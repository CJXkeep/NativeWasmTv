# 迭代 I4 · 远程诊断与更新分发

- 状态：**已开工（A~C 组完成，B5 / C5 / E3 收尾待做）**（2026-09-25 设计定稿、修订并两轮 review；四项决策：
  接收端 = 钉钉机器人、TV 长按反馈 = 做、自动发送 = 不做、远程触发下载 = 不做；无未决项，结论见文末「已决策」）
- 依据：[`docs/开发理念.md`](../../开发理念.md)——原则 4「一切失败必须有终态」、原则 6「先让时间可观测」、原则 7「做减法」；
  北极星「崩溃率 0」的可定位前提、「设置项 ≤ 12（当前 11，只减不增）」
- 上位文档：[`I3 验收报告`](../I3-坏台终态与回归网/验收报告.md)（其「拆分 `MainActivity`」入口条件第 1、2 条已满足）
- 来源：作者提出的两个运维痛点——① 能否远程触发版本更新、远程取得问题日志；② 国内下载体验与是否挂 Gitee。
  **关键约束：作者不在设备身边（不同城），且设备在非技术用户家中。**

## 迭代目标

一句话：**把「设备在用户家里出问题」变成「作者在外地拿得到现场、发得出修复」的闭环。**

1. **现场可获取（核心，双通道）**：
   - 就近通道：局域网内手机打开控制页直接查看/复制（作者回家或家人会用手机时）；
   - **异地通道（主）**：设备主动把脱敏诊断**发送到作者的接收端**——家人只需按一次遥控器或点一次网页按钮，
     无需理解任何技术细节、无需把手机给谁、无需复制粘贴。这是本迭代的核心增量。
2. **更新可触发、可观测**：发送通道确认可用后，控制页可手动触发更新检查/下载，四种结果都有明确终态与时间戳；TV 弹窗流程不变。
3. **国内下载不再单点**：更新清单、APK、CJS 插件下载改为「多加速源回退 + 直连兜底」；Gitee 作为可选分发镜像（决策门控）。

**边界（必须写清）**：

- 「发送」是**用户主动触发的一次性动作**（遥控器长按 / 网页按钮）——这是与「统计上报」的分界线；
  **已拍板：故障之后不自动发送**，理念无需修订。
- 不做静默安装（Android TV 无 root 时系统安装器必经人工确认）；不做公网暴露设备管理页（控制页仍只监听局域网）。
- 这是一次**运维能力**迭代，不改变「看电视」主体验。

## 现状盘点（设计依据）

行号口径：2026-09-25 工作区版本。

| 能力 | 现状 | 位置 |
| --- | --- | --- |
| 更新检查 | `AutoUpdater` 拉 GitHub Release 清单（经 `gh-proxy.com`），仅 `onCreate` 调一次；结果静默（失败只 `Log.w`），无「已是最新 / 失败原因」状态 | `MainActivity.java:718`、`AutoUpdater.java:35`、`AutoUpdater.java:53` |
| 更新下载 | 下载 → SHA-256 校验 → 调系统安装器；链路完整 | `AutoUpdater.java:197`、`AutoUpdater.java:335` |
| 崩溃日志 | Java 崩溃写 `files/last-crash.txt`，**全仓无任何读取/展示/发送**；native 崩溃不捕获 | `CrashReporter.java:44` |
| 控制页接口 | `Listener` 接口 + `route()` if-else；已有 `GET /api/state`（含 `playback` 块） | `LocalControlServer.java:29`、`LocalControlServer.java:218` |
| 控制页 UI | 单文件 `control.html`，`mainPage` + 5 个 `secondary-page`，hash 路由 `showPageFromHash()` | `control.html:117`、`control.html:167`、`control.html:213` |
| TV 终态提示 | I3 的 sticky 文案「⋯ ↑↓ 换台 / OK 打开频道列表」，此时无「上报/反馈」出口 | `MainActivity` sticky 分支 |
| 加速 | 单一前缀 `https://gh-proxy.com/`；`apply` / `unwrap` 两函数，硬编码一处、调用三处 | `GithubProxy.java:5` |
| 更新源硬编码 | 清单 URL（`AutoUpdater.java:35`）、`releaseBase`（`app/build.gradle:158`）、代理白名单（`AutoUpdater.java:399`） | 三处 |
| 发布脚本 | 构建双 ABI + 生成 `version.json` + 校验哈希；无镜像上传步骤 | `scripts/build-release.ps1:224` |

### 缺失（本轮要补）

| # | 缺失 | 后果 |
| --- | --- | --- |
| 1 | **作者在外地时无取数手段** | 家人说「打不开」时只能电话描述；`last-crash.txt` 与失败现场困在设备里 |
| 2 | 崩溃日志无出口 | 无展示、无发送；非技术用户不可能 adb 取文件 |
| 3 | 播放失败无历史 | `playback` 块只有「最近一次」，且重启即丢；无法判断「偶发还是稳定复现」 |
| 4 | 更新检查无终态、不可触发 | 电视待机不重启就收不到新版本；检查失败用户与作者都不知道 |
| 5 | 加速单点 | `gh-proxy.com` 挂掉时：更新检查、APK 下载、CJS 插件下载同时失效 |
| 6 | 代理使用不可见 | 无法回答「这次下载到底走了哪个源、回退了几次」 |

## 迭代范围

**做**：A 组诊断通道（本地留存 + 局域网查看 + **异地发送**）、B 组更新可观测与远程触发、C 组多加速源回退、E 组回归扩展。

**门控做**：D 组 Gitee 分发镜像——前置条件不成立（实名账号 / 分发仓审核未过）则整组砍掉，其余组不受影响。

**不做**（写进承诺）：

- **后台自动上报、云日志平台**（已拍板）：发不发完全由用户主动决定，故障之后也不会自动发送。
- 任何需要作者自建/自维护的服务端：接收端只用**现成的第三方服务**（钉钉群机器人为默认；通用 JSON 端点可接任意现成服务，见决策 7）。
- 新增用户可见设置开关：控制页可配置项保持 **11**；接收地址属「作者工具」（编译期注入 + 高级折叠内可覆盖），不计入设置项。
- 真·推送（FCM / 自建推送）与静默安装：TV 系统安装器必经人工确认，无法绕过。
- 公网暴露控制页（端口映射/内网穿透）：设备在非技术用户家中，安全与配置成本都不可控。
- 远程触发下载与安装（**2026-09-25 拍板取消**）：更新只做「检查 + 展示」，安装动作仍由 TV 端用户完成。
- **native 崩溃捕获（已知盲区，明确登记）**：QuickJS / TLS 栈 / HLS 代理的原生崩溃无法在 Java 层捕获，
  本轮不引入 tombstone 解析或 root 依赖；诊断只覆盖 Java 崩溃与播放失败链。
