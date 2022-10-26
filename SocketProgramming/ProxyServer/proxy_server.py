import socket
import sys
import logging
from typing import Union
import getopt
import traceback
import os


# logging.basicConfig(level = logging.DEBUG, format = '%(asctime)s - %(name)s - %(levelname)s - [CS391-ProxyServer-Demo] %(message)s')
logging.basicConfig(level = logging.INFO, format = '%(asctime)s - %(name)s - %(levelname)s - [CS391-ProxyServer-Demo] %(message)s')

CRLF: str = "\r\n"

class ProxyServer:
    """
        A simple Proxy Server implemented in socket.
        Able to proxy the HTTP (without SSL) request and support:
        - GET/POST
        - Error handle with a simple 404 page
        - GET Cache
    """

    # socket config
    DEFAULT_HOST: str = "localhost"
    DEFAULT_PORT: str = "5000"

    PROXY_CACHE_DIR: str = ".proxy_cache"

    def __init__(self, host: str = DEFAULT_HOST, port: Union[str, int] = DEFAULT_PORT, max_connections: int = 5, buf_size: int = 1024) -> None:
        """
            Initialize the server.
            - Create the server socket
            - Create the cache dir
        """
        try:
            self._server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            
            if host == self.DEFAULT_HOST:
                logging.warning("Use default host: {}".format(self.DEFAULT_HOST))
            if port == self.DEFAULT_PORT:
                logging.warning("Use default port: {}".format(self.DEFAULT_PORT))

            # https support
            # context = ssl.create_default_context()
            # self._server_socket = context.wrap_socket(self._server_socket, server_side=True)
            
            self._server_socket.bind((host, int(port)))
            self._host = host
            self._port = str(port)
            self._max_connections = max_connections
            self._buf_size = buf_size
        except Exception as e:
            logging.error("Socket create error: {}".format(e.args))
            raise e
        else:
            logging.info("Socket create success.")
        
        if not os.path.exists(self.PROXY_CACHE_DIR):
            os.mkdir(self.PROXY_CACHE_DIR)
            logging.info("Proxy cache directory created.")
    

    def __del__(self):
        """
            Destruct. Will not remove the cache dir.
        """
        self._server_socket.close()
        logging.info("Socket closed.")
    
    
    def run(self):
        """
            Run (forever) the proxy server.
        """
        
        try:
            self._server_socket.listen(self._max_connections)
        except Exception as e:
            logging.error("Socket listen error: {}".format(e.args))
            raise e
        else:
            logging.info("Proxy Server running on: host={}, port={}. Use <CTRL-C> to stop.".format(self._host, self._port))

        while True:
            try:
                logging.info("Proxy Server waiting for connections...")
                client_socket, addr = self._server_socket.accept()
                logging.info("Connection from: {}".format(addr))
                message = client_socket.recv(self._buf_size).decode()
                req = self._http_packet_resolve(message)
                logging.debug("Receive message from client: {}".format(req))

                if req["Method"] == "GET":
                    packet = self._make_get(
                        url = req["Url"],
                        host = req["Host"]
                    )
                else:
                    # assert req["Method"] == "POST"
                    # POST method, directly forward it
                    packet = message

                # check cache
                cache_path = self.PROXY_CACHE_DIR + "/" + req["Url"].replace("/", "-").replace(":", ".") + ".cachefile"

                if req["Method"] == "GET" and os.path.exists(cache_path):
                    with open(cache_path, "rb") as fp:
                        cached_resp = fp.read()
                        cached_resp_str = self._bytes_str_modified(str(cached_resp))
                        cached_packet = self._http_packet_resolve(cached_resp_str, is_response=True)
                        # Add a If-Modified-Since
                        if "Last-Modified" in cached_packet:
                            last_modified = cached_packet["Last-Modified"]
                        else:
                            last_modified = cached_packet["Date"]
                        modified_packet = self._make_get(
                            url = req["Url"],
                            host = req["Host"],
                            If_Modified_Since=last_modified
                        )
                        logging.debug(modified_packet)

                        resp = self._http_request(req['Host'], modified_packet)
                        resp_packet = self._http_packet_resolve(
                            self._bytes_str_modified(str(resp)),
                            is_response=True
                        )
                    
                    if resp_packet["Code"] == "200":
                        # Update cache
                        with open(cache_path, "wb") as fp:
                            fp.write(resp)
                        logging.info("Cache updated. Cache file: {}".format(cache_path))
                        client_socket.sendall(resp)
                    else:
                        assert resp_packet["Code"] == "304"
                        logging.info("Cache hit! Cache file: {}".format(cache_path))
                        client_socket.sendall(cached_resp)
                else:
                    resp = self._http_request(
                            req['Host'], 
                            packet
                        )
                    resp_packet = self._http_packet_resolve(
                        self._bytes_str_modified(str(resp)),
                        is_response=True
                    )
                    # logging.debug(resp_packet)

                    # Handling error
                    # Only support 404 now
                    if resp_packet["Code"] != "200":
                        logging.warning("Error status code: " + resp_packet["Code"])
                        with open("404.html", "rb") as fp:
                            resp = self._make_response(
                                code="404",
                                status_name="Not Found",
                                content=fp.read()
                            )
                    # Cache it.
                    else:
                        if req["Method"] == "GET":
                            with open(cache_path, "wb") as fp:
                                fp.write(resp)
                                logging.info("Cache file created: " + cache_path)
                    client_socket.sendall(resp)

                client_socket.close()
            except Exception as e:
                logging.error("Proxy error: {}".format(e.args))
                client_socket.close()
                # raise e
                print(traceback.format_exc())
    

    def _http_request(self, host: str, req_str: str):
        """
            Send a http request and get the response, implemented in socket.

            Note:
                如果报文长度大于 socket.recv 的缓冲区长度，需要再次接受，而不断接受又会导致 recv 卡死，所以需要预先知道接受的数据长度。
                解决方法是，先接受一段数据，然后在报文中手动解析出 Content-Length 字段的值，
                再找到 headers 与 content 分割处从而推断出 header 的长度，
                最后便可以获得总长度：header_length + 4 + content_length（注意两个 <CRLF> 占 4 个位置），这样才能确保接受完整内容。
        """
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.connect((host, 80))
            sock.sendall(req_str.encode())
            
            data = sock.recv(self._buf_size)
            data_str = self._bytes_str_modified(str(data))

            content_length_word = "Content-Length:"
            content_length_pos = data_str.find(content_length_word)
            content_end_pos = data_str.find(CRLF+CRLF)
            # logging.debug(data_str)

            # if it has "Content-Length"
            if content_length_pos != -1 and content_end_pos != -1:
                i = content_length_pos + len(content_length_word)
                temp_str = ""
                while i < len(data_str):
                    if str.isdigit(data_str[i]):
                        temp_str += data_str[i]
                    elif data_str[i] != " ":
                        break
                    i += 1
                content_length = int(temp_str)
                logging.debug("end pos: " + str(content_end_pos))
                logging.debug("length: " + str(content_length))
                while len(data) < content_end_pos + 4 + content_length:
                    raw = sock.recv(self._buf_size)
                    data += raw

        return data

    @staticmethod
    def _make_get(url: str, host: str, **other_headers):
        """
            Make a simple GET request with very few headers.
        """
        return CRLF.join([
            "GET {} HTTP/1.1".format(url),
            "Host: {}".format(host)           
        ] + ["{}: {}".format(key.replace("_", "-"), value) for key, value in list(other_headers.items())]
        ) + CRLF + CRLF
    

    @staticmethod
    def _make_response(code: str, status_name: str, content: bytes):
        """
            Make a simple response http packet.
        """
        return (CRLF.join([
                    "HTTP/1.1 {} {}".format(code, status_name),
                    "Content-Type: text/html",
                ]) + CRLF + CRLF).encode() + content + (CRLF).encode()

    @staticmethod
    def _http_packet_resolve(message: str, is_response = False):
        """
            Return a dict with keys:

            Content: content of the packet
            (request)
            Method: GET/POST
            Url: the url of the request
            (response)
            Code: status code
            Status_Name: status name
        """
        ret_dict = {}
        lines = message.split(CRLF)
        
        # logging.debug(message)
        # logging.info(len(lines))
        
        reach_content = False
        for i in range(len(lines)):
            # first line
            line = lines[i].strip()
            # logging.debug(line + " " + str(len(line)))

            if line and reach_content:
                ret_dict["Content"] += line + CRLF
                continue

            if line and line[0].isalpha():
                if i == 0:
                    objs = line.split()
                    assert len(objs) >= 3
                    if is_response:
                        # HTTP/1.1 200 OK
                        logging.debug(objs[0])
                        assert objs[0].strip() in ("HTTP/1.0", "HTTP/1.1")
                        ret_dict["Code"] = objs[1]
                        ret_dict["Status_Name"] = ''.join(objs[2:])
                    else:
                        # GET url HTTP/1.1
                        assert objs[2].strip() in ("HTTP/1.0", "HTTP/1.1")
                        ret_dict["Method"] = objs[0].strip()
                        ret_dict["Url"] = objs[1].strip()
                else:
                    objs = line.split(": ")
                    assert len(objs) == 2
                    ret_dict[objs[0].strip()] = objs[1].strip()
            else:
                ret_dict["Content"] = ""
                reach_content = True

        return ret_dict

    @staticmethod
    def _bytes_str_modified(raw: str):
        return raw[2:][:-1].replace("\\r", "\r").replace("\\n", "\n")


def show_help():
    print("Usage: python3 proxy_server.py [--help] [options]\n")
    print("Options:")
    print("\t-H, --host [HOST] \t Host to bind")
    print("\t-P, --port [PORT] \t Port to listen on")
    print("\t-h, --help        \t Show this message")


if __name__ == "__main__":
    args_dict = {
        "max_connections": 5
    }
    
    # command line
    opts, _ = getopt.getopt(sys.argv[1:], "-h-H:-P:", ["--help", "--host=", "--port="])
    for opt_name, opt_value in opts:
        if opt_name in ("-h", "--help"):
            show_help()
            exit(0)
        if opt_name in ("-H", "--host"):
            args_dict["host"] = opt_value
        if opt_name in ("-P", "--port"):
            args_dict["port"] = opt_value
    
    proxy_server = ProxyServer(**args_dict)
    try:
        proxy_server.run()
    except:
        print(traceback.format_exc())
        exit(0)