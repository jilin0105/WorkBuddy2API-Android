#!/usr/bin/env python3
"""用服务端权威清单重建国际版内置模型目录。

权威来源：GET https://www.codebuddy.ai/console/enterprises/personal/models
（2026-10-19 实测 HTTP 200 返回，已与网页下拉框 16 项逐条交叉验证）

本脚本不联网：以那次 200 响应的 id/name/token 上限为权威，
其余展示与能力位字段从现有目录按 id 复用。缺少的 id 按规则补齐。
"""
import json
import shutil
from pathlib import Path

ROOT = Path("/workspace/WorkBuddy2API-Android")
SRC = ROOT / "app/src/main/assets/codebuddy-international-models.json"
BAK = ROOT / "analysis/backup-models-before-rebuild.json"

# ---- 权威数据：服务端 200 响应的 20 个唯一 id ----
# (id, name, maxInputTokens, maxOutputTokens)
AUTHORITATIVE = [
    ("default-model",       "Auto",                272000,  32000),
    ("fast-model",          "Fast",                200000,  48000),
    ("balanced-model",      "Balanced",            512000,  48000),
    ("primary-model",       "Primary",            2000000, 256000),
    ("deep-model",          "Deep",                640000,  64000),
    ("hy4-preview",         "Hy4-preview",         192000,  32000),
    ("hy3",                 "HY3",                 196000,  32000),
    ("gpt-5.6-sol",         "GPT-5.6-Sol",        1000000, 128000),
    ("gpt-5.6-terra",       "GPT-5.6-Terra",      1000000, 128000),
    ("gpt-5.6-luna",        "GPT-5.6-Luna",       1000000, 128000),
    ("gpt-5.5",             "GPT-5.5",            1000000, 128000),
    ("gpt-5.4",             "GPT-5.4",            1000000, 128000),
    ("gpt-5.3-codex",       "GPT-5.3-Codex",      1000000, 128000),
    ("gemini-3.5-flash",    "Gemini-3.5-Flash",   1000000,  65536),
    ("glm-5.3",             "GLM-5.3",             200000, 128000),
    ("glm-5.2",             "GLM-5.2",             200000, 128000),
    ("kimi-k3",             "Kimi-K3",             256000,  32000),
    ("kimi-k2.6",           "Kimi-K2.6",           256000,  32000),
    ("deepseek-v4.1-flash", "DeepSeek-V4.1-Flash", 1000000, 128000),
    ("default-model-lite",  "Default-Lite",        100000,  16000),
]

src = json.loads(SRC.read_text(encoding="utf-8"))
old_by_id = {m["id"]: m for m in src["models"]}

shutil.copyfile(SRC, BAK)

required = ["id", "name", "vendor", "maxInputTokens", "maxOutputTokens",
            "supportsToolCall", "supportsImages", "maxAllowedSize"]
new_models = []
filled, reused = [], []

for mid, name, mtin, mtout in AUTHORITATIVE:
    base = dict(old_by_id.get(mid, {}))
    if base:
        reused.append(mid)
    else:
        filled.append(mid)

    row = {
        "id": mid,
        "name": name,
        "vendor": base.get("vendor", "e"),
        "maxInputTokens": mtin,
        "maxOutputTokens": mtout,
        "supportsToolCall": base.get("supportsToolCall", True),
        "supportsImages": base.get("supportsImages", True),
        "maxAllowedSize": base.get("maxAllowedSize", mtin),
    }
    # 保留展示字段
    for k in ("credits", "descriptionEn", "descriptionZh", "tags"):
        if k in base:
            row[k] = base[k]
    new_models.append(row)

# 校验
ids = [m["id"] for m in new_models]
assert len(ids) == len(set(ids)), "id 重复"
for m in new_models:
    for f in required:
        assert f in m, f"缺少 {f} @ {m['id']}"

# 保留原有顶层结构，只替换 models
removed = sorted(set(old_by_id) - set(ids))
src["models"] = new_models
# cli_recommended 过滤掉已不存在的
rec = src.get("cli_recommended", [])
src["cli_recommended"] = [r for r in rec if r in set(ids)]

SRC.write_text(json.dumps(src, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

print(f"重建完成: {len(old_by_id)} -> {len(new_models)} 条")
print(f"复用已有字段: {len(reused)} 条")
print(f"新增补齐: {filled}")
print(f"删除: {len(removed)} 条 -> {removed}")
print(f"cli_recommended: {len(rec)} -> {len(src['cli_recommended'])}")
print(f"备份: {BAK}")