- 改动播放主链、拆分 `MainActivity`、CJS/Ku9 体系合并（本轮改动仅限埋点、发送与接口转发）。

## 关键设计决策

### 决策 1：诊断双通道——局域网直连查看 + 主动一键发送（不自动上报）

| 通道 | 谁在用 | 需要家人做什么 | 依赖 |
| --- | --- | --- | --- |
| 局域网直连 | 作者回家 / 家人会用手机 | 打开控制页（电视上已有管理二维码） | 无外部依赖 |
| **主动发送（主）** | **作者在外地** | **遥控器长按 OK 一次，或网页上点一次按钮** | 一条第三方推送通道（决策 7） |

安全边界：控制页仍只监听局域网、不引入鉴权（既有暴露面不变）；发送是**用户主动、单次、排障目的**，
不是统计上报，不含用户行为数据（决策 3 脱敏适用于两条通道）。

### 决策 2：失败时间线 = 进程级环形缓冲 + 落盘留存 + 崩溃快照

- 新增 `PlaybackDiagnostics`：进程级单例，`synchronized` 读写；
  每条记录 `{at, stage, channel, line, reason, attempt, elapsedMs}`。
- **两份数据、职责不同（review 修正）**：
  - 内存环形 **10 条**：唯一用途是崩溃时给 `CrashReporter` 取快照（崩溃 handler 必须极短且不做文件 IO）；
  - 落盘滚动 **50 条**（`files/diagnostics.log`，≤ 64 KB 自动截断）：**诊断读取与发送的唯一数据源**，
    保证「上周那次偶发失败」在作者拿到设备前不丢。
- **写入路径（review 补充）**：失败埋点在 UI 线程，只做内存追加 + 投递；落盘由单后台线程追加
  （进程退出最多丢最后几条，可接受，不为此引入持久化队列）。读取按 64 KB 上限截断解析。
- 埋点收敛在 I3 已收敛的失败路径上（`handleChannelFailure` / `setOnErrorListener` /
  `recoverStalledPlayback` / `switchCustomSource` 收尾 / CJS 解析失败），不新增第二条失败链。
- 崩溃时由 `CrashReporter` 把内存快照**附写进 `last-crash.txt`**（try/catch 包裹，写失败不影响原流程）。

### 决策 3：诊断内容脱敏（发送出去的与局域网看到的是同一份）

- 线路地址只保留 `scheme://host/path`，query 一律打码为 `?…`（token 多藏在这里）。
- 不含 cookie、不含用户上传的源文件名、不含设备唯一标识；只带机型 + 局域网 IP 尾号（用于区分多台设备）。
- 崩溃栈原样保留（开发者信息，不含用户数据）。
- 单条发送上限 **8 KB**（**review 修正截断方向**）：按「异常类型与消息 → 自己代码帧 → 失败记录最近 3 条」
  优先级保留，被截掉的是框架帧尾部；局域网完整版 ≤ 64 KB。

### 决策 4：更新检查终态化（四态）+ 控制页手动触发，TV 弹窗不变

`AutoUpdater` 增加只读结果态：`{state: idle|checking|upToDate|available|error, remoteVersion, reason, lastCheckAt, source}`。

- 控制页 `POST /api/update/check` 触发（异步，立即返回）；状态经 `/api/state` 的 `update` 块轮询展示。
- 并发保护：复用现有 `checking` 标志；重复触发返回当前态，不叠加请求。
- TV 端行为：发现新版本即后台静默下载，下载完成后提示一次「是否安装」（2026-09-25 修订，详见 B7/B8）。
- **远程触发下载：不做（2026-09-25 拍板取消 B3）**。安装确认必须在 TV 端完成，多一个接口只增加守卫与测试面；
  更新卡片只做「检查 + 展示」，下载与安装仍由 TV 端现有弹窗流程承担。
  （review 发现随之归档：`downloadUpdate()` 缺「已在下载」守卫——取消远程触发后不存在新的并发入口，不改代码。）

### 决策 5：多加速源回退下沉到「直接下载」链路

`GithubProxy` 改为候选列表（≥2 个公共加速域 + 直连兜底，常量集中一处，执行时实测筛选）：

- 新增 `candidates(url)` 返回候选序列；`apply(url)` 保留（返回首选，兼容展示场景）；
  `unwrap` / 新增 `matches` 兼容多前缀（`CjsPluginRuntime.java:160` 的识别逻辑依赖它）。
- 回退发生在**真正打开连接处**：`AutoUpdater`（清单 + APK）、`CjsPluginRuntime.download`（插件文件与目录）；
  逐候选尝试，按「连接失败 / 超时 / 5xx」切换，记录实际来源与回退次数（进诊断页，原则 6）。
- **已知限制**：`PlaylistManager` 的推荐源 URL（`PlaylistManager.java:93`）是持久化地址，单值不可回退，
  本轮保持首选项 + 失败时现有提示。

### 决策 6：Gitee 定位为「分发包镜像」，权威源仍为 GitHub（门控执行）

- **清单获取**：GitHub 清单（经加速回退）优先，Gitee 分发仓清单（直连）兜底——避免陈旧镜像覆盖权威信息。
- **APK 下载**：`version.json` 新增 `apk32Mirrors` / `apk64Mirrors` 数组（Gitee Release 附件地址），
  下载顺序 = 镜像优先 → 主 URL 兜底。
- **安全**：镜像来源的 APK 仍必须通过清单内 SHA-256 校验；来自 Gitee 兜底的清单若缺少 `sha256` 字段则整体拒绝。
  `AutoUpdater.proxiedGithubUrl` 的白名单扩展到 `gitee.com`。
- **前置条件**：作者实名 Gitee 账号 + 公开分发仓（只放 Release 附件与清单，不放源码）审核通过；不满足则 D 组砍掉。
- **发布纪律**：`build-release.ps1` 发布时强校验「两边清单与哈希一致」才算发布成功；token 由本地文件/环境变量提供，不入库。

### 决策 7：发送走「第三方现成通道 + 域名自动适配」，不要求作者维护服务端

