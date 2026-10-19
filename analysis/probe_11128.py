#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""流式复现 11128，并做变量对照。"""
import json
import uuid
import urllib.request
import urllib.error

ACC_PATH = "/attachments/1792408548547_workbuddy-jilintang17_gmail.com.json"
URL = "https://www.codebuddy.ai/v2/chat/completions"

acc = json.load(open(ACC_PATH, encoding="utf-8"))
TOKEN = acc["auth"]["accessToken"]
UID = acc["account"]["uid"]
DOMAIN = acc["auth"].get("domain") or "www.codebuddy.ai"


def headers(with_empty=True):
    conv = str(uuid.uuid4())
    mid = uuid.uuid4().hex
    h = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "Authorization": "Bearer " + TOKEN,
        "X-User-Id": UID,
        "X-Domain": DOMAIN,
        "User-Agent": "CLI/2.150.0 CodeBuddy/2.150.0",
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
        "X-Stainless-Lang": "js",
        "X-Stainless-Package-Version": "6.25.0",
        "X-Stainless-OS": "Linux",
        "X-Stainless-Arch": "arm64",
        "X-Stainless-Runtime": "node",
        "X-Stainless-Runtime-Version": "v22.11.0",
        "X-Stainless-Retry-Count": "0",
    }
    if with_empty:
        h["X-Enterprise-Id"] = ""
        h["X-Tenant-Id"] = ""
        h["X-Conversation-Request-ID"] = ""
    return h


def send(label, model, text, sys_prompt="You are a helpful assistant. Respond in the user's language, follow the user's instructions, and be direct and concise.", with_empty=True):
    print(f"\n[{label}] model={model} with_empty_headers={with_empty}")
    print(f"  user: {text[:60]}")
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": sys_prompt},
            {"role": "user", "content": text},
        ],
        "temperature": 0.7,
        "top_p": 1,
        "max_tokens": 512,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=headers(with_empty), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            raw = r.read().decode("utf-8", "replace")
            print(f"  -> HTTP {r.status}  len={len(raw)}")
            print(f"  -> 前200字符: {raw[:200]}")
            return r.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        code = None
        try:
            code = json.loads(raw).get("code")
        except Exception:
            pass
        print(f"  -> HTTP {e.code}  code={code}")
        print(f"  -> {raw[:300]}")
        return e.code, raw
    except Exception as e:
        print(f"  -> EXC {type(e).__name__}: {e}")
        return None, str(e)


if __name__ == "__main__":
    send("A 原样复现", "deepseek-v4.1-flash", "请阅读当前工作区工程结构并分析关键逻辑与优化点")
    send("B 简单内容", "deepseek-v4.1-flash", "你好")
    send("C 换模型 default-model", "default-model", "你好")
    send("D 去掉空值头", "deepseek-v4.1-flash", "你好", with_empty=False)
