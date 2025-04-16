from exceptiongroup import catch
from fastapi import FastAPI
import torch
from PIL import Image
import psycopg2
import io
import uvicorn
import cv2
import open_clip
from concurrent.futures import ThreadPoolExecutor
from fastapi import UploadFile, File, HTTPException
import os
import traceback
import numpy as np
import pandas as pd
from sentiment_detector import SentimentDetector
from psycopg2 import pool
from sklearn.manifold import TSNE
import ast

MAX_WORKERS = 16
BATCH_SIZE = 50
FRAME_STORAGE = "./frames"

# load the clip model
device = "cuda" if torch.cuda.is_available() else "cpu"
model, _, preprocess = open_clip.create_model_and_transforms('ViT-B-32', pretrained='laion2b_s34b_b79k')
model.eval()
tokenizer = open_clip.get_tokenizer('ViT-B-32')

#  local storage of the video frames
FRAME_STORAGE = "./frames"
if not os.path.exists(FRAME_STORAGE):
    os.makedirs(FRAME_STORAGE)

mastershot_dir_1 = "/home/ubuntu/V3C1_msb/msb"
# mastershot_dir_1  = "./V3C1_msb/msb"
SD = SentimentDetector()

app = FastAPI()

db_pool = psycopg2.pool.SimpleConnectionPool(
    1, 20,  # Min and max connections
    dbname="multimedia_db",
    user="test",
    password="123",
    host="localhost",
    port="5432"
)


def get_db_connection():
    """
    returns multiple DB connections
    """
    return db_pool.getconn()


def release_db_connection(conn):
    """
    closes multiple db connections
    """
    db_pool.putconn(conn)


def get_embedding(input_text=None, input_image=None):
    """
    calculates the embedding of text and images with CLIP
    :param input_text: input text to embed
    :param input_image: input image to embed
    :return: returns the embedded values
    """
    with torch.no_grad():
        if input_text:
            inputs = tokenizer([input_text])
            features = model.encode_text(inputs)
            return features.numpy().flatten()
        elif input_image:
            image = Image.open(io.BytesIO(input_image)).convert("RGB")
            inputs = preprocess(image).unsqueeze(0)
            features = model.encode_image(inputs)
            return features.numpy().flatten()


def normalize_embedding(embedding):
    """
    normalizes the embedding
    """
    norm = np.linalg.norm(embedding)
    if norm == 0:
        return embedding
    return embedding / norm