- 新类 `DiagnosticsSender`：把诊断摘要 POST 到一个（最多两个）接收地址。
  **地址来源与凭据安全（2026-09-25 修订，作者提出「硬编码后上传仓库会不会很危险」）**：
  - **默认不在 APK 内内置任何凭据**：地址与加签密钥由控制页「诊断与更新 → 高级：接收地址」在设备上配置，存设备本地 SharedPreferences；
  - 自用构建可用 `-PdiagnosticWebhook=` / `-PdiagnosticSecret=` 注入，但注入值会成为 `BuildConfig` 常量、**反编译 APK 即可提取**；
    因此构建守卫**直接拒绝带凭据的 release 包**，需显式 `-PallowEmbeddedDiagnosticCredential=true` 才放行（防「自用参数忘了清」误发布）；
  - `-P` 参数只生成到 `app/build/`（已被 `.gitignore` 忽略），**不会随仓库泄露**；唯一会入库的写法是写进 `gradle.properties`，已在该文件加禁止注释；
  - 加签的意义仅限「只拿到 URL 的人无法发送」；**secret 同在 APK 内**时无法阻止提取者使用，只能靠「专用可弃机器人 + 随时轮换」兜底。
  - **自用注入版与公开发布的共存前提（2026-09-25 补充）**：更新链路完全依赖 GitHub Release 的
    `releases/latest/download/version.json` 与其中的 APK 地址，因此自用包**不需要上传 release，也能照常收到更新**，但要满足三条：
    1. **签名与公开发布包一致**，否则覆盖安装会失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）；
    2. **versionCode 不高于已发布版本**，否则该设备的 `VERSION_CODE` 更大，永远不会再提示更新；
    3. **不要改 `-PapplicationIdOverride`**，否则下载回来的公开包是另一个应用、装不到一起。
    满足这三条时，公开包覆盖安装后**凭据不会丢**（地址与密钥存在 SharedPreferences，升级不清 data）。
    反之：若完全不发布 release，则 `version.json` 不更新，所有设备自然收不到新版本（这本身不是故障）。
- **通道适配器**（纯函数，可单测）：**钉钉自定义机器人**（`oapi.dingtalk.com`，国内直连）+ 通用 JSON 兜底
  （通用格式供作者自接任意端点）。**已收敛**：飞书 / 企业微信 / ServerChan / Bark 等适配器不做（作者已选定钉钉，做减法）。
- **钉钉接入形态**（2026-09-25 官方文档核实）：群机器人 Webhook **默认开通、无需申请、无需 OAuth 授权，个人群即可创建**；
  安全设置选「加签」（`HMAC-SHA256(timestamp + "\n" + secret)`，secret 随构建参数注入）或「自定义关键词」，
  **不要选 IP 白名单**（家庭宽带 IP 会变，必然失败）；限流 20 条/分钟，本设计频控 10 分钟 3 次，远低于限制。
- **成功判定必须含业务码（review 修正）**：钉钉在签名错 / 关键词不匹配 / 限流时返回 **HTTP 200 + `errcode != 0`**，
  适配器必须解析响应体 `{"errcode":0,"errmsg":"ok"}`；`errcode != 0` 视为失败，`errmsg` 作为终态原因展示，
  并附「检查设备时间」提示（加签要求偏差 ≤ 1 小时）。通用 JSON 兜底通道只看 HTTP 状态。
- **通道候选 2 个**（分号分隔，**第二个可留空**——单机器人是常态）：第一个失败（连接 / 超时 / 非 2xx / 业务码非 0）
  依次尝试第二个；记录终态 `idle|sending|sent|failed(reason)`，控制页展示，TV 端由遥控器触发时用一次 Toast 反馈（不新增界面）。
- **接口立即返回（review 修正）**：`POST /api/diagnostics/send` 只下发任务并立即返回 `{accepted:true}`（或当前进行中状态），
  状态由控制页轮询 `/api/state`；避免 8 s × 2 通道的同步等待占满控制页工作线程（worker 仅 4 个）。
- **频控**：同一设备 10 分钟内最多 3 次（防误触/防刷），超限返回明确终态提示；计数存内存（进程重启清零，可接受）。
- **发送配方（内容物固定）**：设备标签（机型 + IP 尾号 + 版本）、当前频道与播放状态、最近 3 条失败记录（脱敏）、
  崩溃摘要（有则附）、代理来源与回退次数。
- **失败不阻塞**：发送在后台线程执行，超时 8 s/通道；失败仅落诊断记录，不影响播放与终态提示。
- **不抽公共回退抽象（review 结论）**：`AutoUpdater` 的加速源回退（同域名换前缀）与本通道回退（不同端点、不同协议）
  语义不同，按纪律 6 不引入公共类，各自实现。
- **密钥形态**：接收地址即凭据（机器人 webhook key），内置在自用 APK 中——不做公开分发，接受该风险；
  日志中地址一律打码；必要时删机器人重建即轮换。

### 决策 8：触发点两个——遥控器长按 + 网页按钮（不新增界面元素）

| 触发点 | 用户动作 | 说明 |
| --- | --- | --- |
| TV 终态提示 | **长按 OK** | 复用 I3 的 sticky 文案，追加一句「长按 OK 反馈问题」；仅在终态出现，不打扰正常观看 |
| 控制页诊断页 | 点「发送给作者」 | 大字按钮 + 明示「将发送什么」，家人可照电话指导完成 |

不新增 TV 菜单项、不新增按键语义（长按不冲突：终态下短按 OK 仍是打开频道列表）。

**实现要点（review 对照代码后补充）**：现有 `dispatchKeyEvent`（`MainActivity.java:5850` 起）没有任何
`startTracking` / `onKeyLongPress` 使用，长按要新增，且有三个坑：

1. `ACTION_DOWN` 分支必须调 `event.startTracking()` 并返回 `true`（在该事件尚未被其它分支消费的路径上），
   否则 `onKeyLongPress` 不会被触发；
2. 长按已触发后，`ACTION_UP` 必须**不再走短按分支**（否则发完诊断立刻弹出频道列表）——用 `event.isLongPress()`
   或标志位拦截；
3. 生效条件限定「`stickyStatus == true` 且频道列表与 `managementPanel` 均未打开」；OK 键可能是
   `KEYCODE_DPAD_CENTER` 或 `KEYCODE_ENTER`，两个都要覆盖。

## 工作项与任务

### A. 诊断通道（核心）

