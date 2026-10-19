#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TLS 指纹对照实验：同一请求体/头，仅换 TLS 栈，观察是否仍返回 11128。

四组：
  1. Python urllib   (OpenSSL 默认指纹)
  2. curl_cffi chrome131 (模拟 Chrome 桌面)
  3. curl_cffi safari180_ios (模拟 iOS Safari)
  4. Node.js fetch   (与官方 CLI 同为 Node undici 栈)
"""
import json
import uuid
import urllib.request
import urllib.error

ACC = "/attachments/1792408548547_workbuddy-jilintang17_gmail.com.json"
URL = "https://www.codebuddy.ai/v2/chat/completions"

acc = json.load(open(ACC, encoding="utf-8"))
TOKEN = acc["auth"]["accessToken"]
UID = acc["account"]["uid"]
DOMAIN = acc["auth"]["domain"]


def build_headers(ua):
    conv = str(uuid.uuid4())
    mid = uuid.uuid4().hex
    return {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "Authorization": "Bearer " + TOKEN,
        "X-User-Id": UID,
        "X-Domain": DOMAIN,
        "User-Agent": ua,
        "X-Product": "CodeBuddy",
        "X-Product-Version": "2.150.0",
        "X-IDE-Type": "CLI",
        "X-IDE-Name": "CLI",
        "X-IDE-Version": "2.150.0",
        "X-Conversation-ID": conv,
        "X-Conversation-Message-ID": mid,
        "X-Request-ID": mid,
        "X-Agent-Intent": "craft",
        "X-Agent-Type": "main",
        "X-CodeBuddy-Request": "1",
    }


BODY = {
    "model": "deepseek-v4.1-flash",
    "messages": [{"role": "user", "content": "你好"}],
    "max_tokens": 64,
    "stream": True,
    "stream_options": {"include_usage": True},
}

CLI_UA = "CLI/2.150.0 CodeBuddy/2.150.0"


def summarize(label, status, text):
    code = None
    try:
        d = json.loads(text)
        code = d.get("code")
        msg = d.get("msg", "")
    except Exception:
        msg = text[:80].replace("\n", " ")
    verdict = "PASS(未拦截)" if status == 200 else f"FAIL code={code} {msg}"
    print(f"[{label}] HTTP {status} -> {verdict}")


# 1) urllib
try:
    req = urllib.request.Request(
        URL, data=json.dumps(BODY).encode(), headers=build_headers(CLI_UA), method="POST"
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        summarize("1.urllib", r.status, r.read(500).decode("utf-8", "replace"))
except urllib.error.HTTPError as e:
    summarize("1.urllib", e.code, e.read().decode("utf-8", "replace"))
except Exception as e:
    print(f"[1.urllib] EXC {type(e).__name__}: {e}")

# 2) chrome131
try:
    from curl_cffi import requests as creq
    r = creq.post(
        URL, json=BODY, headers=build_headers(CLI_UA),
        impersonate="chrome131", timeout=60,
    )
    summarize("2.chrome131", r.status_code, r.text)
except Exception as e:
    print(f"[2.chrome131] EXC {type(e).__name__}: {e}")

# 3) safari180_ios
try:
    from curl_cffi import requests as creq
    r = creq.post(
        URL, json=BODY, headers=build_headers(CLI_UA),
        impersonate="safari180_ios", timeout=60,
    )
    summarize("3.safari180_ios", r.status_code, r.text)
except Exception as e:
    print(f"[3.safari180_ios] EXC {type(e).__name__}: {e}")

# 4) 把请求要素导出，供 Node 复用
with open("/tmp/probe_payload.json", "w", encoding="utf-8") as fh:
    json.dump({"url": URL, "headers": build_headers(CLI_UA), "body": BODY}, fh, ensure_ascii=False)
print("\n[导出] /tmp/probe_payload.json 供 Node 测试使用")