def check_file_exists(filename):
    """
    checks if a file with the same name already exists in the database
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    try:
        cursor.execute("SELECT object_id FROM multimedia_objects WHERE location = %s;", (filename,))
        result = cursor.fetchone()
        return result[0] if result else None
    finally:
        cursor.close()
        release_db_connection(conn)


###################### UPLOADING IMAGES ##############################

def insert_image_metadata(filename):
    """
    inserts the metadata of the image into the multimedia_object table
    :param filename: name of the image file
    :return: object_id of the just inserted image tuple
    """
    try:
        cursor = get_db_connection().cursor()
        cursor.execute("INSERT INTO multimedia_objects (location) VALUES (%s) RETURNING object_id;",
                       (filename,))
        object_id = cursor.fetchone()[0]
        get_db_connection().commit()
        return object_id
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/upload_image/")
async def upload_files(files: list[UploadFile] = File(...)):
    """
    uploads the local images form a list into the DB with their embeddings
    :param files: list of image files to upload
    :return: status of request
    """
    try:
        cursor = get_db_connection().cursor()
        for file in files:
            file_content = file.file.read()
            file.file.seek(0)
            embedding = get_embedding(input_image=file_content)
            embedding = normalize_embedding(embedding)

            # first insert the image and its meatdata (file, id etc.)
            object_id = insert_image_metadata(file.filename)
            cursor.execute(
                "INSERT INTO multimedia_embeddings (object_id, frame_time, embedding) VALUES (%s, NULL, %s);",
                (object_id, embedding.tolist())
            )

        get_db_connection().commit()
        return {"message": f"Uploaded {len(files)} images successfully"}

    except Exception as e:
        get_db_connection().rollback()
        raise HTTPException(status_code=500, detail=str(e))

    finally:
        cursor.close()


######################## UPLOADING VIDEO ################################

def extract_frames(video_path, output_folder, msb_file):
    """
    Extract frame per mastershot boundary (middle frame), and extract MP3 audio clips for start and end.
    """
    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    try:
        boundaries = pd.read_csv(msb_file, sep="\t")
    except Exception as e:
        print(f"Error reading boundary file {msb_file}: {e}")
        return []

    cap = cv2.VideoCapture(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS)

    if fps <= 0:
        print(f"Error: FPS is zero for:  {video_path}")
        return []

    frame_paths = []
    audio_files = []

    for index, row in boundaries.iterrows():
        start_frame = int(row['startframe'])
        end_frame = int(row['endframe'])
        start_time = row['starttime']
        end_time = row['endtime']

        middle_frame = (start_frame + end_frame) // 2
        middle_time = (start_time + end_time) / 2
        cap.set(cv2.CAP_PROP_POS_FRAMES, middle_frame)
        success, image = cap.read()

        if not success or image is None:
            print(f"Could not extract frame {middle_frame} from {video_path}")
            continue

        frame_filename = os.path.join(output_folder, f"{os.path.basename(video_path)}_frame_{middle_frame}.jpg")
        cv2.imwrite(frame_filename, image)
        frame_paths.append((frame_filename, middle_time, middle_frame))
        base_name = os.path.splitext(os.path.basename(video_path))[0]
        audio_filename = os.path.join("./mp3", f"{base_name}_msb_{index}.mp3")
        audio_files.append(audio_filename)
        try:
            SD.convert_mp4_to_mp3(video_path, audio_filename, start_time=start_time, end_time=end_time)
            print(f"Saved audio: {audio_filename}")
        except Exception as audio_error:
            print(f"Failed to convert audio for {video_path} msb {index}: {audio_error}")

    cap.release()
    return frame_paths, audio_files


def insert_video_metadata(video_filename):
    """
    inserts the metadata of the video into the multimedia_object table
    :param video_filename: name of the video file
    :return: object_id of the just inserted video tuple
    """
    cursor = get_db_connection().cursor()
    try:
        cursor.execute("INSERT INTO multimedia_objects (location) VALUES (%s) RETURNING object_id;",
                       (video_filename,))
        object_id = cursor.fetchone()[0]
        get_db_connection().commit()
        return object_id
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


def process_frame(frame_info, object_id, audio_file):
    """
    Processes a single video frame: extracts embeddings, detects emotion, stores in DB,
    and analyzes audio sentiment.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    try:
        frame_path, frame_time, middle_frame = frame_info

        # -------- IMAGE EMBEDDING --------
        try:
            image = Image.open(frame_path).convert("RGB")
            img_byte_arr = io.BytesIO()
            image.save(img_byte_arr, format="PNG")
            embedding = get_embedding(input_image=img_byte_arr.getvalue())
            embedding = normalize_embedding(embedding)

            cursor.execute(
                """
                INSERT INTO multimedia_embeddings (object_id, frame_location, frame, frame_time, embedding)
                VALUES (%s, %s, %s, %s, %s) RETURNING id;
                """,
                (object_id, frame_path, int(middle_frame), float(frame_time), embedding.tolist())
            )
            embedding_id = cursor.fetchone()[0]
        except Exception as e:
            print(f"[ERROR] Failed to process image or insert embedding for frame: {frame_path}")
            print(traceback.format_exc())
            conn.rollback()
            return

        # -------- FACE & EMOTION DETECTION --------
        try:
            emotion, confidence, sentiment, annotated_path = SD.detect_faces_and_get_emotion_with_plots(frame_path)
            if emotion and confidence:
                # Insert detected face normally
                cursor.execute(
                    """
                    INSERT INTO Face (embedding_id, emotion, confidence, sentiment, path_annotated_faces)
                    VALUES (%s, %s, %s, %s, %s);
                    """,
                    (embedding_id, emotion, confidence, sentiment, annotated_path)
                )
            else:
                # Insert placeholder if no emotion found
                cursor.execute(
                    """
                    INSERT INTO Face (embedding_id, emotion, confidence, sentiment, path_annotated_faces)
                    VALUES (%s, %s, %s, %s, %s);
                    """,
                    (embedding_id, "no_face", 0.0, "neutral", None)
                )
            conn.commit()
        except Exception as e:
            print(f"[WARNING] Could not detect or insert face/emotion info for: {frame_path}")
            print(traceback.format_exc())
            conn.rollback()

        # -------- AUDIO ANALYSIS --------
        try:
            if audio_file and os.path.exists(audio_file):
                audio_text = SD.get_text_from_mp3(audio_file)
                if audio_text:
                    sentiment_result = SD.get_emotion_from_text(audio_text)
                    if sentiment_result and isinstance(sentiment_result, list) and len(sentiment_result) > 0:
                        top_emotion = sentiment_result[0]['label']
                        audio_confidence = sentiment_result[0]['score']
                        sentiment_category = SD.get_sentiment_from_emotion(top_emotion)
                    else:
                        top_emotion = "unknown"
                        audio_confidence = 0.0
                        sentiment_category = "neutral"
                else:
                    audio_text = None
                    top_emotion = "no_transcription"
                    audio_confidence = 0.0
                    sentiment_category = "neutral"

                # Always insert something
                cursor.execute("""
                    INSERT INTO ASR (embedding_id, text, emotion, confidence, sentiment)
                    VALUES (%s, %s, %s, %s, %s);
                """, (embedding_id, audio_text, top_emotion, audio_confidence, sentiment_category))
                conn.commit()
            else:
                print(f"[WARNING] Audio file not found or not provided: {audio_file}")
        except Exception as e:
            print(f"[ERROR] Audio sentiment analysis or DB insert failed for: {audio_file}")
            print(traceback.format_exc())
            conn.rollback()

    except Exception as e:
        print("[FATAL ERROR] Unexpected failure in process_frame()")
        print(traceback.format_exc())
        conn.rollback()
    finally:
        cursor.close()
        release_db_connection(conn)


