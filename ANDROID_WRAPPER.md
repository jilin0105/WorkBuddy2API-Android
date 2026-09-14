# Android 版架构说明

> 本文档说明 Android 原生版的技术实现与构建方式。
> 上手使用请看 [README-Android.md](README-Android.md)。

---

## 架构演进

本工程经历过一次**架构重写**，早期版本与当前版本完全不同：

| | 早期版本（已废弃） | 当前版本 |
|---|---|---|
| 形态 | WebView 壳，连接远程后端 | **完全原生，独立运行** |
| 依赖 | 需先部署 Python 后端 | **无外部依赖** |
| 界面 | 网页套壳 | 原生 View |
| 数据 | 存远程 | 本地 SQLite |
| 端口 | 8787（远程） | 8788（本机） |

> 如果你看到旧文档提到「APK 只承担管理 UI」「需要先在 Docker 启动服务」，那是**已废弃**的描述。

---

## 组件构成

```
app/src/main/java/com/joy4fire/workbuddy2api/
├── MainActivity.kt        界面与交互（原生 View，非 Compose）
├── ApiHostService.kt      前台服务：HTTP 服务、保活、调度
├── NativeCore.kt          核心：上游通信、账号管理、协议适配入口
├── NativeStore.kt         SQLite 存储层（账号/应用Key/用量记录）
├── ProtocolAdapters.kt    三协议转换（OpenAI/Anthropic/Responses）
├── OAuthWebActivity.kt    登录窗口（唯一使用 WebView 处）
├── AccountSync.kt         账号信息同步
├── RegionConfig.kt        国内版/国际版端点配置
└── BootReceiver.kt        开机自启广播接收器
```

### 运行时结构

- **`ApiHostService`** — 前台服务，进程存活的核心
  - `ServerSocket(8788, 32, 0.0.0.0)` 监听局域网请求
  - 手写 HTTP/1.1 解析与 SSE 流式输出（未用 Web 框架）
  - `ScheduledExecutorService` 维护循环（60s）：签到 / 额度 / 模型 / 清理
  - WakeLock 续期 + 闹钟兜底重启

- **`NativeStore`** — `SQLiteOpenHelper`，WAL 模式
  - 表：`accounts` / `apps` / `usage_logs` / `settings` / `model_cache`
  - 全局 `synchronized(lock)` 保证并发安全

- **`ProtocolAdapters`** — 三协议 → 统一 OpenAI Chat 格式 → 上游

---

## 构建

```bash
# 前置：JDK 17+，Android SDK (compileSdk 36)
echo "sdk.dir=/你的/Android/SDK路径" > local.properties

./gradlew assembleDebug     # 调试版
./gradlew assembleRelease   # 发布版（需配置签名）
```

若无 `gradlew`，用本机 Gradle：

```bash
gradle assembleDebug
```

### 版本要求

| 组件 | 版本 |
|---|---|
| AGP | 8.13.2 |
| Kotlin | 2.2.20 |
| JDK | 17+ |
| compileSdk / targetSdk | 36 |
| minSdk | 24（Android 7.0） |

### 依赖

仅一个第三方依赖：

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

---

## 保活机制详解

国产 ROM 的后台管控是最大的工程难点。当前实现：

| 机制 | 作用 |
|---|---|
| 前台服务 + 常驻通知 | 提升进程优先级，避免被随手回收 |
| WakeLock（10 分钟超时，循环续期） | 防止 CPU 休眠导致不调度 |
| `BOOT_COMPLETED` 广播 | 开机 / OTA 后自动拉起 |
| 精确闹钟（`setExactAndAllowWhileIdle`） | 划掉任务后兜底重启（绕过 Android 12+ 后台启动前台服务限制） |
| 看门狗（15 分钟自检） | 冻结恢复后自动补回锁与状态 |

> 通知重要性用 `IMPORTANCE_DEFAULT` 而非 `LOW`：部分 ROM 会把低重要性通知视作可降级，进而弱化对应服务优先级。

---

## 数据与隐私

- 所有数据存于应用私有目录，**不上传任何第三方服务器**
- 应用 Key 用 AES-GCM 加密（密钥存 AndroidKeyStore）
- 账号 auth 文件仅本地读取，用于向上游换取 token
- 用量记录默认保留 30 天，超期自动清理

---

## 常见问题

**Q：为什么不用 WebView 做界面？**
早期版本是 WebView 套壳，需要先跑 Python 后端。改为原生后无需任何外部依赖，且体积从数十 MB 降到 1.7 MB。

**Q：为什么 WebView 还剩在代码里？**
仅用于账号 OAuth 登录页。已配置 `LOAD_NO_CACHE` 并在退出时清理缓存，避免磁盘堆积。

**Q：编译报 `sdk.dir` 错误？**
需手动创建 `local.properties` 并填入 SDK 路径（该文件含本机路径，不入版本控制）。

**Q：服务启动后连不上？**
确认手机与电脑在同一局域网，且应用的「电池优化白名单」与「自启动」已开启。
