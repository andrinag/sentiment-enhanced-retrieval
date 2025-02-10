import os
import requests

"""
Simple script that lets you upload images from given directory to database
"""


def get_video_list_from_folder(folder_path):
    video_list = [
        os.path.abspath(os.path.join(folder_path, file))
        for file in os.listdir(folder_path)
        if file.endswith(".mp4")
    ]
    return video_list


if __name__ == "__main__":
    folder_path = "/media/V3C/V3C1/video-480p" # on node
    folder_path = "/media/V3C/V3C2/video-480p" # second folder
    # folder_path = "./videos" # local
    video_list = get_video_list_from_folder(folder_path)

    url = "http://127.0.0.1:8000/upload_videos/"

    for video_path in video_list:
        with open(video_path, "rb") as video_file:
            response = requests.post(url, files=[("files", video_file)])
            print(response)