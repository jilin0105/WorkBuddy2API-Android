#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用完整渠道校验头，逐个测试模型可用性，定位 11102 是单模型还是全局问题。"""
import json
import secrets
import urllib.error
import urllib.request
import uuid

ACC = "/attachments/1792408548547_workbuddy-jilintang17_gmail.com.json"
acc = json.load(open(ACC, encoding="utf-8"))
TOKEN = acc["auth"]["accessToken"]
UID = acc["account"]["uid"]
DOMAIN = acc["auth"]["domain"]


def hexr(n):
    return secrets.token_hex((n + 1) // 2)[:n]


def build_headers():
    conv = str(uuid.uuid4())
    mid = uuid.uuid4().hex
    creq = uuid.uuid4().hex
    trace, span, parent = hexr(32), hexr(16), hexr(16)
    return {
        "Content-Type": "application/json", "Accept": "application/json",
        "Authorization": "Bearer " + TOKEN, "X-User-Id": UID, "X-Domain": DOMAIN,
        "User-Agent": "CLI/2.169.0 CodeBuddy/2.169.0",
        "X-Product": "SaaS", "X-Product-Version": "2.169.0",
        "X-IDE-Type": "CLI", "X-IDE-Name": "CLI", "X-IDE-Version": "2.169.0",
        "X-Stainless-Lang": "js", "X-Stainless-Package-Version": "6.25.0",
        "X-Stainless-OS": "Linux", "X-Stainless-Arch": "arm64",
        "X-Stainless-Runtime": "node", "X-Stainless-Runtime-Version": "v22.11.0",
        "X-Stainless-Retry-Count": "0",
        "X-Conversation-ID": conv, "X-Conversation-Request-ID": creq,
        "X-Conversation-Message-ID": mid, "X-Request-ID": mid,
        "X-Agent-Intent": "craft", "X-Agent-Type": "main",
        "x-requested-with": "XMLHttpRequest",
        "X-Agent-Purpose": "conversation", "X-Agent-Runtime": "other",
        "X-Private-Data": "false", "x-codebuddy-request": "1",
        "X-Root-Request-ID": mid,
        "traceparent": f"00-{trace}-{span}-01",
        "b3": f"{trace}-{span}-1-{parent}",
        "X-B3-TraceId": trace, "X-B3-ParentSpanId": parent,
        "X-B3-SpanId": span, "X-B3-Sampled": "1",
        "X-Trace-ID": trace,
        "baggage": f"codebuddy.session_id={conv},codebuddy.conversation_request_id={creq}",
    }


def try_model(model):
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "hi"},
        ],
        "max_tokens": 8, "stream": True,
        "stream_options": {"include_usage": True},
    }
    req = urllib.request.Request(
        "https://www.codebuddy.ai/v2/chat/completions",
        data=json.dumps(body).encode(), headers=build_headers(), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=45) as r:
            chunk = r.read(200).decode("utf-8", "replace")
            ok = "chat.completion.chunk" in chunk
            return ("OK" if ok else "??"), 200, chunk[:80].replace("\n", " ")
    except urllib.error.HTTPError as e:
        try:
            d = json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            return "ERR", e.code, "unparseable"
        return str(d.get("code")), e.code, str(d.get("msg"))[:110]
    except Exception as ex:
        return "EXC", "-", f"{type(ex).__name__}: {ex}"


MODELS = [
    "default-model", "fast-model", "balanced-model", "primary-model", "deep-model",
    "deepseek-v4.1-flash", "hy3", "gpt-5.5", "gemini-3.5-flash", "glm-5.3",
]

print(f"{'model':<24} {'code':<10} {'http':<6} detail")
print("-" * 86)
for m in MODELS:
    code, http, detail = try_model(m)
    print(f"{m:<24} {code:<10} {http:<6} {detail}")
