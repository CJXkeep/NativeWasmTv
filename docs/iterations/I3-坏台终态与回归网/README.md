# 迭代 I3 · 坏台终态与回归网

- 状态：**已完成**（2026-09-25 收尾；A 组通过、B 组除 B6 完成、C1/C2 完成、D 组结论已出，见[验收报告](./验收报告.md)）
- 依据：[`docs/开发理念.md`](../../开发理念.md)（原则 3 / 4 / 6 / 7；北极星「死台无限等待 0」「坏台自动离开耗时 ≤ 20 s」）
- 上位文档：[`I2 验收报告`](../I2-功能收敛与冗余清理/验收报告.md)（D 组未达标与「下轮改进」）

## 迭代目标

两件事，顺序固定——先把失败变成终态，再给后续大改动铺一张能立刻变红的网。

1. **把「播不出来」变成终态**：内置 65 个频道里 **41 个（63%）只有一条线路**，当前实现是「3 秒提示 → 黑屏或冻帧 → 无提示、无动作、无上限」。本轮给失败定义确定的行为：自动离开 → 连续上限 → 常驻提示。
2. **补最小的回归网**：I2 的 D 组停在 6023 行（目标 ≤ 4300），根因是没有自动化保护。本轮把可自动化的部分建起来（内置源契约 + 纯逻辑单测 + 断言脚本归位），让后续拆分（I5）有「改坏立刻红」的底线。

**为什么本轮不拆 `MainActivity`**：拆分要动的 `ControlPageApi` / `SourceManager` / `PlaybackController`，正是 A 组要改的区域（`onError` / `recoverStalledPlayback` / `buildControlState`）。叠加改动后回归无法归因。拆分顺延为 I5（I4 编号已用于新增迭代「远程诊断与更新分发」，该迭代只碰埋点与接口转发），入口条件见文末。

## 现状盘点（设计依据）

行号口径：2026-09-25 工作区版本，`MainActivity.java` 6034 行（含空行；I2 文档记 6023，口径待统一，见 C2）。以方法名为准，行号仅供快速定位。

### 失败链路现状

| 阶段 | 现状 | 位置 |
| --- | --- | --- |
| 看门狗 | custom 源 5 s / 首帧 10 s / buffering 10 s / stall 10 s | `CUSTOM_SOURCE_TIMEOUT_MS`、`VIDEO_RENDER_START_TIMEOUT_MS`、`PLAYBACK_BUFFERING_RECOVERY_MS`、`PLAYBACK_STALL_RECOVERY_MS` |
| 线路级切换 | `switchCustomSource(offset, automatic, reason)`，上限 `AUTO_SWITCH_MAX_ATTEMPTS = 4` | `switchCustomSource` |
| 播放器错误 | 已渲染过 → 1 s 后进恢复流程；custom 源未渲染 → 立即换线；其他源本地重试 2 次（500 ms）→ 只提示 | `setOnErrorListener` |
| 三级恢复 | 重连 ≤ 5 次 → 换线路 → 跳下一频道 | `recoverStalledPlayback` |
| 提示 | `showChannelBar` 3 s 自动隐藏，无 sticky 模式 | `showChannelBar`、`CHANNEL_BAR_TIMEOUT_MS = 3000` |
| 状态上报 | `buildControlState` 只有 `video.width/height` | `buildControlState` |

### 缺失（本轮要补）

| # | 缺失 | 后果 |
| --- | --- | --- |
| 1 | 失败终态 | 线路耗尽后 `hideLoading()` → 黑屏或冻帧，提示 3 s 后消失，用户无从判断 |
| 2 | 跨频道连跳防护 | `recoverStalledPlayback` 跳台后 `switchChannel` 无条件 `resetPlaybackRecoveryState()`，失败链可循环 |
| 3 | 冻帧清除 | I2 实测：403 后画面停在上一频道最后一帧（间隔 12 s 截图 MD5 相同） |
| 4 | 失败可观测 | 「上次失败原因 / 时间」无字段，控制页看不到播放态 |
| 5 | 自动化底线 | 无 `test` sourceSet、无 junit；`tests/java/.../CjsSourceTest.java` 未参与编译，且其所需输入 `cctv.m3u` / `cmg.m3u` / `gxtv.m3u` **在仓库中不存在**（当前不可运行） |

## 迭代范围

**做**：A 组失败终态、B 组最小回归网、C 组低风险整理、D 组事实澄清（只出结论）。