- [x] A1 新增 `PlaybackDiagnostics`：内存环形（实现为 50 条）+ 落盘留存（50 条，≤ 64 KB）+ 脱敏 +
  `fullJson` / `reportText`（完整版/摘要版；崩溃路径走无锁 volatile 快照）
- [x] A2 失败链埋点：`handleChannelFailure`、`setOnErrorListener`、`recoverStalledPlayback`、`switchCustomSource` 收尾、CJS 解析失败
- [x] A3 `CrashReporter` 附写时间线快照（限额、try/catch）
- [x] A4 新增 `DiagnosticsSender`：钉钉适配（加签 / 关键词两种模式）+ 通用 JSON 兜底 + 双候选回退 + 频控 + 终态
- [x] A5 `LocalControlServer`：`GET /api/diagnostics`（只读）、`POST /api/diagnostics/send`、`Listener` 扩展
  （另加 `POST /api/update/check` 与 `/api/settings` 的接收地址字段）
- [x] A6 `control.html` `#maintenance` 页：诊断展示 + 「发送给作者」+ 「复制全部」+ 发送状态 + 「高级：接收地址」；`mainPage` 入口
- [x] A7 TV 终态追加「长按 OK 反馈问题」：`startTracking` + `onKeyLongPress` + UP 拦截（决策 8 三个坑）；
  文案改动落在 `PlaybackFailurePolicy.terminalStatus`（纯函数，同步补单测）+ 一次 Toast 反馈（失败含原因）
- [ ] A8 实测：**部分完成**——坏台 → 长按 OK → 触发发送（用本地不可达地址验证失败终态）已通过；
  **待补**：模拟崩溃后发送含崩溃栈、钉钉业务码场景（需作者提供 webhook）

### B. 更新可观测与远程触发

- [x] B1 `AutoUpdater` 四态结果（含 `source`、`reason`、`lastCheckAt`）
- [x] B2 `POST /api/update/check` + `/api/state` 的 `update` 块
- [x] B3 原方案（`POST /api/update/download`）——**已取消**（2026-09-25 拍板）：不做远程触发下载
- [x] B4 `control.html` 更新卡片：当前版本、检查按钮、四态文案与时间（无下载按钮；含「下载状态」与「更新说明」两行）
- [x] B6（2026-09-25 追加）**周期检查**：`checkForUpdatesIfDue`，前台每 6 小时一次 + 每次回到前台距上次超 6 小时也检查
      （解决「电视常年待机不重启 → 永远收不到更新」）——**设备已验证**：未到期回前台时日志 `Update check skipped: interval not reached`
- [x] B7（追加，2026-09-25 修订为「只打扰一次」）**去掉「发现新版本」确认框**：发现即静默下载，
      只在下载完成后提示一次「已下载，是否安装」；控制页始终可手动检查。随修订一并删除已失效的手动下载路径、
      进度框与 4 处字符串资源（做减法）。**节流落到安装提示（review 后收口）**：点过「稍后」的同一版本
      24 小时内不再提示（`UpdatePromptPolicy` 纯函数 + prefs，跨进程重启生效）；没点过则照常提醒。
      ——**已实测**：单对话框、无叠加；点「稍后」后杀进程重启不再弹；Activity 重建不重复检查
- [x] B8（追加）**自动下载**（已拍板）：发现新版本后台静默下载 + sha256 校验通过后，弹「已下载，是否安装」；
      安装仍需 TV 端系统确认（无 root 无法静默安装）——**真实发版端到端已验证**（vc8 → 设备升级完成）
- [x] B9（追加）下载/校验失败原因进 `update` 状态块（远程可诊断「更新为什么装不上」）——状态字段已在设备验证
- [ ] B5 终态验证：无更新 ✓ / 控制页手动检查 ✓ / 断网 / 加速域全挂 ——**未单独验证后两种**

### C. 国内加速：多源回退

- [x] C1 `GithubProxy`：候选列表 + `unwrap` / `matches` 多前缀兼容
- [x] C2 连接级回退：候选顺序、切换判定、直连兜底、记录实际来源与回退次数
- [x] C3 覆盖三处：更新清单、APK 下载、CJS 插件与目录下载
- [x] C4 诊断页展示代理来源与回退记录
- [ ] C5 实测：屏蔽首选域后三条链路仍可用（对照记录回退次数与耗时）——**未做**（当前设备实测均为「首选命中」）

### D. Gitee 分发镜像（门控，未过则砍）

- [ ] D1 前置确认：实名账号、分发仓审核、附件直链格式与 API 参考（Gitee OpenAPI v5）
- [ ] D2 `build.gradle` `releaseBase` 参数化 + 生成 `apk32Mirrors` / `apk64Mirrors`
- [ ] D3 `AutoUpdater`：Gitee 清单兜底 + 镜像优先下载 + sha256 强制 + 白名单扩展
- [ ] D4 `build-release.ps1`：Gitee 上传与两边一致性强校验（token 不入库）
- [ ] D5 实测：国内网络下清单/APK 下载速度对比（记录事实，不做承诺）

### E. 回归与验证

- [x] E1 单测扩展：新增 `DiagnosticsSenderTest`(8) + `PlaybackDiagnosticsTest`(5) + `UpdatePromptPolicyTest`(6)，总数 11 → **30**
- [x] E2 `gradlew test` 全绿（30/30）+ 控制页脚本 `node --check` 通过 + `scripts/regression.ps1` 26/28
      （D10/D13 用 `run-as` 读应用私有目录，设备装**release 包**时不可用 → 失败为环境所致：
      同轮用 `adb root` 直接核对 `files/cjs-sites-v5/catalog.json` 存在且 `"protocol":5`，断言实质通过；
      设备装 debug 包时脚本可全绿）
- [ ] E3 收尾：验收报告 + `reports/`（诊断、发送、更新、回退四组实测记录）——**待收尾**（依赖真实发版与 webhook 两项验证）

## 验收标准

