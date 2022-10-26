import requests

proxies = {'http': 'http://localhost:5000'}
# proxies = {'http': 'http://localhost:5001'}
# proxies = {}

# payload = {"cmd_input": "test"}

url = 'http://www.baidu.com/'
# url = 'http://www.baidu.com/img/flexible/logo/pc/index.png'
# url = 'http://localhost'
# resp = requests.post(url, proxies=proxies, json=payload, verify=True)
# resp = requests.get("http://chihaozhang.com/", proxies=proxies)
resp = requests.get(url, proxies=proxies, verify=True)
print(resp)
# with open("index.png", "wb") as fp:
#    fp.write(resp.content)
print(resp.content)