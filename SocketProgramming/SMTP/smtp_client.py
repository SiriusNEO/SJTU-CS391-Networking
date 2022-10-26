import base64
import ssl
import socket
from typing import Union, List

import logging

CRLF: str = "\r\n"


"""
    Headers:
        From
        To
        Date
        Subject
        Content-Type
        Content-Transfer-Encoding
"""

class MIMEModel:
    """
        MIME 简单实现.
    """

    def __init__(self):
        self._headers = []
        self._content = []
        self._headers_tag = []

    def __str__(self) -> str:
        """
            MIME format:
                Header1: xxx <CRLF>
                Header2: xxx <CRLF>
                Header3: xxx <CRLF>
                <CRLF>
                Content1 <CRLF>
                Content2 <CRLF>
        """
        stuff = []
        if self._headers:
            stuff.append(CRLF.join(self._headers))
        # content or multipart
        if self._content:
            stuff.append(CRLF.join(self._content))
        elif self._is_multipart and self._attachment:
            multipart_content = ""
            for i in range(len(self._attachment)):
                multipart_content += "--" + self._boundary + CRLF
                multipart_content += str(self._attachment[i]) + CRLF
            stuff.append(multipart_content + CRLF + "--" + self._boundary + "--" + CRLF)

        ret = (CRLF + CRLF).join(stuff)
        return ret

    def add_header(self, header_name: str, header_value: str):
        """
            给此MIME添加一个Header.
        """
        self._headers.append("{}: {}".format(header_name, header_value))
        self._headers_tag.append(header_name)

    def has_header(self, header_name: str) -> bool:
        """
            判断此MIME是否包含这个header.
        """
        return header_name in self._headers_tag

    def add_content(self, content: str):
        """
            给此MIME添加一个content.
        """
        self._content.append(content)

    @property
    def _is_multipart(self):
        return hasattr(self, "_boundary") and hasattr(self, "_attachment")
    
    def attach(self, submime: "MIMEModel"):
        """
            添加一个子段. 要求自身必须是 multipart 格式.
        """
        assert self._is_multipart
        assert not submime.has_header("From")
        assert not submime.has_header("To")
        assert not submime.has_header("Subject")
        self._attachment.append(submime)
    

    @staticmethod
    def create_base(from_name = "", to_name = "", subject = "") -> "MIMEModel":
        """
            创建并返回一个具有基本几个信息header的MIME.
        """
        mime = MIMEModel()
        if from_name:
            mime.add_header("From", from_name)
        if to_name:
            mime.add_header("To", to_name)
        if subject:
            mime.add_header("Subject", subject)
        return mime

    @staticmethod
    def create_text(from_name = "", to_name = "", subject = "", subtype="plain", text = []) -> "MIMEModel":
        """
            创建并返回一个文本类型的MIME.
        """
        mime = MIMEModel.create_base(from_name, to_name, subject)
        mime.add_header("Content-Type", "text/{}".format(subtype))
        if not isinstance(text, list):
            text = [text]
        for single_text in text:
            mime.add_content(single_text)
        return mime
    
    @staticmethod
    def create_png(from_name = "", to_name = "", subject = "", pic_path: str = "", file_name: str = "") -> "MIMEModel":
        """
            创建并返回一个PNG图片类型的MIME.
        """
        mime = MIMEModel.create_base(from_name, to_name, subject)
        mime.add_header("Content-Type", "image/png")
        mime.add_header("Content-Transfer-Encoding", "base64")

        if pic_path:
            with open(pic_path, "rb") as fp:
                content = str(base64.b64encode(fp.read()), encoding='utf8')
                mime.add_content(content)

        if file_name:
            mime.add_header("Content-Disposition",  "attachment; filename=\"{}\"".format(file_name))
        
        return mime

    @staticmethod
    def create_multipart(from_name = "", to_name = "", subject = "", subtype = "related") -> "MIMEModel":
        """
            创建并返回一个 Multipart MIME.
        """
        mime = MIMEModel.create_base(from_name, to_name, subject)
        mime._boundary = "simple_boundary"
        mime._attachment = []
        mime.add_header("Content-Type", "multipart/{}; boundary=\"{}\"".format(subtype, mime._boundary))
        return mime