| 维度 | 指标 | 目标 |
| --- | --- | --- |
| 发送可用性 | 家人按遥控器长按 OK（或网页点按钮） | 作者接收端 ≤ 60 s 收到含机型/版本/频道/失败记录的诊断 |
| 发送终态 | 正常 / 未配置 / 通道失效 / 钉钉业务码非 0 / 频控超限 | 五种都有明确文案（含 `errmsg` 与「检查设备时间」提示）；TV 端仅一次 Toast |
| 按键回归 | 终态长按 OK 后抬起 | 不弹出频道列表、不触发换台；正常状态长按 OK 行为与现状一致 |
| 诊断可用性 | 局域网手机打开诊断页 | ≤ 15 s 拿到「频道 / 线路 / 原因 / 时间」完整链 |
| 诊断留存 | 失败与崩溃后重启 | 上次失败记录（≥ 50 条滚动）与崩溃快照仍可读 |
| 排障闭环 | 从「家人说打不开」到作者拿到现场 | ≤ 2 min（电话指导长按 OK / 点按钮），对比现在是「不可能」 |
| 更新终态 | 无更新 / 有更新 / 检查中 / 失败 | 四态均有明确文案与时间戳；TV 弹窗流程无回归 |
| 更新可用性 | 控制页触发检查 | ≤ 30 s 内出终态（含回退重试） |
| 更新到达性 | 前台持续运行 6 h / 回到前台 | 到期自动检查一次；未到期不重复（日志 `Update check skipped: interval not reached`） |
| 打扰次数 | 一次更新全程 | 只弹一次「已下载，是否安装」（不再有「发现新版本」确认框） |
| 稍后节流 | 点「稍后」后重启进程 / 重建 Activity | 24 小时内不再提示（跨重启生效）；重建不重新检查（日志 `Update check skipped: interval not reached`） |
| 更新说明 | 控制页维护页 | 显示清单 `releaseNotes`（压成单行、≤ 300 字符）；TV 端不展示 |
| 自动下载 | 发现新版本 | 后台静默下载 + sha256 校验 → 「已下载，是否安装」；失败原因进 `update` 状态块 |
| 加速回退 | 屏蔽首选加速域 | 更新检查、插件下载、目录刷新三条链路仍成功；回退次数可查 |
| 设置项 | 控制页可配置项 | 仍为 11（发送地址属作者工具，不计入） |
| 隐私 | 发送内容 | 无明文 token（query 打码）、无用户行为数据；发送前在页面上明示内容清单 |
| 兼容 | API 与 WebView | 不引入新 API；控制页保持现有 ES5 风格；API 14 未实测项照实标注 |
| 回归 | `gradlew test` / `scripts/regression.ps1` | 全绿；负向用例改坏仍必须红 |
| 代码规模 | `MainActivity.java` | 仅埋点、触发与转发，净增 ≤ 160 行 |

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| 接收凭据随 APK 分发被提取 | **默认不内置**（走控制页配置）；带凭据的 release 构建被守卫直接拒绝；若自用注入，须用专用的可弃机器人（泄露即删机器人重建 = 轮换）；凭据能力仅限「往群里发消息」，不使用任何高权限凭据 |
| 第三方通道失效/限流 | 双候选回退 + 失败终态；诊断在本地留存，通道不可用时仍可局域网取 |
| 发送内容被第三方平台留存 | 只发脱敏摘要（无 token/无行为数据）；页面明示发送内容 |
| 家人误触长按 | 仅在终态提示下生效 + 频控（10 min ≤ 3 次） |
| 设备时间不准导致加签失败 | 失败原因展示钉钉 `errcode`/`errmsg` + 「检查设备时间」提示；诊断页展示设备时间；不自动改系统时间 |
| 诊断落盘写入失败/占满 | 固定 ≤ 64 KB 滚动覆盖，写失败仅记录，不影响播放 |
| 崩溃 handler 内二次崩溃 | 快照写入 try/catch 包裹；限额截断 |
| 时间线线程安全 | 固定条数 + `synchronized` 快照；只存短字符串 |
| 加速域失效或篡改 | 直连兜底；更新链路沿用 SHA-256 强校验；Gitee 兜底清单缺 `sha256` 整体拒绝 |
| Gitee 审核不通过或被下架 | D 组门控可整体砍掉，主链不依赖 Gitee |
| 手动触发与启动检查并发 | 复用 `checking` 标志，返回当前态不叠加 |
| 控制页体积与旧浏览器 | 复用现有 `secondary-page` 与 `system-info-list` 样式，不引第三方库 |
| 与 I5 拆分叠加导致回归不可归因 | 本轮不拆 `MainActivity`；拆分条件见文末 |

## 产出物

- 代码：`PlaybackDiagnostics`、`DiagnosticsSender`、`CrashReporter` 增强、`LocalControlServer` 新接口、
  `control.html` `#maintenance` 页、TV 终态长按反馈、`AutoUpdater` 结果态、`GithubProxy` 回退
- 单测：脱敏 / 通道适配与域名识别 / 候选回退 / 留存淘汰 / 频控（并入现有 11 用例集）
- 记录：`reports/`（诊断、发送、更新、回退四组实测；含一次真实的「外地接收端收到」记录）
- 文档：本 README + 收尾验收报告

## 文档约定

与 I1 / I2 / I3 相同：一个迭代两份文档，不另外造空壳。

| 文档 | 用途 | 时点 |
| --- | --- | --- |
| `README.md`（本文件） | 迭代计划 | 开始时定稿，执行中更新勾选与进度 |
| `验收报告.md` | 验收结论 + 对照数据 + 回顾 | 迭代收尾时创建 |

## 进度记录

