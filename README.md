# WorkBuddy2API · Android 原生版

[![Build APK](https://github.com/jilin0105/WorkBuddy2API-Android/actions/workflows/build.yml/badge.svg)](https://github.com/jilin0105/WorkBuddy2API-Android/actions/workflows/build.yml)
[![Latest build](https://img.shields.io/github/v/release/jilin0105/WorkBuddy2API-Android?label=latest%20APK&color=blue)](https://github.com/jilin0105/WorkBuddy2API-Android/releases/tag/latest-build)

> 把腾讯 **WorkBuddy / CodeBuddy** 的积分额度，变成手机本地一个标准的 **OpenAI / Anthropic 兼容 API**。
> 纯原生 Android 实现（Kotlin），**无需 Python、无需 Docker、无需电脑**——装到手机上就能跑。

---

## 直接下载

每次推送到 `main` 都会自动构建 APK，滚动更新到同一个 Release：

**👉 [下载最新构建 APK](https://github.com/jilin0105/WorkBuddy2API-Android/releases/tag/latest-build)**

- APK 使用 **debug 签名**（本仓库为本地自用工具，不发布应用商店）
- 安装前请先卸载设备上的旧版本，或确认签名一致
- 也可在 [Actions](https://github.com/jilin0105/WorkBuddy2API-Android/actions) 页面下载任意一次构建的产物（保留 14 天）

---

## 这是什么

一个跑在 Android 手机上的本地 API 网关。启动后，你的电脑、平板或其它 App 可以通过局域网连到手机上的 API 端口，
用标准 OpenAI / Anthropic 协议直接调用 WorkBuddy 的模型额度。

```
你的电脑 / Claude Code / Cherry Studio / Codex CLI
        │  http://<手机IP>:8788/v1/...
        ▼
┌─────────────────────────────────┐
│   手机 (WorkBuddy2API Native)    │
│  原生 HTTP 服务 (ServerSocket)   │
│  ├─ 协议适配 → 统一转为上游格式    │
│  ├─ 多账号轮换 / 冷却 / 限速      │
│  └─ SQLite 记录用量               │
└─────────────────────────────────┘
        │  https://copilot.tencent.com
        ▼
     WorkBuddy 上游
```

---

## 特性

- 🔌 **三协议兼容** — OpenAI Chat (`/v1/chat/completions`)、Anthropic Messages (`/v1/messages`)、OpenAI Responses (`/v1/responses`)，均为**流式 + 非流式**
- 📱 **纯原生，零依赖** — 不打包 Python 运行时，APK 仅约 1.7 MB；所有逻辑用 Kotlin 原生实现
- 🔄 **多账号轮换** — 加权随机选号（余额 / 到期时间 / 成功率 / 闲置时长 多因子）、失败冷却、自动重试
- ⏰ **自动签到** — 每日定时签到领取额度，可配置签到时刻
- 🧠 **思维链适配** — 自动处理 `reasoning_content` 多轮回填与 `developer` 角色归一
- 📊 **用量记录** — 每次请求的输入 / 输出 / 思考链入库（SQLite），支持筛选与统计
- 🔑 **应用 Key 管理** — 创建 / 启停 / 删除 API Key，加密存储（AES-GCM）
- 🛡 **保活加固** — 前台服务 + WakeLock + 开机自启 + 精确闹钟兜底重启，应对国产 ROM 的后台限制
- 🌏 **国内外双版本** — 同时支持国内版与国际版账号

---

## 快速开始

### 1. 编译

```bash
# 需要 JDK 17+ 与 Android SDK (compileSdk 36)
echo "sdk.dir=/你的/Android/SDK路径" > local.properties
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 2. 安装并启动

安装后在应用内点击「启动服务」。

### 3. 添加账号

在「账号」页通过**扫码登录**或**上传 auth 文件**添加 WorkBuddy / CodeBuddy 账号。

### 4. 创建应用 Key

在「应用」页创建一个 API Key（形如 `sk-...`），用于访问 `/v1/*` 接口。

### 5. 开始调用

```bash
# 假设手机 IP 是 192.168.1.100
curl http://192.168.1.100:8788/v1/chat/completions \
  -H "Authorization: Bearer sk-你的Key" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "glm-5.3",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": true
  }'
```

在 Claude Code / Cherry Studio 等客户端里，把 Base URL 填成 `http://<手机IP>:8788/v1` 即可。

---

## 配置项

在应用「设置」页可调整：

| 配置 | 默认 | 说明 |
|---|---|---|
| 端口 | `8788` | 本地 API 监听端口 |
| 使用记录保留天数 | `30` | 超期记录每日自动清理，控制数据体积 |
| 额度刷新间隔 | `30` 分钟 | 账号余额刷新频率 |
| 模型缓存时长 | `60` 分钟 | 模型列表缓存 TTL |
| 每日签到时刻 | `9,21` | 自动签到时间点 |
| 保活开关 | 开 | 前台服务保活 |

---

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | 原生 View（无 Compose / 无 WebView 界面） |
| HTTP 服务 | `java.net.ServerSocket` 手写实现 |
| HTTP 客户端 | OkHttp 4.12 |
| 存储 | SQLite（`SQLiteOpenHelper`，WAL 模式） |
| 加密 | AES-GCM（AndroidKeyStore） |
| 构建 | AGP 8.13.2 / Kotlin 2.2.20 / JDK 17 |
| 最低版本 | Android 7.0（API 24） |

> **关于 WebView**：仅在账号登录（OAuth）时使用，配置为 `LOAD_NO_CACHE` 且退出时清理缓存。

---

## 数据存储说明

应用数据全部存放在私有目录，不会上传到任何第三方服务器：

| 目录 | 内容 |
|---|---|
| `databases/` | SQLite 数据库（账号、应用 Key、用量记录） |
| `app_webview/` | 登录页 WebView 缓存（自动清理） |
| `shared_prefs/` | 配置项 |

「设置 → 数据管理」可查看各目录**实测占用**，并清理数据库记录与 WebView 缓存。

---

## 保活说明

国产 ROM（ColorOS / MIUI / EMUI 等）对后台进程管控严格，本应用做了以下加固：

- **前台服务** + 常驻通知，降低被冻结概率
- **WakeLock** 定期续期，防止进程休眠
- **开机自启**（`BOOT_COMPLETED` 广播）
- **精确闹钟兜底**：从最近任务划掉后，通过闹钟回调重启服务（绕过 Android 12+ 后台启动前台服务的限制）
- **看门狗**：维护循环自检，冻结恢复后自动补上锁
- **悬浮球保活（可选）**：屏幕上常驻一个圆形悬浮球，把进程提升到 `VISIBLE` 优先级

### 悬浮球交互

| 操作 | 行为 |
|---|---|
| 按住拖动 | 可拖到屏幕任意位置，边界自动夹紧（不让球拖出视野丢失） |
| 松手 | 自动吸附到最近的屏幕边缘 |
| 3 秒不碰 | 贴边半藏，只露半个球在边缘（半透明，不碍事） |
| 点击球体 | 立即展开，重置倒计时 |
| 状态指示 | 绿点=运行中，红点=已停止 |

收起时刻意保留**一半**可见：露出太少会让人以为球丢了、反而要费力去找；
露一半则一眼看出是"躲在边上"，既不挡内容，又随点随到。

收起时**只偏移绘制内容、窗口本身仍在屏内** —— 这样"看起来藏起来了"，
但触摸热区仍是完整的球体（若把窗口整体移出屏幕，露出的部分就是全部触摸区，会很难点中）。

### 关于悬浮球保活的作用边界

这个功能**不是保活银弹**，需要如实理解它的能力范围：

| 它能做到的 | 它做不到的 |
|---|---|
| 提高 `oom_adj` 优先级，**内存不足（LMK）回收**时排在后台服务之后 | ❌ 挡不住系统「一键清理」「强力清理」——那类操作走**白名单机制**，不在白名单里窗口再多也照杀 |
| 部分 ROM 判定「速冻」时会参考"是否有可见窗口"，可降低被速冻概率 | ❌ 无法绕过电池优化、自启动等厂商管控 |

**结论**：悬浮球是"以观感换存活率"的辅助手段（屏幕上会多一个球），默认关闭。
真正的主力仍是：**电池优化白名单 + 自启动 + 后台运行 + 回收后自动重启**。

> 建议同时在系统设置中把本应用加入**电池优化白名单**并允许**自启动**。

---

## 免责声明

> **本项目仅供学习与技术研究使用，请勿用于任何商业用途。**

- 本项目**仅用于学习** API 网关原理、协议适配、Android 原生开发等技术。
- **禁止**用于商业用途、生产环境、批量调用、代理转售，或任何违反 WorkBuddy / CodeBuddy 服务条款的行为。
- 使用者须自行遵守上游服务条款，**自行承担全部使用风险**。
- 作者不对任何因使用本项目产生的直接或间接损失负责。
- 若你所在地区或平台规定不允许此类工具，**请勿使用**。

---

## License

[MIT](LICENSE)

---

## 致谢

本项目参考了以下开源项目的思路（Python 版后端）：

- [Buddy2api](https://github.com/wicm84266964/Buddy2api)
- [codebuddy2api](https://github.com/ShouZhuo0413/codebuddy2api)
- [workbuddy2api](https://github.com/hawklithm/workbuddy2api)

及更多（详见上游 `README.md`）。
