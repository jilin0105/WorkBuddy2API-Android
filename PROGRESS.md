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

---

## UI 全量重写（进行中 · 续跑锚点）

### 目标
重写整套 UI：单一 Miuix 界面、修交互缺陷、只读信息从卡片堆叠改为原生列表组件、
二级页顶栏返回分级、统一设计令牌，并保证可编译产出真机 APK。

### 观察到的旧版缺陷（重写依据）
1. 间距体系崩坏：同屏出现 10/12/14/16/5/2dp 六种间距，页面各自塞 Spacer 补救。
2. `PcCard` 自带横向 padding + 又传 insideMargin，形成双重缩进。
3. `PcInfoRow` 用 weight 分列 → 短标签（UID）被压成逐字竖排（有截图实锤）。
4. 按钮手工 Row 摆放散落 6 个页面：忘加间距 / 同行宽窄不一 / 奇数个被 weight 拉满整行。
5. 二级页只有一个「返回」文字按钮且固定回所属 Tab；实际二级页可从多 Tab 进入
   （用量统计来自概览与设置），于是"返回回到莫名其妙的地方"。
6. 设置页首屏 16 个可交互组件 + 5 个常驻 TextField（每个启动 4 个动画）→ 首屏 2612ms。
7. 概览/记录/模型把整张表铺成卡片墙（100 条记录 = 100 卡 × 7 行）。
8. 长文本（出网取证 body 可达上百 KB）交给单个 Text() 渲染 → 渲染超时闪退。
9. 编辑器类弹窗复用位置时保留上一次输入（串值）。
10. 账号卡 5 个等宽按钮且「删除」与「停用」相邻，误触代价高。
11. 启动中 `enabled` 被 starting 锁死 → 反复启停后再也停不掉。

### 已完成（代码全部落盘）
- `ui/PcDesign.kt`（新）语义化令牌：PageInset/BlockGap/TitleGap/RowGap/SectionGap/
  ButtonGap/BottomInset/TopInset/LabelWidth/DialogBodyMax/DetailBodyMax/ListPadding/CardInside。
- `ui/PcComponents.kt`（重写）PcList / PcSection / PcCard / PcDivider / PcGap / PcHGap /
  PcRow / PcClickRow / PcInfoRow（固定标签列宽）/ PcMonoRow / PcMetricRow+PcMetric /
  PcStatus / PcNote / PcErrorText / PcEmptyCard / PcActionGrid+PcAction / PcWideButton。
  核心：布局约束外置，间距责任唯一化。
- `ui/PcDialogs.kt`（新）PcConfirmDialog(danger) / PcAlertDialog / PcInputDialog /
  PcDetailDialog（12000 字符渲染上限 + 复制原文）/ PcLogDialog / DialogState。
  统一 WindowDialog（非 OverlayDialog），规避 NavigationEventDispatcher 崩溃链。
- `ui/AppShell.kt`（重写）6 Tab + 7 二级页；subOrigin 记住来源；IconButton 返回
  （ArrowRight rotate 180°）；Snackbar 承载瞬时提示；BackHandler 三级分级；
  去掉冗余第二层 ProvideNavigationEventDispatcher（保留 NavCompat 包外层）。
- `ui/OverviewScreen.kt`（重写）服务卡→指标卡→额度条→待办→入口 五层；
  活动日志降级弹窗；启动中可取消启动；TodoItem 数据类（弃用 Triple）。
- `ui/AccountsScreen.kt`（重写）账号卡压缩；「更多」弹窗收纳低频操作；
  签到结果汇总+明细弹窗；删除单独一行错误色；保留全部原功能（登录/切换/导入/导出/
  启停/优先级/积分详情/批量刷新/批量签到）。
- `ui/ModelsScreen.kt`（重写）分组清单（每组一卡），版本短标签，能力/上下文/成本一行。
- `ui/AppsScreen.kt`（重写）接入参数卡可一键复制；Key 列表一卡多行；详情/编辑弹窗。
- `ui/RecordsScreen.kt`（重写）一卡内三行紧凑记录；进页自动拉取；详情按需读全文。
- `ui/GrowthScreen.kt`（重写）任务行减半高；「更多操作」弹窗；日志自动弹窗；
  列表 key 用「索引:code」避免 Key "" already used。
- `ui/SettingsScreen.kt`（重写）首屏 7 组件；分组入口下钻。
- `ui/SubScreens.kt`（重写）Usage / Inspector / Storage / KeepAlive / Schedule 五页。
- `ui/SettingRow.kt`（重写）点击弹窗编辑 + PriorityDialog（步进/滑块/快捷档位三路径）。

### 剩余工作（下段从这里继续，按序执行）
1. **写 `ui/CreditDetailDialog.kt`**：AccountsScreen 已按
   `CreditDetailDialog(accountName, detail: JSONObject?, onRefresh, onClearLedger, onDismiss)`
   调用。需实现：积分总量/已用/剩余、来源分类（签到/奖励/消耗）、
   过期提醒、清空台账确认（danger）、明细滚动限高。
2. **核对并补齐 AppState API**（编译器会报错，逐个补）：
   读取类 `usageSummary / usageSeries / usageRecords / usageTotal / revision /
   checkinResults / selectedAccount`；
   方法类 `loadUsageIfStale / loadUsage / usageDetail(id) / deleteUsage(id) /
   clearUsage(total) / storageInfo / storageBreakdown / inspectorEntries /
   inspectorEnabled / inspectorExport / setInspectorEnabled / clearInspector /
   clearWebViewCache / trimUsage / cleanupUsage(days) / loadSettings / saveSetting /
   loadCreditDetail(key) / clearCreditLedger(key) / appKey(id) / showMessage / clearMessage /
   toggleServer / refreshCredits / refreshModels / loadAccounts / importAuth / exportAccount /
   exportAllAccounts / setAccountPriority / setAccountEnabled / deleteAccount / checkInAll /
   createApp / updateApp / deleteApp / toggleApp`。
3. `assembleDebug` 编译，逐一修 Kotlin 错误（预计集中在：Miuix 组件参数名、
   `Icons.Layers` 扩展图标是否存在于 miuix-icons、TextField 两套 API 的选择、
   `rememberTextFieldState`/`snapshotFlow` 的导入）。
4. 装机验证：二级页返回、各弹窗不崩、服务启停、账号卡按钮、Key 创建/查看、记录详情。

### 关键技术约定（勿违反）
- 弹窗一律 `WindowDialog`（`OverlayDialog` 在纯 Compose 宿主会崩）。
- `WindowDialog`/`OverlayDialog` **没有 buttons 参数**，按钮写进 content。
- 图标只用 miuix-ui 自带的 `MiuixIcons.Basic.*`，或 miuix-icons 扩展（需确认存在）。
- 卡片内左右边距只由容器 insideMargin 提供，行组件永远不加横向 padding。
- 长文本先裁剪到 12000 字符再渲染，复制走原文。
- 构建配方见 memory `miuix-arm64-build-recipe`（AGP 8.13.2 / Gradle 8.14.2 /
  `compileSdkVersion("android-37.0")` / platform 目录 android-37.0）。
