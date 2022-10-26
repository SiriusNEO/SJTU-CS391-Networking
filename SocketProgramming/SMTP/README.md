# Socket Programming: SMTP client

### To run the test

```
python3 test_smtp_client.py
```

By default it uses my email address, **please modify it**.

```
me = 
you = 
auth_pass = 
```

"me" is the sender, "you" is the receiver, "auth_pass" is the generated SMTP pass code.

You can modify the code in `test_smtp_client.py` to test different part (uncomment it!)

```python
if __name__ == "__main__":
    # test_plain_text()
    # test_ssl()
    # test_single_picture()
    # test_attachment()
    test_html()
```



### To use the SMTP Client

Normally Usage

```python
client = SMTPClient(host="smtp.qq.com") # by default port is 25
client.login("<your smtp username>", "<your smtp pass>")
client.send_by_myself(receivers="<a recevier>", data="测试")
```



Use the context manager

```python
with SMTPClient(host="smtp.qq.com") as client:
    client.login("<your smtp username>", "<your smtp pass>")
    client.send_by_myself(
        receivers="<a recevier>",
        data="测试"
    )
```



Use auto create (it will infer the smtp server address and do login)

```python
client = auto_create_client("<your smtp username>", "<your smtp pass>")
client.send_by_myself(receivers="<a recevier>", data="测试")
```



### More Features

- Multiple Receivers

  ```python
  client.send_by_myself(receivers=["<recevier1>", "<recevier2>"], data="测试")
  ```

- Multiple Data (not so useful)

  ```python
  client.send_by_myself(receivers="<a recevier>", data=["测试1", "测试2"])
  ```

- Use MIMEModel to send pictures

  ```python
  mime = MIMEModel.create_png(subject="Picture test", pic_path="pic1.png")
  client.send_by_myself(
      receivers=["<a recevier>"],
      data=mime
  )
  ```

- Use Python MIME

  ```python
  msg = MIMEText("say something", 'plain', 'utf-8')
  msg['Subject'] = ...
  msg['From'] = ...
  msg['To'] = ...
  
  client.send_by_myself(
      receivers=["<a recevier>"],
      data=msg
  )
  ```

- TLS/SSL support

  ```python
  client = SMTPClient(host="smtp.qq.com", port=465, mode="ssl")
  ```

  