| 日期 | 事件 |
| --- | --- |
| 2026-09-25 | 迭代设计定稿（来源：作者提出的远程诊断/远程更新与国内加速两个痛点） |
| 2026-09-25 | 修订：作者不在设备身边为硬约束，诊断改为「局域网直连 + 主动发送」双通道；新增决策 7/8、落盘留存、TV 终态长按触发；登记三项待决策 |
| 2026-09-25 | 三项决策拍板（见下）；适配器收敛为钉钉 / 飞书 / 企业微信 / 通用 JSON；理念无需修订 |
| 2026-09-25 | 接收端定案钉钉自定义群机器人（核实官方文档：无需申请/授权、个人群可建、限流 20 条/分钟）；适配器再收敛为钉钉 + 通用 JSON，安全设置建议加签、禁用 IP 白名单 |
| 2026-09-25 | 设计 review：对照代码修正 7 处 + 补 1 项威胁模型说明（明细见下），登记 1 项未决（B3） |
| 2026-09-25 | B3 拍板取消：不做远程触发下载，更新卡片只做「检查 + 展示」；设计定稿，无未决项 |
| 2026-09-25 | 开工（A~C 组）：新增 `PlaybackDiagnostics` / `DiagnosticsSender`；`CrashReporter` 附写失败快照；5 处失败链埋点；`LocalControlServer` 新增 `GET /api/diagnostics`、`POST /api/diagnostics/send`、`POST /api/update/check`；`control.html` 新增「诊断与更新」页；TV 终态长按 OK 反馈（文案落在 `PlaybackFailurePolicy`）；`GithubProxy` 候选回退覆盖更新清单/APK/CJS 插件；`AutoUpdater` 四态；`build.gradle` 注入 `DIAGNOSTIC_WEBHOOK/SECRET/KEYWORD` |
| 2026-09-25 | 回归：`gradlew :app:testArm64DebugUnitTest` **24/24 全绿**（新增 `DiagnosticsSenderTest` 8 例、`PlaybackDiagnosticsTest` 5 例）；`scripts/regression.ps1` **28/28**；控制页脚本 `node --check` 通过 |
| 2026-09-25 | 设备侧（MuMu arm64 / Android 15）已验证：`/api/state` 出现 `update` 与 `diagnostics` 块；`/api/diagnostics` 返回设备标签（机型 + IP 尾号）、ABI、失败列表、崩溃标记；`POST /api/update/check` 立即返回 `checking`，4 s 后转 `upToDate` 且 `source` 显示「首选命中 · 390ms」；`POST /api/diagnostics/send` 在未配置时返回 `skipped` + 「未配置接收地址」 |
| 2026-09-25 | 设备侧实测（见下「设备实测记录」）：坏台终态、失败链埋点与脱敏、发送链路与失败终态、长按 OK 反馈、短按 OK 回归、计数复位**全部通过**；顺带发现「断网 + 网页线路」下 stall 去重导致无终态 |
| 2026-09-25 | **仍未验证**：① 钉钉实发（缺 webhook 地址与加签 secret）；② 屏蔽首选加速域后的回退实测；③ API 14 老设备。**方法修正**：用 `svc wifi disable` 造坏台会连带切断模拟器 vnet 与 adb（冷启动未恢复，需重启 MuMu 客户端），正确姿势见「设备实测记录」 |
| 2026-09-25 | 凭据安全加固（作者提出「硬编码后上传仓库是否危险」）：默认不内置任何凭据、`gradle.properties` 加禁止注释、新增构建守卫——带凭据的 release 构建直接失败（实测 `:app:assembleArm64Release -PdiagnosticWebhook=…` 被拒；debug 放行） |
| 2026-09-25 | 作者确认优先级：诊断走配置流（不内置，定位为「全源失效时的排查工具」），重点转向自动更新 → 追加 B6~B9 并实现：周期检查（6 h）、提示节流（24 h）、静默自动下载 + 安装提示、下载失败入状态块；控制页更新卡片加「下载状态」 |
| 2026-09-25 | 设备验证（B6/B9）：启动即检 ✓；回到前台未到期 → `Update check skipped: interval not reached` ✓；控制页手动检查不受节流（`lastCheckAt` 更新）✓；`update` 块新增 `downloadState`/`downloadProgress`/`checkIntervalMs=21600000` ✓ |
| 2026-09-25 | **B7/B8 端到端验证（本地假清单）全部通过**：发现新版本 → 静默下载 100% → 安装提示 → 系统安装器 → 发现提示节流 → 稍后抑制；测试中发现并修复「安装提示无抑制」缺陷；新增仅编译期的测试开关 `-PupdateManifestUrlOverride`（默认空，发布包不受影响）；测试环境已清理、设备装回正式配置包（`source` 恢复为 GitHub 加速源） |
| 2026-09-25 | 建立自有发布闭环（见上节）：更新源改指自有仓库、新建 30 年发布签名、versionCode 7 / 1.7.0、`build-release.ps1` 产出三份产物并通过签名与 ABI 校验；修正 `update.source` 会被 CJS 插件等链路覆盖的问题（改为按线程记录，失败也留痕）；设备侧确认「清单未上传」时 `reason=HTTP 404` 且来源可见 |
| 2026-09-25 | 作者上传 vc7 后设备侧验证通过：`state=upToDate`，`source` 为经加速器读到的自有 release 清单（**1.1 s**），自有发布闭环打通；另构建 vc8 演示包（`.codex-tmp/release-v8/`）用于演示「自动下载 + 安装提示」；给 `build-release.ps1` 增加 `-VersionCode` 参数 |
| 2026-09-25 | **真实发版端到端验证通过**（作者发布 1.7.1/vc8）：启动检查 → 静默下载 100% → 安装提示 → 系统安装器 → 升级后 `versionCode=8` + `upToDate`；同时发现「发现新版本 / 已下载 / 系统安装器」三层窗口叠加的打扰问题，建议去掉「发现新版本」确认框（待作者确认） |
| 2026-09-25 | 按作者拍板实施「只打扰一次」：去掉「发现新版本」确认框（发现即静默下载，仅下载完成后提示一次安装），并删除随之失效的手动下载路径、进度框字段与 4 处字符串资源；复测（本地假清单）：单对话框、背景无叠加、点「稍后」后不再打扰（截图核对）；单测 24/24 全绿；设备已装回线上 vc8，`upToDate`、来源为自有仓库清单 |
| 2026-09-25 | 「只打扰一次」的 review 收口（P0/P1/P2，见上表 9~11）：`onCreate` 检查改回 `CHECK_INTERVAL_MS`；安装提示「稍后」改为 24 h 持久化节流（`UpdatePromptPolicy` + 6 例单测，总数 30）；`update` 块新增 `remoteNotes` 并在控制页维护页展示。设备实测：重建 Activity 后日志两次 `Update check skipped: interval not reached` 且 `lastCheckAt` 不变；点「稍后」后 `update_install_snooze_version=99` 落盘、杀进程重启不再弹（截图核对为纯播放画面）；测试痕迹（prefs 键、假清单服务、reverse、截图）已清理，设备装回 vc8（`upToDate`） |

## 自有发布闭环（2026-09-25 建立）

作者确认走「自己的发布闭环」，因此：

- **更新源改为本项目自己的 Release**：`AutoUpdater.VERSION_URL` 与 `build.gradle` 的 `releaseBase` 均指向
  `https://github.com/CJXkeep/NativeWasmTv/releases/latest/download/`（不再跟随上游 `buhanzhe`）；
- **发布签名**：`.signing/iptv-release.jks`（JKS / RSA 2048 / 有效期 30 年，口令在 `.signing/keystore-info.properties`）。
  该目录已被 `.gitignore` 忽略；**必须离线备份**——签名一旦更换，所有已安装设备都无法覆盖升级，只能卸载重装。
  证书 SHA-256：`58d09442b8fedae65899164092ecd1e26523446a57e7083b27c3257de0e3520b`；
