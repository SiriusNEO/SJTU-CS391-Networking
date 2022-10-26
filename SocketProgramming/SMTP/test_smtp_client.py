from smtp_client import SMTPClient, MIMEModel

"""
    发件人与收件人, 使用我的 qq 与 sjtu 交互.
    From 和 To 字段默认也填这两个.
"""

me = "siriusneo@qq.com"
you = "chaosfunc@gmail.com"
auth_pass = "ltvxndzqberhbcdd"

test_word = "[CS391-SMTP-Demo] Hello!"

def test_plain_text():
    """
        纯文本发送
    """
    with SMTPClient(host="smtp.qq.com") as client:
        client.login(me, auth_pass)
        client.send_by_myself(
            receivers=[you],
            data=MIMEModel.create_text(subject="纯文本测试", text=test_word)
        )
    

def test_ssl():
    """
        开启 SSL/TLS 并使用 465 端口进行发送
    """
    with SMTPClient(host="smtp.qq.com", port=465, mode="ssl") as client:
        client.login(me, auth_pass)
        client.send_by_myself(
            receivers=["chaosfunc@gmail.com"],
            data=MIMEModel.create_text(subject="SSL/TLS 测试", text=test_word)
        )


def test_single_picture():
    """
        单图片发送
    """
    with SMTPClient(host="smtp.qq.com", port=465, mode="ssl") as client:
        client.login(me, auth_pass)
        mime = MIMEModel.create_png(subject="单图片测试", pic_path="pic1.png")
        client.send_by_myself(
            receivers=["siriusneo@sjtu.edu.cn"],
            data=mime
        )


def test_attachment():
    """
        文字 + 附件发送
    """
    with SMTPClient(host="smtp.qq.com") as client:
        client.login(me, auth_pass)
        mime = MIMEModel.create_multipart(me, you, "文字+附件测试")
        mime1 = MIMEModel.create_text(text=test_word)
        mime2 = MIMEModel.create_png(pic_path="pic1.png", file_name="pic1.png")
        mime.attach(mime1)
        mime.attach(mime2)
        client.send_by_myself(
            receivers=[you],
            data=mime
        )


def test_html():
    """
        发送带图 html
    """
    html_msg = """
<p>html 测试</p>
<p><a href="https://siriusneo.top/">SiriusNEO</a></p>
<p>测试图片:</p>
<p><img src="cid:image1"></p>
"""

    with SMTPClient(host="smtp.qq.com") as client:
        client.login(me, auth_pass)
        mime = MIMEModel.create_multipart(me, you, "html 测试")
        mime1 = MIMEModel.create_text(subtype="html", text=html_msg)
        mime2 = MIMEModel.create_png(pic_path="pic1.png", file_name="pic1.png")
        mime2.add_header('Content-ID', '<image1>')
        mime.attach(mime1)
        mime.attach(mime2)
        client.send_by_myself(
            receivers=[you],
            data=mime
        )


if __name__ == "__main__":
    # test_plain_text()
    # test_ssl()
    # test_single_picture()
    # test_attachment()
    test_html()
