#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""合并 CLI 最新模型清单与项目内置目录，输出更新后的 JSON。"""
import json, os, sys

CLI_PATH = "/tmp/cbcli/package/product.json"
PROJ_PATH = "/workspace/WorkBuddy2API-Android/app/src/main/assets/codebuddy-international-models.json"

cli = json.load(open(CLI_PATH, encoding="utf-8"))
proj = json.load(open(PROJ_PATH, encoding="utf-8"))

cli_models = cli.get("models", [])
proj_models = proj.get("models", [])

print("=== CLI product.json 顶层键 ===")
print(list(cli.keys()))
print("CLI models 条数:", len(cli_models))
print()
print("=== CLI 第一条模型完整字段 ===")
print(json.dumps(cli_models[0], ensure_ascii=False, indent=2))
print()
cli_ids = [m["id"] for m in cli_models]
proj_ids = [m["id"] for m in proj_models]
print("=== CLI 全部 id (%d) ===" % len(cli_ids))
for i in cli_ids:
    print("  ", i)
print()
print("=== 项目全部 id (%d) ===" % len(proj_ids))
for i in proj_ids:
    print("  ", i)
print()
print("=== 差集 ===")
print("CLI 有 / 项目无 (%d):" % len(set(cli_ids) - set(proj_ids)))
for i in sorted(set(cli_ids) - set(proj_ids)):
    print("  +", i)
print("项目有 / CLI 无 (%d):" % len(set(proj_ids) - set(cli_ids)))
for i in sorted(set(proj_ids) - set(cli_ids)):
    print("  -", i)
