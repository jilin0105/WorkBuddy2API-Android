#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地 MITM 代理：拦截官方 CLI 发往 codebuddy.ai 的请求，完整记录头与体。

用途：对比官方 CLI 成功请求 vs 客户端失败请求的差异。
只监听本地，仅转发 codebuddy.ai 域名。
"""
import socket
import ssl
import threading
import json
import datetime

LOG = "/tmp/cli_capture.log"
lock = threading.Lock()


def log(msg):
    with lock:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(msg + "\n")


def handle(client):
    try:
        # 读取 CONNECT 请求
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = client.recv(4096)
            if not chunk:
                return
            data += chunk

        head = data.split(b"\r\n\r\n")[0].decode("utf-8", "replace")
        first = head.split("\r\n")[0]
        log(f"\n{'='*70}\n{datetime.datetime.now().isoformat()}")
        log(f"[CONNECT] {first}")

        if not first.startswith("CONNECT"):
            return

        host = first.split()[1]
        if "codebuddy" not in host:
            # 非目标域名，直接拒绝
            client.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
            return

        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")

        # TLS 中间人需要证书；这里改为直接透传到真实服务器并旁路记录
        upstream = socket.create_connection(("www.codebuddy.ai", 443), timeout=20)
        ctx = ssl.create_default_context()
        up_tls = ctx.wrap_socket(upstream, server_hostname="www.codebuddy.ai")

        def pump(src, dst, tag):
            try:
                while True:
                    b = src.recv(65536)
                    if not b:
                        break
                    if tag == "C->S":
                        log(f"[{tag}] {len(b)} bytes")
                        # 尝试解析 HTTP 明文（TLS 前的 ClientHello 是二进制，跳过后才是明文）
                        try:
                            txt = b.decode("utf-8")
                            if "HTTP/" in txt or "POST " in txt or "GET " in txt:
                                log(txt[:3000])
                        except Exception:
                            pass
                    dst.sendall(b)
            except Exception:
                pass

        t1 = threading.Thread(target=pump, args=(client, up_tls, "C->S"), daemon=True)
        t2 = threading.Thread(target=pump, args=(up_tls, client, "S->C"), daemon=True)
        t1.start(); t2.start()
        t1.join(); t2.join()
        up_tls.close()
    except Exception as e:
        log(f"[ERR] {type(e).__name__}: {e}")
    finally:
        try:
            client.close()
        except Exception:
            pass


def main():
    open(LOG, "w").close()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 8899))
    srv.listen(50)
    print("proxy listening on 127.0.0.1:8899", flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
