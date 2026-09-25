# WorkBuddy 1.2.7 功能移植 —— 进度

> 目标：把 WorkBuddy 1.2.7 的**领任务 / 做任务 / 提示词注入**移植进 `WorkBuddy2API-Android`，
> UI 统一为单一 **Miuix** 界面。

## 一、已完成

### 1. 逆向 APK 1.2.7（产物 `.taixu-tmp/wb127/sources/`）
- 附件与项目**同源**（包名 `com.joy4fire.workbuddy2api`），1.2.7 多出整套成长中心
- 提示词注入：`a/I2.java`、`a/B2.java`、`a/C0045h2.java:Y()/b0()`
- 任务链：`a/n2.java`（`A()` 解析任务、`v()/w()` 插件域、`L()` 开学季 `@/portal/activity/school`）

### 2. 移植的功能代码
`PromptInjection.kt` / `PromptRules.kt` / `GrowthApi.kt` / `GrowthEvents.kt` / `GrowthTasks.kt` /
`GrowthRunner.kt` / `GrowthCenter.kt` / `GrowthFacade.kt` / `FormatKit.kt` / `KeepAliveGuide.kt`

注入点：`ApiHostService.proxy()` 开头调 `PromptInjection.apply(...)`；上游 400/403/422 命中拦截词 → 降级提示词至当天午夜。
**清洗已真机实测有效**：模板句去品牌、裸 `11-128` → `one-to-many`。

### 3. 单一 Miuix 界面
入口 `MiuixHostActivity` + `ui/AppShell.kt`；7 个一级 Tab（概览/账号/模型/记录/应用/设置/成长）；
5 个二级页（用量/出网取证/存储/保活/提示词）。`MainActivity` 已从 Manifest 移除。

### 4. UI 基建（`ui/PcComponents.kt`）
- `PcCard`：统一卡片 + 标题间隙（`PcTokens.TitleGap`）+ 行内边距
- `PcInfoRow` / `PcMonoRow`：**标签固定 96dp 列宽**，值占剩余宽度（修掉"UID 竖排"）
- `PcActionGrid` + `PcAction`：**统一按钮组**——每行最多 2 个、等宽、固定间距、奇数自动折行（修掉"功能键融一块"）

## 二、已修复并真机验证的缺陷

| 缺陷 | 根因 | 修复 | 验证 |
|---|---|---|---|
| 启动即崩 | targetSdk 36 下 `registerReceiver` 未声明导出性 | `RECEIVER_NOT_EXPORTED`（SDK≥33 分支） | ✓ 正常启动 |
| 全面屏返回直接退出 | Compose 不消费返回事件，直达 finish | `AppShell` 三级 `BackHandler` | ✓ 三级行为正确 |
| 打开任意对话框崩溃 | Miuix 弹层注册 NavigationBackHandler，Compose Dialog 是独立 window 取不到宿主 owner | `ui/NavCompat.kt` 自建根 `NavigationEventDispatcher` 注入 | ✓ 弹层可用 |
| 做任务崩溃 | `items(tasks, key={it.code})` 的 code 空/重复 → `Key "" was already used` | 改 `items(count, key={tasks[it].id})`，id=`"$index:$code"` | ✓ 不崩 |
| 成长任务显示"未知/+0分" | 字段名臆测错误。**真实响应**：`{task_id,code,title,description,level_name,status,url}` | 兼容 `code`/`task_code`、`status`/`accept_status`；无 reward 时显示"奖励以官方客户端为准" | ✓ 正确显示 |
| **反复启停后按钮全灰、停不掉服务** | `updateServiceState` 收了广播但**没重置 `startingUp`**，`enabled=!startingUp` 永久 false | 广播回调里同时复位 `startingUp`；启动中允许「取消启动」 | ✓ **启停循环实测通过**（见下） |
| 启动服务"半天没反应" | 直接读静态 `ApiHostService.running`，不触发重组 | 移入 `AppState.serviceRunning`（mutableStateOf），广播驱动；新增 `startingUp` 中间态 + 20s 超时 | ✓ 运行中/健康 正常显示 |
| 国内/国际模型混在一起 | 忽略模型的 `regions[]` | 模型页按「双版本/国内专属/国际专属」三段分组 + 筛选 Tab | ✓ 分组正确 |
| 积分不显示 | `BreakdownCard` 把 credits 当 0 过滤掉 | 始终显示（含 0） | ✓ 概览显示 7576.0 积分 |
| 记录看不出谁在扣分 | 数据库已存 `account_uid`/`credits`，界面未展示 | 记录页显示「扣费账号(昵称·短UID)/积分消耗/输入输出Tokens」+ 顶部汇总 | 代码就绪 |
| UID 竖排 | `PcInfoRow` 用 weight 争抢宽度，短标签被压成竖排 | 标签固定 96dp 列宽 + `PcMonoRow` | ✓ 单行显示 |
| 按钮融一块 | 满宽按钮垂直相邻无间距；手工 Row 摆放宽度不一致 | 统一 `PcActionGrid` | ✓ 已改概览/账号/成长/设置/存储 |

