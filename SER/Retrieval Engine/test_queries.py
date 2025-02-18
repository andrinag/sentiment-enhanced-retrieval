
import requests


"""
test script for text to image search 
"""

query = ("Schmetterling")
url = f"http://127.0.0.1:8001/search/{query}"

response = requests.get(url)

if response.status_code == 200:
    print(response.json())
else:
    print(f"Error: {response.status_code}, {response.text}")