- **版本号**：`versionCode` 6 → **7**、`versionName` 1.6.0 → **1.7.0**（每次发版必须递增 versionCode）；
- **构建**（本机需先把 `GRADLE_USER_HOME` 指向 JDK8 沙箱，否则默认 `~/.gradle` 里的 JDK21 会让 Gradle 4.4 直接失败）：

  ```powershell
  $env:GRADLE_USER_HOME='d:\WorkStation\TVNY\.codex-tmp\gradle-home-jdk8'
  $env:JAVA_HOME='D:\SoftWareTools\Java\jdk8u181'
  .\scripts\build-release.ps1 -ReleaseNotes '更新说明'                    # 用 build.gradle 默认 versionCode
  .\scripts\build-release.ps1 -VersionCode 8 -ReleaseNotes '更新说明'     # 指定版本号（发版必须递增）
  .\scripts\build-release.ps1 -VersionCode 8 -OutputDirectory .\.codex-tmp\release-v8   # 产物另存，便于留存旧包
  ```

- **产物**（三份都要上传到同一个 Release，并标为 latest）：

  | 文件 | 位置 | 说明 |
  | --- | --- | --- |
  | `nTv.apk` | `app/build/outputs/apk/` | armeabi-v7a（覆盖 Android 4.x） |
  | `nTv64.apk` | `app/build/outputs/apk/` | arm64-v8a |
  | `version.json` | 仓库根目录 | 更新清单（含 sha256，App 用它判断版本并校验包） |

- **首次发布前的设备侧状态**（清单尚未上传）：`state=error`、`reason=HTTP 404`、
  `source=…/releases/latest/download/version.json…（HTTP 404）`——失败原因与尝试过的地址都可读。

**注意**：从上游正式版（buhanzhe 签名）切到本签名需要**卸载重装一次**；之后同一签名的版本才能正常覆盖升级。

## 已决策（2026-09-25）

| # | 决策点 | 结论 | 对设计的影响 |
| --- | --- | --- | --- |
| 1 | 接收端 | **钉钉自定义群机器人**（Webhook 默认开通，无需申请/授权，个人群即可创建） | 决策 7 适配器收敛为钉钉 + 通用 JSON 兜底；地址在控制页配置，**不内置进 APK**（可选注入参数 `-PdiagnosticWebhook=` / `-PdiagnosticSecret=`，仅自用且被 release 守卫拦截） |
| 2 | TV 终态「长按 OK 反馈问题」 | **做** | A7 进入必做项；触发点两处（遥控器长按 + 网页按钮） |
| 3 | 故障后自动发送 | **不做** | 理念「不做统计上报」无需修订；发送始终由用户主动触发 |
| 4 | 远程触发下载（B3） | **不做** | 更新卡片只做「检查 + 展示」；`POST /api/update/download` 不实现；原 review 守卫项随 B3 归档 |

## Review 修正记录（2026-09-25）

对照代码逐条复核后的结论（明细已就地写入各决策与工作项，此处只做索引）：

| # | 发现 | 处置 |
| --- | --- | --- |
| 1 | 崩溃/诊断截断方向写反（框架帧不应优先） | 决策 3 改为「异常类型与消息优先」 |
| 2 | 钉钉失败常表现为 HTTP 200 + `errcode != 0`，只判 HTTP 会把失败当成功 | 决策 7 增加业务码解析与 `errmsg` 展示 |
| 3 | 长按 OK 需 `startTracking`，且长按后抬起会误触短按（弹出频道列表） | 决策 8 写入三个实现坑；验收增加「按键回归」项 |
| 4 | 发送若同步等待 8 s × 2 通道，会占满控制页 4 个 worker | 决策 7 改为接口立即返回 + 状态轮询 |
| 5 | 内存 10 条与落盘 50 条两份数据职责不清 | 决策 2 明确：内存仅供崩溃快照，落盘是唯一读取源，写入走单后台线程 |
| 6 | `downloadUpdate()` 没有「已在下载」守卫 | 随 B3 取消归档：无远程触发路径后不构成新风险，不改代码 |
| 7 | native 崩溃未捕获，此前未登记 | 写入「不做」清单，作为已知盲区 |
| 8 | 未写明「控制页可覆盖发送地址」的威胁模型 | 与既有暴露面一致（同网段本就可读全量诊断、可改设置），不新增，接受 |

第二轮 review（2026-09-25，针对「去掉发现框」这次改动）：

| # | 发现 | 处置 |
| --- | --- | --- |
| 9 | `onCreate` 里 `checkForUpdatesIfDue(0L)` 等于「每次 Activity 重建都重查」；重建后新实例的稍后记录归零 → 重复弹安装提示 | 改用 `CHECK_INTERVAL_MS`（进程首次启动 `lastCheckAt == 0` 本就必检）；实测重建后只打 `Update check skipped: interval not reached` |
| 10 | 删掉 24 h 提示节流后，安装提示只剩内存抑制 → 每次进程重启（电视开关机）都会再问一遍 | 新增 `UpdatePromptPolicy`（纯函数）+ `update_install_snooze_*` prefs：只有用户点过「稍后」才静默 24 h，跨重启生效；补 6 例单测 |
| 11 | `releaseNotes` 成了死字段（唯一展示处「发现新版本」已删）→ 用户升级前看不到更新内容 | `statusJson` 增加 `remoteNotes`（压单行、限 300 字符），控制页维护页新增「更新说明」行 |

**无未决项**：原未决的 B3 已拍板取消（见「已决策」第 4 项）。

## 设备实测记录（2026-09-25，MuMu arm64 / Android 15）

**造坏台的正确姿势**（避免动模拟器网络）：

```bash
adb root
adb shell 'iptables -I OUTPUT -m owner --uid-owner <应用uid> ! -d 127.0.0.1 -j DROP'
# 恢复
adb shell 'iptables -D OUTPUT 1'
```

不要用 `svc wifi disable`：它会连带切断模拟器 vnet 与 adb，冷启动也不能恢复，只能重启 MuMu 客户端（本次已踩）。
另注：模拟器重启后 MuMu 的主机端口转发会失效，改用 `adb forward tcp:19966 tcp:9966` 即可稳定访问控制页。