**不做**：拆 `MainActivity`、CJS/Ku9 双体系合并、HLS 代理与解码链路改动、设置存储两套键名重构、
新增线路与源运维、引入需要维护的服务端。**本迭代不新增任何用户可见开关**（控制页保持 11 项）。

## 关键设计决策

### 决策 1：失败终态 = 三级阶梯，全自动决策（无开关）

| 级别 | 条件 | 行为 | 上限 |
| --- | --- | --- | --- |
| L1 线路级 | 本频道仍有未试线路 | 换下一条线路（复用现有 `switchCustomSource`） | `AUTO_SWITCH_MAX_ATTEMPTS = 4` 或线路总数 |
| L2 频道级 | 本频道线路耗尽且自动切换已启用 | 跳到下一频道，提示「XX 无可用线路，已跳到 YY」 | **连续跳过 ≤ 2**（新增 `SKIP_CHANNEL_MAX_CONSECUTIVE = 2`） |
| L3 终态 | 连跳达上限，或自动切换已关闭 | **停止**，显示常驻文案「XX 暂时无法播放。↑↓ 换台 / OK 打开频道列表」 | — |

- **复位条件**：`consecutiveSkips` **只在新频道首帧渲染成功后清零**（沿 `videoRenderingStarted` 的真值路径），
  不得在 `switchChannel` 中无条件清零——否则与现状一样会循环。
- **为什么上限是 2**：源已按「画质优先」筛过（I1），大面积同时失效是小概率；连续 2 个都失败时，
  继续自动跳台对家庭用户的收益低于「停下来让人看见」，同时避免跳台风暴。数值写死在常量里，不给开关（原则 3）。

### 决策 2：终态提示复用 `channel_bar`，不新增覆盖层

给 `showChannelBar` 增加仅供终态使用的 sticky 标志：为真时 `hideChannelBar` 不生效，直到起播成功或用户换台。
理由：视觉与现有提示一致；新增覆盖层要额外处理与 loading、手势遮罩 `channel_switch_blackout` 的互斥，
成本大于收益（纪律 6：不为单一实现引入抽象）。

### 决策 3：冻帧清除是进入终态的前置动作

进入 L3 前必须让播放层离开「上一帧」：`releasePlayer()` + `resetVideoLayout()` + `PlaybackSupport.resetPlaybackLayerImmediately()`。
验收用「间隔 12 s 两张截图 MD5 不同」判别，而不是靠肉眼看黑屏。

### 决策 4：可观测性用只读 state，不新增开关

`buildControlState` 的 `video` 块扩展为 `playback` 块：
`{state: playing|loading|failed|idle, reason, lastErrorAt, attempt, consecutiveSkips}`；
控制页播放区显示失败原因。只读、不进设置项（原则 6：先让时间可观测，再优化）。

### 决策 5：回归网落在标准位置 `app/src/test/java`（执行中修正）

原计划用 `sourceSets` 指向模块外的 `tests/java`；执行时改为标准位置 `app/src/test/java`（零配置、IDE 原生识别），
并删除原 `tests/java`（其中的 `CjsSourceTest` 已改造为 JUnit 用例）。测试与主代码同包 `xiao.bu.tv`，
可直接覆盖 package-private 逻辑（这是这张网能覆盖纯逻辑面的原因）。

**补充（执行中发现）**：`builtin_channels.txt` 必须注册为 `Test` 任务输入——
`tasks.withType(Test) { inputs.file(...) }`，否则改坏源时测试仍显示 `UP-TO-DATE`，负向验证会假绿。

## 工作项与任务

### A. 失败终态（核心）

- [x] A1 新增状态与常量：`consecutiveChannelSkips`、`lastFailureReason` / `lastFailureAt` / `stickyStatus`；
  上限常量收在 `PlaybackFailurePolicy.SKIP_CHANNEL_MAX_CONSECUTIVE`
