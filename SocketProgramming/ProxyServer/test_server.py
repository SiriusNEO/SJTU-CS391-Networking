from flask import Flask, request

app = Flask(__name__)

@app.route('/', methods=['POST'])
def index():
    print(request.json)
    return 'I have receive you request !'

app.run(host='localhost', port='5001')