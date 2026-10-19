#!/usr/bin/env python3
"""把 CLI 2.169.0 的模型变更合并进项目内置目录（方案一，保守）。

规则：
  1. 删除 12 个已下线的纯文本模型（保留 default-model-lite，它是被合并而非下线）
  2. 保留全部 6 个图像/视频模型（CLI product.json 不列图像模型，不代表服务端下架）
  3. 追加 5 个新模型（技术字段取自 CLI product.json）
  4. 其余原有条目原样保留，不做任何字段改动

校验规则（按模型类型分别校验，因为两类字段集天然不同）：
  - 文本模型：必须含 id / name / vendor / maxInputTokens / maxOutputTokens
  - 媒体模型：必须含 id / name（无 token 概念，按 credits 计费）
  - 全局：id 唯一、非空

注意：本脚本不修补既有条目的字段缺陷（如部分 Gemini 缺 vendor），
      保持最小改动，避免引入未经确认的字段值。
"""
import json
import sys

SRC = "app/src/main/assets/codebuddy-international-models.json"
CLI = "/tmp/cbcli/package/product.json"

# 明确下线的纯文本模型（12 个）
REMOVED = [
    "gemini-2.5-flash",
    "gemini-2.5-pro",
    "gemini-3.0-flash",
    "gemini-3.1-pro",
    "gemini-3.1-flash-lite",
    "glm-5.0",
    "gpt-5.1-codex",
    "gpt-5.1-codex-mini",
    "gpt-5.3-codex",
    "kimi-k2.5",
    "minimax-m3",
    "deepseek-v3-2-volc",
]

# 新增的 5 个模型
ADDED = [
    "gpt-6-astra",
    "hy4-preview",
    "kimi-k2.8-preview",
    "glm-5.3-flash",
    "deepseek-v4.1-flash-sg",
]

# 必须保留的图像/视频模型，用于校验没被误删
KEEP_MEDIA = [
    "gemini-2.5-flash-image",
    "gemini-3.0-pro-image",
    "gemini-3.1-flash-image",
    "hunyuan-image-v2.0-general-edit",
    "hunyuan-image-v3.0",
    "hunyuan-video-art",
]

# 文本模型必需字段
TEXT_REQUIRED = {"id", "name", "vendor", "maxInputTokens", "maxOutputTokens"}
# 媒体模型必需字段
MEDIA_REQUIRED = {"id", "name"}
# 媒体模型识别标记：含 tags 且不含 token 字段
MEDIA_MARKER = "tags"


def is_media(m: dict) -> bool:
    """媒体模型：有 tags 且没有 maxInputTokens（图像/视频不按 token 计费）。"""
    return MEDIA_MARKER in m and "maxInputTokens" not in m


def validate(models: list) -> list:
    """按类型分别校验，返回错误列表。"""
    errors = []
    ids = []
    for m in models:
        mid = m.get("id")
        if not mid:
            errors.append("存在 id 为空或缺失的条目")
            continue
        ids.append(mid)

        if is_media(m):
            miss = MEDIA_REQUIRED - set(m.keys())
            if miss:
                errors.append(f"[媒体] {mid} 缺少字段 {sorted(miss)}")
            # 媒体模型不应出现 token 字段（防止误把文本字段塞进图像模型）
            extra_token = {"maxInputTokens", "maxOutputTokens"} & set(m.keys())
            if extra_token:
                errors.append(f"[媒体] {mid} 意外含有 token 字段 {sorted(extra_token)}")
        else:
            miss = TEXT_REQUIRED - set(m.keys())
            if miss:
                errors.append(f"[文本] {mid} 缺少字段 {sorted(miss)}")

    dup = sorted({i for i in ids if ids.count(i) > 1})
    if dup:
        errors.append(f"id 重复：{dup}")
    return errors


def main() -> int:
    doc = json.load(open(SRC, encoding="utf-8"))
    models = doc["models"]
    before = {m["id"]: m for m in models}
    print(f"原始模型数: {len(models)}")

    cli_doc = json.load(open(CLI, encoding="utf-8"))
    cli = {m["id"]: m for m in cli_doc["models"]}

    # --- 校验：要删的确实存在 ---
    missing_remove = [i for i in REMOVED if i not in before]
    if missing_remove:
        print(f"错误：以下待删模型不在目录中 {missing_remove}", file=sys.stderr)
        return 1

    # --- 校验：要新增的确实不在目录中，且在 CLI 中存在 ---
    dup_added = [i for i in ADDED if i in before]
    if dup_added:
        print(f"错误：以下待增模型已存在 {dup_added}", file=sys.stderr)
        return 1
    missing_cli = [i for i in ADDED if i not in cli]
    if missing_cli:
        print(f"错误：以下新模型在 CLI 中不存在 {missing_cli}", file=sys.stderr)
        return 1

    # --- 校验：图像模型必须全部健在且不被列入删除名单 ---
    lost = [i for i in KEEP_MEDIA if i not in before]
    if lost:
        print(f"错误：图像/视频模型缺失 {lost}", file=sys.stderr)
        return 1
    overlap = [i for i in KEEP_MEDIA if i in REMOVED]
    if overlap:
        print(f"错误：图像模型被列入删除名单 {overlap}", file=sys.stderr)
        return 1

    # --- 构建新列表，保持原顺序 ---
    out = [m for m in models if m["id"] not in REMOVED]

    # --- 追加新模型 ---
    for mid in ADDED:
        src = dict(cli[mid])
        out.append(src)

    doc["models"] = out
    print(f"新模型数: {len(out)}  (删除 {len(REMOVED)}，新增 {len(ADDED)})")

    # --- 校验新列表结构 ---
    errors = validate(out)
    if errors:
        print("错误：结构校验失败，未写入文件", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    with open(SRC, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")

    # --- 结果报告 ---
    print("\n=== 结果 ===")
    print("新增:", ", ".join(ADDED))
    print("删除:", ", ".join(REMOVED))
    kept = {m["id"] for m in out}
    print("保留图像/视频:", ", ".join(i for i in KEEP_MEDIA if i in kept))

    texts = [m for m in out if not is_media(m)]
    medias = [m for m in out if is_media(m)]
    print(f"\n文本模型 {len(texts)} 条 / 媒体模型 {len(medias)} 条")

    print("\n=== 新模型字段 ===")
    for mid in ADDED:
        m = next(x for x in out if x["id"] == mid)
        print(f"{mid}: in={m.get('maxInputTokens')} out={m.get('maxOutputTokens')} "
              f"tool={m.get('supportsToolCall')} img={m.get('supportsImages')} vendor={m.get('vendor')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