- [x] A2 `setOnErrorListener` 补终态：线路耗尽 / 本地重试 2 次仍失败 → 走 `handleChannelFailure`
- [x] A3 `switchCustomSource` 的单线路、线路耗尽、自动切换关闭三个收尾统一接终态入口
- [x] A4 `recoverStalledPlayback` 的跳台改走 `skipToNextChannel`，与 A2/A3 共用同一计数
- [x] A5 连跳计数复位点从 `switchChannel` 移到首帧渲染成功路径
- [x] A6 进入终态前清冻帧：**`releasePlayer()` 不够**，设备实测发现残留帧，补 `DirectVideoView.clearLastFrame()`
- [x] A7 `channel_bar` 增加 sticky 终态（决策 2）
- [x] A8 `buildControlState` 增 `playback` 块 + `control.html` 只读展示（决策 4）
- [ ] A9（可选，需实测）上游关闭直播流（当前无 `setOnCompletionListener`）纳入失败链——**本轮未纳入**，留待真机实测

### B. 回归网（后续拆分 I5 的前置）

- [x] B1 junit4 + `test` 源集；**实际落在标准位置 `app/src/test/java`**（决策 5 已修正），并删除原 `tests/java`
- [x] B2 `CjsSourceTest` 改为 JUnit，删掉依赖仓库中不存在的 `cctv.m3u` / `cmg.m3u` / `gxtv.m3u` 的断言（纪律 5）
- [x] B3 新增纯逻辑用例：`PlaybackFailurePolicyTest`(3)、`BuiltinChannelContractTest`(4)、`CjsSourceTest`(4)，共 11 个
- [x] B4 负向验证：注入黑名单线路 → `excludesBlacklistedAddresses` FAILED；
  **顺带发现并修复「assets 不是测试输入」的缺陷**（改坏源时测试曾 `UP-TO-DATE` 假绿）
- [x] B5 脚本归位：4 个脚本 → `scripts/`，并修正 `RepoRoot` / `Playlist` / `OutDir` 基准（`regression.ps1` 28/28 验证）
- [x] B6 发布门禁接入 `check-sources.ps1`：`deadChannels > 0` 时 `exit 1`；
  实测正常源 exit 0、构造「全死且无 webview 兜底」的频道 exit 1（负向验证）

### C. 低风险整理（原则 7）

- [x] C1 删除 `debug { assets.srcDirs = [rootProject.file('docs')] }`；核实无代码引用 `docs/`，debug APK **-248 KB**
- [x] C2 文档漂移修正：I2 README 状态「待启动」→「已完成」；`MainActivity` 行数口径统一（含空行）
- [x] C3 `cctv-filtered.m3u` 保留（它是 `merge-builtin-channels.ps1` 的默认输入，删除会破坏脚本）；
  `test1-resolved.txt` 已删除（全仓无引用，属调试产物，需要时可从 git 历史恢复）

### D. 事实澄清（只出结论，不改代码）

- [x] D1 **结论：arm32 可以发布**。`app/src/main/libs/armeabi-v7a/` 下 5 个 `.so` 齐备，`scripts/build-release.ps1`
  同时产 `nTv.apk`(armeabi-v7a / minSdk 14) 与 `nTv64.apk`(arm64-v8a / minSdk 21) 并校验 ABI；
  I1 记录里的「仅提供 arm64 产物」是其时的本机限制，现已不成立，理念基线无需改写
- [x] D2 **结论：待作者决策**。线上 1.6.0 与 master 架构分裂的事实已确认（见[验收报告](./验收报告.md)）；
  是否发布基于 master 的版本（接受首次插件下载成本）或明确「release 线单独维护」，涉及改代码时另开迭代

## 验收标准