class SMTPClient:
    """
        SMTP 客户端简单实现.
    """
    
    # socket config
    BUF_SIZE: int = 1024
    
    # status code
    SERVICE_READY_CODE: str = "220"
    WAIT_AUTH_CODE: str = "334"
    AUTH_SUCCESS_CODE: str = "235"
    OK_CODE: str = "250"
    START_INPUT_CODE: str = "354"

    # templates
    HELO: str = "HELO Alice" + CRLF
    LOGIN: str = "AUTH LOGIN" + CRLF
    MAIL: str = "MAIL FROM:<{}>" + CRLF
    RCPT: str = "RCPT TO:<{}>" + CRLF
    DATA: str = "DATA" + CRLF
    END_DATA: str = "{}.{}".format(CRLF, CRLF)
    QUIT: str = "QUIT" + CRLF


    def __init__(self, host: str = "", port: Union[str, int] = 25, mode = "plain"):
        """
            初始化 SMTP 客户端. 如果给定了 host 与 port, 将会自动 connect.
            
            Parameters
            ----------
            host: str
                SMTP 服务器名, 如 smtp.qq.com
            port: Union[str, int]
                SMTP 端口号, 默认是 25, SSL 下需要走 465
            mode: str
                plain/ssl, 表示明文/SSL加密
        """
        self._connected_flag = False
        self._login_flag = False
        self._host = host
        self._mode = mode
        
        # collect code name for better display
        self._code_name_table = {}
        for key, value in SMTPClient.__dict__.items():
            if key.endswith("_CODE"):
                self._code_name_table[value] = key
        # connect
        if host:
            self.connect(host, port)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, exc_tb):
        self._destruct()

    def __del__(self):
        self._destruct()

    def _destruct(self):
        if self._login_flag:
            self.quit()
        
        if self._connected_flag:
            self._socket.close()
            logging.info("Socket closed.")
            self._connected_flag = False


    def connect(self, host: str, port: Union[str, int] = 25):
        """
            创建 socket 并发送 HELO.
            
            Parameters
            ----------
            host: str
                SMTP 服务器名.
            port: Union[str, int]
                SMTP 端口号.
        """
        # socket create
        try:
            # AF_INET (IP v4), STREAM socket (TCP)
            self._socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        except Exception as e:
            logging.error("Socket create error: {}".format(e.args))
            raise e
        else:
            logging.info("Socket create success")
        
        # socket SSL wrap
        if self._mode == "ssl":
            context = ssl.create_default_context()
            self._socket = context.wrap_socket(self._socket, server_hostname=self._host)
        
        # socket connection establish
        try:
            self._socket.connect((host, port))
            recv_msg = self._socket.recv(self.BUF_SIZE).decode()
            self._check_code(recv_msg, self.SERVICE_READY_CODE)
        except Exception as e:
            logging.error("Socket connect error: {} server::host={}, port={}".format(e.args, host, port))
            raise e
        else:
            logging.info("Socket connect success. server::host={}, port={}".format(host, port))
        
        # helo
        try:
            self._socket_send_and_check(
                to_send=self.HELO,
                expected_code=self.OK_CODE
            )
        except Exception as e:
            logging.error("Send HELO error: {}".format(e.args))
            raise e
        else:
            logging.info("HELO OK.")

        self._connected_flag = True


    def login(self, username: str, auth_pass: str):
        """
            登录, 使用 AUTH LOGIN
            
            Parameters
            ----------
            username: str
                SMTP 用户名, 一般是邮箱号.
            auth_pass: str
                SMTP 的代码, 注意一般不是某个邮箱服务的账号密码.
        """
        try:
            self._check_connected()
            
            # 1. send AUTH LOGIN
            self._socket_send_and_check(
                to_send=self.LOGIN,
                expected_code=self.WAIT_AUTH_CODE
            )

            # 2. send username
            logging.info("username b64: " + str(base64.b64encode(username.encode()), encoding='utf8'))
            self._socket_send_and_check(
                to_send="{}{}".format(str(base64.b64encode(username.encode()), encoding='utf8'), CRLF),
                expected_code=self.WAIT_AUTH_CODE
            )

            # 3. send auth pass
            self._socket_send_and_check(
                to_send="{}{}".format(str(base64.b64encode(auth_pass.encode()), encoding='utf8'), CRLF),
                expected_code=self.AUTH_SUCCESS_CODE
            )
        except Exception as e:
            logging.error("Login error: {}".format(e.args))
            raise e
        else:
            logging.info("Login success. username={}".format(username))
            self.username = username
            self._login_flag = True
    

    def quit(self):
        """
            退出. 使用 QUIT.
        """
        try:
            self._check_connected()
            self._check_login()
            
            self._socket.sendall(self.QUIT.encode())
            # not need to check code (?)
        except Exception as e:
            logging.error("Quit error: {}".format(e.args))
            raise e
        else:
            logging.info("Quit success. from username={}".format(self.username))
            self.username = ""
            self._login_flag = False

    
    def send(self, sender: str, receivers: Union[str, List[str]], data):
        """
            发送邮件. 流程是 MAIL -> RCPT -> DATA -> <CRLF>.<CRLF> (to end it)
            
            Parameters
            ----------
            sender: str
                发送者, 会被标注在 From 上
            receivers: Union[str, List[str]]
                接收者, 可以有很多个, 会被标注在 To 上用 , 分隔.
            data: List[Union[str, MIMEModel, ...]]
                发送数据, 类型可以是 str (自动传成plain_text), MIMEModel 或者 python email包里的 MIME
        """
        try:
            self._check_connected()
            self._check_login()

            # 1. MAIL
            self._socket_send_and_check(
                to_send=self.MAIL.format(sender),
                expected_code=self.OK_CODE
            )

            # 2. RCPT
            ok_receivers = []
            wrong_receivers = []
            if isinstance(receivers, str):
                receivers = [receivers]
            for receiver in receivers:
                try:
                    self._socket_send_and_check(
                        to_send=self.RCPT.format(receiver),
                        expected_code=self.OK_CODE
                    )
                except:
                    wrong_receivers.append(receiver)
                else:
                    ok_receivers.append(receiver)
            
            # 3. DATA
            self._socket_send_and_check(
                to_send=self.DATA,
                expected_code=self.START_INPUT_CODE
            )

            # 4. send data...
            if isinstance(data, str) or isinstance(data, list): # List[str]
                data = MIMEModel.create_text(text=data)
            
            if isinstance(data, MIMEModel):
                if not data.has_header("From"):
                    data.add_header("From", sender)
                if not data.has_header("To"):
                    data.add_header("To", ", ".join(receivers))
                self._socket.sendall(str(data).encode())
            else:
                logging.info(data.as_string())
                self._socket.sendall(data.as_string().encode())

            logging.debug("send end")

            # 5. end
            self._socket_send_and_check(
                to_send=self.END_DATA,
                expected_code=self.OK_CODE
            )
        except Exception as e:
            logging.error("Send error: {}".format(e.args))
            raise e
        else:
            logging.info("Send success with OK receivers: {}, wrong receivers: {}".format(str(ok_receivers), str(wrong_receivers)))
    

    def send_by_myself(self, receivers: Union[str, List[str]], data):
        """
            将sender设为登录时所用的username, 然后send.
        """
        self.send(self.username, receivers, data)

    def _socket_send_and_check(self, to_send: str, expected_code: str):
        self._socket.sendall(to_send.encode())
        recv_msg = self._socket.recv(self.BUF_SIZE).decode()
        self._check_code(recv_msg, expected_code)
        logging.debug("Socket receive: {}".format(recv_msg))    

    def _check_code(self, msg: str, code: str):
        if not msg.startswith(code):
            raise Exception("{}({}) not received. Instead received: {}".format(self._code_name_table[code], code, msg))
    
    def _check_login(self):
        if not self._login_flag:
            logging.error("Please login first.")
            raise Exception("not login")
    
    def _check_connected(self):
        if not self._connected_flag:
            logging.error("Please connect first.")
            raise Exception("unconnected")



def auto_create_client(username: str, auth_pass: str) -> SMTPClient:
    """
        便于创建一个 client 的方法. 它能够根据你的 user 邮箱自动推导出 smtp 服务器地址
        (实际上就是在 @ 前面加 smtp)
    """
    at_pos = username.find('@')
    if at_pos == -1:
        error_msg = "auto infer error: username address does not contain @"
        logging.error(error_msg)
        raise Exception(error_msg)

    client = SMTPClient(host="smtp." + username[at_pos+1:], port=25)
    client.login(username, auth_pass)
    return client


# logging.basicConfig(level = logging.DEBUG, format = '%(asctime)s - %(name)s - %(levelname)s - [CS391-SMTP-Demo] %(message)s')
logging.basicConfig(level = logging.INFO, format = '%(asctime)s - %(name)s - %(levelname)s - [CS391-SMTP-Demo] %(message)s')