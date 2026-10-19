# PROGRESS

## 当前进行中：国际版模型目录校准

### 已完成
1. 逆向 CodeBuddy 桌面版（macOS arm64 v4.12.1）→ 确认 `product-ide*.json` 无代码引用，真正模型源是 `extensions/genie/product.json`（18 条）
2. 从 npm 拉取 CLI 包 `@tencent-ai/codebuddy-code@2.169.0`（2026-10-18 构建）→ `product.json` 22 条 / `product.internal.json` 32 条 / `product.ioa.json` 53 条
3. 用国际版账号 token 成功调用 `GET /console/enterprises/personal/models` → HTTP 200，返回 20 个唯一模型 id（权威数据）
4. `app/src/main/assets/codebuddy-international-models.json` 已按服务端返回重建：
   - `models`: **20 条**（原 36 条 → 中途误改 29 条 → 最终校准 20 条）
   - 删除：12 个下线文本模型 + 6 个账号不可见图像/视频模型 + 4 个错误引入的模型
   - 新增：`gpt-5.3-codex`（服务端在提供，此前被误删）
5. 三处数据修复（均已回读核实）：
   - `source` → `server:GET /console/enterprises/personal/models (@tencent-ai/codebuddy-code@2.150.0 展示字段)`
   - `generated_at` → `2026-10-19T00:00:00Z`，补 `note` 说明
   - `gpt-5.3-codex` 补 `credits: "x1.25 credits"`（来自网页截图 1.25x）
   - `agents[0].models` 清理 6 个孤儿引用（minimax-m3 / gemini-3.1-pro / kimi-k2.5 / gemini-3.0-pro-image / gemini-3.1-flash-image / gemini-2.5-flash-image），现 18 条全部有效

### 待完成
1. **编译 APK** — 被 Harness 运行时拦截：
   ```
   前台进程清理尚未完成，暂不能启动新的重型命令
   命令进程尚未退出，保留资源供重试
   ```
   连 `ls` 都被拦，`ps aux` 无 gradle/java 残留 → 是运行时进程台账卡死，需重启太墟应用
2. 编译后从 APK 内解出 assets 核对是否为这 20 条（验证方法已验证可用：python zipfile）
3. git commit（分工：我 commit，用户 push）

### 关键技术事实（勿再踩坑）
- **权威数据源是服务端接口**，不是 CLI/npm 包的 `product*.json`。后者会漏掉（gpt-5.3-codex）或包含账号不可见的模型
- 调用该接口需 13 个 header：
  ```
  Content-Type, Accept, Authorization: Bearer <token>, X-User-Id,
  X-Enterprise-Id, X-Tenant-Id, X-Domain, User-Agent,
  X-Product, X-Product-Version, X-IDE-Type, X-IDE-Name, X-IDE-Version
  ```
  token 取 `auth.accessToken`，uid 取 `account.uid`（账号导出 JSON 结构）
- 该接口不稳定：同一脚本同一 header，一次 200、之后连续 500（APISIX 网关）。拿到 200 必须**立刻落盘**
- 国际版账号网页下拉 16 项，`default-model` = **Auto 0.79x**
- 国内版无内置兜底目录（`loadBundledInternationalCatalog` 仅 INTERNATIONAL 分支），断网时模型列表为空

### 我在这轮犯过的错（记录以便复盘）
1. 拿 CLI `product.json` 当权威基准 → 删掉实际可用的 `gpt-5.3-codex`，加了 4 个账号不可见的模型
2. 首次拿到 HTTP 200 原始响应时没落盘（`json.dump` 在不执行分支），后续接口 500 无法重拉
3. 多次把 `arguments` 字段误包进 `base` 的 `command`，浪费大量轮次
4. 说“这个模型下线了”时未验证账号可用性，被用户用网页截图纠正

### 环境备注
- 项目根：`/workspace/WorkBuddy2API-Android`
- 构建：`/opt/gradle-8.14.2/bin/gradle assembleDebug --offline`（wrapper 会尝试下载，勿用 `./gradlew`）
- 沙箱无 `unzip`、无 `xxd`，用 Python `zipfile`
- 账号导出文件含 accessToken（有效期至 2027，剩余约 334 天）与 refreshToken，位于
  `/attachments/1792380124014_workbuddy-jilintang17_gmail.com__1_.json` —— **建议用户删除**，未复制进项目目录