### 服务启停循环验证（实测）
| 步骤 | 端口 8788 | 按钮 |
|---|---|---|
| 初始（运行中） | 监听 | 「停止服务」可用 |
| ① 停止 | 关闭 ✓ | 「启动服务」可用（**不再变灰**） |
| ② 启动 | 监听 ✓ | 「停止服务」可用（**关键修复点**） |
| ③ 再次停止 | 关闭 ✓ | 正常 |

## 三、待办 / 已知限制

1. **成长任务的奖励与进度上游不下发**：`/v2/activity/growth/tasks` 只返回任务元信息
   （code/title/status/url），无奖励数额与进度，界面如实显示"奖励以官方客户端为准"。
   若要显示真实数值，需另找发放接口（1.2.7 中也未找到对应解析）。
2. **任务能否真正"完成"未端到端确认**：执行链会发多次网络请求（实测单次执行 >25s），
   期间进程可能被系统回收。需在真机上观察 `statusText` 的最终文案（`✓/✗/·` 日志行）。
3. 每日福利/猫猫/开学季的**响应字段**尚未逐个用真机核对（端点路径已与 1.2.7 对齐）。
4. 记录页"扣费账号"列刚改完，尚未装机验证。
5. OAuth 登录全流程未验证。
6. `AppsScreen`/`PromptScreen`/`SubScreens` 部分区域仍是手工 Row+weight 按钮（已有间距，视觉可接受），
   如需完全统一可继续替换为 `PcActionGrid`。

## 四、环境与踩坑（避免重蹈）

- 版本：AGP 8.13.2 / Gradle 8.14.2 / Kotlin 2.3.21 / Compose 强制 1.11.2 / Miuix 0.9.1
- `compileSdkVersion("android-37.0")` 必须字符串 API
- **Gradle 构建 2–6 分钟，一律用 `process` 后台跑**；前台 `base` 会超时；`sleep` 也可能被拖长
- **navigationevent 依赖**（必须两个都加，否则要么缺核心类、要么缺 compose 部分）：
  ```kotlin
  implementation("androidx.navigationevent:navigationevent:1.1.2")
  implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
  ```
  原因：Miuix 0.9.1 的 pom 声明 `org.jetbrains.androidx.navigationevent:*:1.1.0`，但该 group 下**没有 core 模块**（404）。
- Miuix 0.9.1 API 与技能文档（0.9.4）有差异：无 `SuperArrow/SuperSwitch/SuperDialog`（example 封装）；
  对话框用 `window.WindowDialog`/`overlay.OverlayDialog`（**都需 owner**）；组件无 `buttons` 参数；
  `NavigationBarItem` 是 `RowScope` 扩展；图标 `MiuixIcons.Regular.Xxx` 或 import `icon.extended.Xxx` 后用 `MiuixIcons.Xxx`
  （实际存在：Tasks/Favorites/Layers/ListView/GridView/ContactsCircle/Settings/Info/Me/Lock）
- 装机：`cp /sdcard/Download/x.apk /data/local/tmp/x.apk && pm install -r /data/local/tmp/x.apk`
  （`pm install` 不能直接读 `/sdcard`，SELinux 会拒）
- **验证方法论**：
  - `screen_observe` 的文本节点会被屏幕裁剪，**不要据被裁片段判定功能坏了**（曾两次误判清洗失效）；
  - 取完整文本与坐标用 `uiautomator dump` + `tr '>' '\n' | grep -oE 'text="..."'`（宿主机无 python3）；dump 偶发失败，重试 2–3 次；
  - 交互验证用 `android.util.Log` + `logcat -d -s TAG:I`；
  - **字段名必须用真机响应核对，不要凭反编译或记忆臆测**（成长任务字段名踩过两次）；
  - 判断"崩没崩"看 `logcat -b crash -d`，`died->visible` 是系统回收不是崩溃。