@app.post("/upload_videos/")
async def process_videos(files: list[UploadFile] = File(...)):
    """
    takes a list of videos and calls the corresponding methods to extract the frames, calculate the embedding and
    insert into the DB
    """
    try:
        for file in files:
            existing_object_id = check_file_exists(file.filename)
            if existing_object_id:
                print(f"Skipping {file.filename}: Already exists in the database.")
                continue

            video_filename = file.filename
            video_path = os.path.join(FRAME_STORAGE, video_filename)
            with open(video_path, "wb") as f:
                f.write(file.file.read())
            conn = get_db_connection()
            cursor = conn.cursor()
            # Todo change this to the metadata method
            cursor.execute("INSERT INTO multimedia_objects (location) VALUES (%s) RETURNING object_id;",
                           (file.filename,))
            object_id = cursor.fetchone()[0]
            conn.commit()
            cursor.close()
            release_db_connection(conn)

            boundary_file = os.path.splitext(mastershot_dir_1)[0] + "/" + video_filename.split(".")[0] + ".tsv"
            if not os.path.exists(boundary_file):
                print(f"Skipping {video_filename}: No boundary file found ({boundary_file})")
                continue

            frame_data, audio_list = extract_frames(video_path, FRAME_STORAGE, boundary_file)
            with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
                executor.map(lambda args: process_frame(*args),zip(frame_data, [object_id] * len(frame_data), audio_list))

        return {"message": f"Uploaded and processed {len(files)} videos successfully"}

    except Exception as e:
        error_message = traceback.format_exc()
        print("Error Traceback:", error_message)
        return {"error": str(e), "traceback": error_message}


@app.post("/generate_low_dim_embeddings/")
async def generate_low_dim_embeddings():
    """
    generates the low dimensional
    """
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        cursor.execute("SELECT id, embedding FROM multimedia_embeddings WHERE embedding IS NOT NULL;")
        rows = cursor.fetchall()
        #rows = cursor.fetchone() # for testing purpose
        #print(f"Calculating reduced embedding for {rows}")

        ids = []
        embeddings = []

        for row in rows:
            ids.append(row[0])
            embedding_list = ast.literal_eval(row[1])
            embeddings.append(np.array(embedding_list))

        embeddings = np.array(embeddings)

        # Perform the TSNE (2 dimensions)
        tsne = TSNE(n_components=2, perplexity=30, n_iter=1000, random_state=42)
        reduced_embeddings = tsne.fit_transform(embeddings)

        # save the result in the multimedia_embeddings table
        if len(embeddings) < 5:
            return {"error": f"Not enough embeddings for t-SNE (got {len(embeddings)}). Need at least 5."}

        for i, emb_id in enumerate(ids):
            x, y = reduced_embeddings[i]
            cursor.execute(
                "UPDATE multimedia_embeddings SET tsne_x = %s, tsne_y = %s WHERE id = %s;",
                (float(x), float(y), emb_id)
            )

        conn.commit()
        return {"message": f"Successfully stored 2D t-SNE embeddings for {len(ids)} items."}

    except Exception as e:
        error_message = traceback.format_exc()
        print("Error Traceback:", error_message)
        if conn:
            conn.rollback()
        return {"error": str(e), "traceback": error_message}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)