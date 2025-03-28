import os
import requests
"""
script for uploading all the images from a certain folder 
"""

url = "http://127.0.0.1:8000/upload_image/"
folder_path = "images"  # folder path to the images


def get_file_list():
    """
    creates a list of files at file location
    :return: list of image names
    """
    files = []
    for filename in os.listdir(folder_path):
        file_path = os.path.join(folder_path, filename)

        if filename.lower().endswith((".png")):
            files.append(("files", (filename, open(file_path, "rb"), "image/jpeg")))

    return files


if __name__ == "__main__":
    # uploading images from caltech dataset
    # copy_all_images("256_ObjectCategories")
    files = get_file_list()
    print(len(files))
    for i in range (0 ,len(files), 100):
        print(f"currently working on files {i} of {len(files) / 1000}")
        response = requests.post(url, files=[files[i]])