| 维度 | 指标 | 目标 |
| --- | --- | --- |
| 坏台终态 | 全部线路耗尽后 | 常驻文案（12 s 后仍在，实测）；无残留帧（终态画面采样为纯黑 + 间隔 12 s 截图稳定） |
| 坏台自动离开 | 单线路坏台 → 跳到下一频道起播 | ≤ 20 s（北极星） |
| 连跳防护 | 构造连续 ≥ 3 个坏台 | 跳过 ≤ 2 后停止并进常驻终态，不无限跳 |
| 计数复位 | 坏台 → 好台 | 起播成功后计数复位（再次遇到坏台仍能自动跳过） |
| 播放不劣化 | 正常频道换台 12 次 | 失败 0 / 12；首帧 ≤ 2.3 s；1080p（I2 基线） |
| 设置项 | 控制页可配置项 | 仍为 11（不新增） |
| 回归网 | `gradlew test` | 全绿；负向用例（改坏内置源）必须红 |
| 断言脚本 | `scripts/regression.ps1` | 三层（S / D / P）仍全绿 |
| 清理完整性 | debug APK 内 `assets/` | 仅 `builtin_channels.txt` 与 `licenses` |
| 代码规模 | `MainActivity.java` | 净增 ≤ 150 行（终态逻辑收敛在一个区块） |

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| 自动跳过误判（上游临时抖动就跳台） | 保留「先换线路、后跳台」顺序；跳台前已有最多 4 次线路尝试；上限 2 |
| 跳台风暴（源大面积失效） | 连跳上限 2，达上限即停在常驻终态，不再自动动作 |
| 复位点改错导致不跳或狂跳 | 把复位判定抽成纯函数并由 B3 单测覆盖 |
| sticky 与 loading 状态互斥出错 | 复用 `loadingActive` 语义；换台与起播成功必清 sticky |
| 老设备（API 14）回归不足 | 本轮不引入新 API，终态只用现有 View / Handler；真机项标注「未验证」 |
| AGP 3.1.4 / Gradle 4.4 加 junit 失败 | B1 先做可行性验证（空用例），再写正式用例 |
| `CjsSourceTest` 输入来源不明 | B2 先决策；确定不了就删除该用例，不留半截 |
| 删 `assets.srcDirs` 影响 debug 控制页 | 已核实控制页来自 `res/raw/control.html`；删除后跑 `GET /` 验证并记录 |
| 与后续拆分叠加导致回归不可归因 | 拆分明确不在本轮范围；I5 入口条件见文末 |

## 产出物

- 失败终态代码 + `channel_bar` sticky 终态 + 控制页 `playback` 区块
- `app/src/test` 回归网（用例清单 + 一次负向验证记录）
- 归位后的 `scripts/`，以及接入 `deadChannels` 的发布检查
- 本目录 `reports/`：坏台复现与终态实测（截图 MD5）、单测运行记录、清理前后对照

## 文档约定

与 I1 / I2 相同：一个迭代两份文档，不另外造空壳。

| 文档 | 用途 | 时点 |
| --- | --- | --- |
| `README.md`（本文件） | 迭代计划 | 开始时定稿，执行中更新勾选与进度 |
| [`验收报告.md`](./验收报告.md) | 验收结论 + 对照数据 + 回顾 | 迭代收尾时创建 |

## 进度记录

| 日期 | 事件 |
| --- | --- |
| 2026-09-25 | 迭代建立，A ~ D 组待推进 |
| 2026-09-25 | B1~B4 完成：junit 接入、`CjsSourceTest` 改造并入 `app/src/test/java`（11 用例全绿）；负向验证发现「assets 不是测试输入」并修复 |
| 2026-09-25 | A1~A8 完成：终态三级阶梯、连跳上限 2、首帧才复位、sticky 提示、`state.playback` + 控制页只读展示 |
| 2026-09-25 | 设备侧实测（MuMu 1）：3 个连续坏台 → **3.1 s** 进终态、跳过 2 次后停、画面全黑、文案常驻；复位与 1080p 正常 → [`reports/`](./reports/) |
| 2026-09-25 | B5/C1/C2 完成：4 个脚本归位 `scripts/` 并修正路径基准（`regression.ps1` 28/28）；移除 docs 打包，debug APK **-248 KB** |
| 2026-09-25 | 收尾：验收报告（A 通过、B6 未做、C3 部分、D 组结论已出） |
| 2026-09-25 | Review 修复 3 项：决策链收敛到 UI 线程、主动换台重置连跳计数（新增 `switchChannelInternal`）、终态幂等保护；重新构建并设备复测通过 |
| 2026-09-25 | 补齐遗留项：12 次换台压测（12/12、全部 1080p、中位 1.82 s）、B6 发布门禁（含负向验证）、C3 收尾 |
| 2026-09-25 | 拆分 `MainActivity` 顺延为 I5：I4 编号用于新增迭代「远程诊断与更新分发」（只碰埋点与接口转发），下方入口条件顺延 |

## 附：I5（拆分 `MainActivity`）的入口条件（原计划为 I4）

1. `gradlew test` 与 `scripts/regression.ps1 -WithPlayback` 全绿，且至少完成一次「改坏了能红」的负向验证
2. I3 的 A 组已合并并通过设备侧实测（避免两轮改动叠加后无法归因）
3. 先抽「播放状态持有对象」，把 200+ 实例字段里与播放相关的部分集中，再按 `SourceManager` → `PlaybackController` → `ControlPageApi` 分批搬移，每批一次回归；
   仍不达成就继续拆，不为了指标一次性搬 1700 行