| 验证项 | 结果 |
| --- | --- |
| 控制页新接口 | `/api/state` 出现 `update` / `diagnostics` 块；`/api/diagnostics` 返回设备标签（`23127PN0CC · .15`）、ABI、失败列表、崩溃标记、发送通道状态 |
| 更新检查四态 | `POST /api/update/check` 立即返回 `checking`；之后转 `upToDate`，`source` 显示「首选命中 · 390 ms」 |
| 失败链埋点与脱敏 | 阻断后依次记录 `source` → `channel` → `terminal`；线路地址脱敏为 `webview://https://yangshipin.cn/tv/home…`（query 已隐去） |
| 坏台终态 | 线路耗尽 + 连跳 2 次后 `state=failed`、`stickyError=true`；屏幕提示「… ↑↓ 换台 / OK 打开列表（长按 OK 反馈问题）」（截图核对） |
| 发送链路 | 控制页配置接收地址（作者工具）→ `POST /api/diagnostics/send` 立即返回 `sending`，随后 `failed` + 「Failed to connect to /127.0.0.1:9968」（失败原因可读） |
| 长按 OK 反馈 | 终态下长按 OK → `sender.lastAttemptAt` 被更新且状态转 `failed`，遥控器触发链路打通 |
| 短按 OK 回归 | 终态下短按 OK 仍打开频道列表（截图核对） |
| 计数复位 | 恢复网络换台后 `consecutiveSkips` 由 2 归 0、`stickyError` 复位、`state=playing` |

### 自动更新全链路（本地假清单，2026-09-25）

真实发版依赖作者的**发布签名与 GitHub 上传权限**，因此用本地假清单做等价验证（不触碰线上 release）：

1. 本机起临时 HTTP 服务（测试后已删除）：`version.json`（versionCode=99 / 9.9.0-test）+ 一份真实 APK + 对应 sha256；
2. `adb reverse tcp:8099 tcp:8099`，让设备经回环访问主机；
3. 用 `-PupdateManifestUrlOverride=http://127.0.0.1:8099/version.json` 构建测试包
   （该开关是编译期参数、默认空；仅在非空时生效，且此时才放宽清单/APK 地址校验，发布包不受影响）。

| 验证项 | 结果 |
| --- | --- |
| 发现新版本 | `state=available`、`remoteVersion=9.9.0-test(99)`、`source` 显示本地清单 |
| 静默自动下载 | `downloadState` 由 `downloading` → `ready`、`downloadProgress=100`；全程无进度框，电视继续播放 |
| 安装提示 | 「新版本 9.9.0-test 已下载 → 是否现在安装」（截图核对，仅下载完成后出现一次） |
| 立即安装 | 点「立即安装」→ 系统安装器「要更新此应用吗？」（截图核对） |
| 一次打扰（2026-09-25 修订后复测） | 全程只出现「已下载，是否安装」**一个**对话框，背景无叠加提示（截图核对） |
| 稍后抑制（本次修复） | 点「稍后」后再触发两次检查，不再弹安装提示（截图核对为纯播放画面） |

**测试中发现并修复**：安装提示原本没有抑制——点「稍后」后每次检查都会再弹一次，属打扰级缺陷。
修复为**内存态抑制**（`installPromptDismissedVersion`）：点稍后当次运行内不再自动弹，应用重启或下次发版仍会提示一次。

### 真实发版端到端（2026-09-25，vc7 → vc8）

作者发布 1.7.1（versionCode 8）后，在 MuMu（arm64，原 vc7）上完整验证：

| 步骤 | 观测 |
| --- | --- |
| 启动自动检查 | `state=available`、`remoteVersionCode=8`；清单经加速器读到（1.1 s） |
| 静默自动下载 | 约 14 s 内 `downloadState=downloading → ready`、进度 100%，期间电视继续播放 |
| 安装提示 | 「新版本 1.7.0 已下载 → 是否现在安装」（截图核对） |
| 手动路径（旧行为，修订后已移除） | 「发现新版本」→「立即更新」→ 进度框 →（缓存命中）→ 系统安装器 |
| 系统安装 | 安装器「要更新此应用吗？」→ 确认 |
| 升级后复查 | `versionCode=8`、`state=upToDate`、`downloadState=idle`（不再重复下载） |

**修复（2026-09-25 已实施，作者拍板）**：原行为会同时叠三层窗口——「发现新版本」确认框 ＋「已下载，是否安装」＋系统安装器，
用户关掉上层后还会被下层再问一次。现改为：**发现新版本即静默下载，只在下载完成后提示一次「是否安装」**；
`AutoUpdater` 同时删除了随之失效的手动下载路径（`downloadUpdate` 的 `silent=false` 分支）、进度框字段与
`update_available_*` / `update_now` / `update_downloading` / `update_download_failed` 四处字符串资源。
复测（本地假清单，vc9 → 假清单 99）：只出现一个对话框、背景无叠加；点「稍后」后再次检查不弹（截图核对）。

**顺带发现（登记待办，本轮不修）**：完全断网且当前频道是网页线路时，失败链会在 stall 恢复上打转——
`recoverStalledPlayback` 的 `stallRecoveryRequestId` 去重使「同一次播放请求」的后续失败不再进入恢复流程，
最终 `state` 停在 `idle` 且没有终态提示。该场景落在 I3 终态设计之外，但正命中原则 4「一切失败必须有终态」；
修复要改播放主链，不属 I4 范围，建议与 I5 拆分同批处理。

**凭据负责方式（2026-09-25 修订）**：作者无需把地址交给构建 —— 在控制页「诊断与更新 → 高级：接收地址」里填一次即可（已实测可用）；
只有「家人零配置」场景才考虑本地注入自用版，那种包**不要上传公开 release**。

## 附：I5（拆分 `MainActivity`）的入口条件

沿用 I3 文末的拆分入口条件（原编号 I4，现顺延为 I5），其中第三条更新为本迭代：

1. `gradlew test` 与 `scripts/regression.ps1 -WithPlayback` 全绿，且至少完成一次「改坏了能红」的负向验证（I3 已满足）
2. I3 的 A 组已合并并通过设备侧实测（已满足）
3. **本迭代 I4 已合并并完成设备侧实测**（避免两轮改动叠加后无法归因；I4 只碰埋点、发送与接口转发，不碰播放主链）
4. 先抽「播放状态持有对象」，再按 `SourceManager` → `PlaybackController` → `ControlPageApi` 分批搬移，每批一次回归
