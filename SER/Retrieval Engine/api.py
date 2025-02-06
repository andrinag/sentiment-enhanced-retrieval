import os
from http.client import HTTPException
from fastapi import FastAPI, UploadFile, File
import torch
from PIL import Image
import psycopg2
import io
from pgvector.psycopg2 import register_vector
import uvicorn
import cv2
from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
import open_clip
from starlette.responses import StreamingResponse
from pathlib import Path
from fastapi import FastAPI
from fastapi import Request, Response
from fastapi import Header
from fastapi.templating import Jinja2Templates

# load the clip model
device = "cuda" if torch.cuda.is_available() else "cpu"
model, _, preprocess = open_clip.create_model_and_transforms('ViT-B-32', pretrained='laion2b_s34b_b79k')
model.eval()
tokenizer = open_clip.get_tokenizer('ViT-B-32')
templates = Jinja2Templates(directory="templates")
CHUNK_SIZE = 1024*1024
video_path = Path("./videos/seafood_1280p.mp4")

FRAME_STORAGE = "./frames" # local storage for the video frames
if not os.path.exists(FRAME_STORAGE):
    os.makedirs(FRAME_STORAGE)

app = FastAPI()

# connection to the local database
conn = psycopg2.connect(
    dbname="multimedia_db",
    user="test",
    host="localhost",
    password="123",
    port="5433"
)
register_vector(conn)

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

################## TEXT TO IMAGE SEARCH #########################
@app.get("/search/{query}")
async def search_images(query: str):
    """
    allows you to search for similar images via query (text) input
    :param query: string
    :return: returns the 3 closest images for the query
    """
    try:
        cursor = conn.cursor()
        query_embedding = get_embedding(input_text=query)
        # TODO: doesn't fully work, need to figure how to make it better
        cursor.execute("""
            SELECT mo.location, me.frame_time, me.embedding <-> %s::vector AS distance
            FROM multimedia_embeddings me
            JOIN multimedia_objects mo ON me.object_id = mo.object_id
            ORDER BY distance ASC
            LIMIT 3;
        """, (query_embedding.tolist(),))

        results = cursor.fetchall()
        cursor.close()
        return {
            "results": [
                {
                    "location": row[0],
                    "frame_time": row[1] if row[1] is not None else None,
                    "distance": row[2]
                }
                for row in results
            ]
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/")
async def read_root(request: Request):
    return templates.TemplateResponse("index.html", context={"request": request})


@app.get("/video")
async def video_endpoint(range: str = Header(None)):
    start, end = range.replace("bytes=", "").split("-")
    start = int(start)
    end = int(end) if end else start + CHUNK_SIZE
    with open(video_path, "rb") as video:
        video.seek(start)
        data = video.read(end - start)
        filesize = str(video_path.stat().st_size)
        headers = {
            'Content-Range': f'bytes {str(start)}-{str(end)}/{filesize}',
            'Accept-Ranges': 'bytes'
        }
        return Response(data, status_code=206, headers=headers, media_type="video/mp4")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001)
