#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MITM 代理：解密并记录官方 CLI 发往 codebuddy.ai 的完整请求头与体。"""
import socket
import ssl
import threading
import json
import datetime

LOG = "/tmp/cli_capture.log"
lock = threading.Lock()
CERT = "/tmp/mitm/srv.crt"
KEY = "/tmp/mitm/srv.key"


def log(msg):
    with lock:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(str(msg) + "\n")


def recv_all_until_headers(sock):
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(8192)
        if not chunk:
            return data
        data += chunk
    return data


def handle(client):
    try:
        data = recv_all_until_headers(client)
        head = data.split(b"\r\n\r\n")[0].decode("utf-8", "replace")
        first = head.split("\r\n")[0]
        log(f"\n{'='*74}\n[{datetime.datetime.now().strftime('%H:%M:%S')}] {first}")

        if not first.startswith("CONNECT"):
            client.sendall(b"HTTP/1.1 400 Bad\r\n\r\n")
            return

        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")

        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(CERT, KEY)
        tls_client = ctx.wrap_socket(client, server_side=True)

        # 读取解密后的 HTTP 请求
        req = recv_all_until_headers(tls_client)
        if not req:
            tls_client.close()
            return

        header_blob, _, rest = req.partition(b"\r\n\r\n")
        lines = header_blob.decode("utf-8", "replace").split("\r\n")
        request_line = lines[0]
        headers = {}
        for ln in lines[1:]:
            if ":" in ln:
                k, v = ln.split(":", 1)
                headers[k.strip()] = v.strip()

        # 读 body
        clen = int(headers.get("content-length", "0") or 0)
        body = rest
        while len(body) < clen:
            chunk = tls_client.recv(65536)
            if not chunk:
                break
            body += chunk

        log(f"REQUEST LINE: {request_line}")
        log("--- HEADERS ---")
        for k, v in headers.items():
            show = v
            if k.lower() in ("authorization", "x-api-key"):
                show = v[:20] + "...<redacted>"
            log(f"  {k}: {show}")
        log("--- BODY ---")
        try:
            j = json.loads(body.decode("utf-8", "replace"))
            # 只记录结构，不记录 messages 全文
            slim = {k: (f"<{len(v)} items>" if k == "messages" else v)
                    for k, v in j.items() if k != "messages"}
            slim["_message_roles"] = [m.get("role") for m in j.get("messages", [])]
            log(json.dumps(slim, ensure_ascii=False, indent=2)[:2500])
        except Exception:
            log(body[:1200].decode("utf-8", "replace"))

        # 转发到真实服务器
        up_raw = socket.create_connection(("www.codebuddy.ai", 443), timeout=30)
        up_ctx = ssl.create_default_context()
        up = up_ctx.wrap_socket(up_raw, server_hostname="www.codebuddy.ai")
        up.sendall(req if len(body) >= clen else req + body[len(rest):])

        def pump(src, dst):
            try:
                while True:
                    b = src.recv(65536)
                    if not b:
                        break
                    dst.sendall(b)
            except Exception:
                pass

        t1 = threading.Thread(target=pump, args=(tls_client, up), daemon=True)
        t2 = threading.Thread(target=pump, args=(up, tls_client), daemon=True)
        t1.start(); t2.start(); t1.join(); t2.join()
        up.close()
    except Exception as e:
        log(f"[ERR] {type(e).__name__}: {e}")
    finally:
        try:
            client.close()
        except Exception:
            pass


def main():
    open(LOG, "w").close()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 8899))
    srv.listen(50)
    print("MITM proxy on 127.0.0.1:8899", flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
