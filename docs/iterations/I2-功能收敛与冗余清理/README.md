# 迭代 I2 · 功能收敛与冗余清理

- 状态：已完成（2026-09-25 收尾，A / B / C 通过、D 组部分未达标；见[验收报告](./验收报告.md)）
- 依据：[`docs/开发理念.md`](../../开发理念.md)
- 上位文档：[`NativeWasmTv-v5适配与源质量评估.md`](../../../NativeWasmTv-v5适配与源质量评估.md)

## 迭代目标

按开发理念的"原则 3（自动决策优于开关）、原则 5（预算投成功率）、原则 7（做减法）"，
把与"看电视"主业无关的功能、以及只为兼容老设备存在的开关清掉，让设置项与代码规模回到可控范围。

## 迭代范围

**做**：清理边缘功能、收敛设置项、拆分 `MainActivity` 上帝类、同步文档与控制页入口。

**不做**：删除核心播放链路、改动 HLS 代理与解码逻辑、改变频道与源体系；
不动 **Ku9 体系**与 **Android 4.x 兼容栈**（旧 TLS、QuickJS 双引擎、MStar 特判等）——它们是既定支持范围。

## 背景（现状盘点）

| 项 | 现状 |
| --- | --- |
| Java 规模 | 41 个类，约 20,350 行；`MainActivity.java` **7183 行（35%）** |
| 用户设置项 | 约 40 项（理念建议 ≤ 12） |
| 控制页 | `control.html` 109 KB，承载全部设置入口 |
| 双插件体系 | CJS（`CjsPluginRuntime` 574 + `CjsSiteResolver` 446）+ Ku9（6 个类 ≈ 1170），功能重叠 |
| 网页源体系 | `WebSourceView` 929 + `YangshipinWebResolver` 907，`webview://` 频道依赖它（当前卫视画质来源） |
| 边缘功能 | 飞鼠（214 + `flymouse.html` 56 KB）、手机录制（`video-recorder.html` 28 KB）、系统信息页（620） |
| 兼容包袱 | `LegacyTlsSocket` 251 + `native/tls`(mbedtls)，仅为 API 14/15；MStar 特判、`surface_mode`、`h264_sps`、`rtsp_transport` 等手动开关 |
| 历史遗留 | `last_*_v2`、`harddecode_ab_migration_v1`、`central_groups_merged_v1` 等一次性迁移键 |

## 工作项与任务

### A. 第一期清理（低风险）· **已完成**

- [x] 删手机飞鼠：`FlyMouseCursorView`(214)、`MainActivity` 的指针 API 与 8 个 dispatch 方法、`control.html` 飞鼠页与全部指针/陀螺仪 JS 及 CSS、`docs/flymouse.html`(56 KB)、`FLY_MOUSE_ENABLED`、`/api/pointer` 路由与接口、`activity_main.xml` 覆盖层节点
- [x] 删手机端录制：`docs/video-recorder.html`、`docs/mp4-finalizer.js`、`LocalControlServer` 录制路由与接口、`MainActivity.handleRecordingResource` 与 `isDirectThirdPartyRecordingSource`、`HlsProxyServer.fetchForRecording` 与 `recordingTokens`、control.html 入口
- [x] 删系统信息页：`SystemInfoProvider`(620) + `state.system` 字段 + control.html 系统信息页；**保留该页里的 CJS 插件管理块**，页面改为「插件管理」
- [x] GitHub 代理：**经调研不删**——`AutoUpdater`（version.json + APK）与 `CjsPluginRuntime`（catalog/站点下载）都依赖它，直连 GitHub 在大陆网络会失败；"改为失败回退"归入后续迭代
- [x] 迁移分支：只删可安全清理的 `migrateFavoriteGroupIndex()`（空操作）与 `player_backend` 清理语句；**纠正**：`last_group_index_v2` / `last_channel_index_v2` / `last_channel_snapshot_v2` 是**当前生效的存储键**（`_v2` 只是命名），不是历史残留，必须保留
- [x] 保留 `recording.width/height` 的可观测性：改为新的 `state.video` 字段（画质探测脚本同步更新）

### B. 第二期清理

**已定决策（2026-09-24）**：继续支持 **Android 4.x**，且现有源**依赖 Ku9**。
因此 B1、B2 保留不动，Ku9 体系后续继续维护——这两项不属于本迭代的清理范围。

| # | 项 | 影响面 | 状态 |
| --- | --- | --- | --- |
| B1 | Ku9 脚本体系（`Ku9*` 六个类 ≈ 1170 行 + `/api/ku9/script/upload`） | 与 CJS 功能重叠，但现有源依赖 | **保留**（后续继续维护） |
| B2 | 旧设备 TLS 栈（`LegacyTlsSocket` + `native/tls`）及 `TlsCompat` 的 trust-all | Android 4.x 必需 | **保留** |
| B3 | 删网页源嗅探（`MainActivity` 嗅探簇 + 控制页 UI + `state.sniffedResources` + `playSniffed` 动作 + `WEB_VIEW_AUTO_PLAY_SNIFFED`） | `webview://` 频道走 WebView 播放，不依赖嗅探 | ✅ 已完成 |
| B4 | UI 缩放 5 档（含 200%）收敛为 2 档（自动 / 大字号） | 影响极端设备适配 | ✅ 已完成 |
| B5 | 解码/显示手动开关改为自动决策：`hardwareDecoder`、`surface_mode`、`h264_sps`、`rtsp_transport` | 老芯片兼容仍需该能力，但不能裸给用户选 | ✅ 已完成 |

### C. 设置项收敛（26 → 11）· **已完成**

- [x] 盘点控制页全部设置项（26 项）
- [x] 删除 11 项：`reverseKeys`、`hardwareDecoder`、`surfaceMode`、`rtspTransport`、`h264SpsCompatibility`、`clockLocation`、`dateTimeFormat`、`autoUpdateChannelList`、`webViewResolution`、`webViewLoadImages`、`webViewUserAgent`
- [x] 合并 3 项（调试信息 / 网速 / 日期时间）为「信息叠加」单一下拉
- [x] 保留 11 项：开机自启、DNS、解码方式、画面比例、分辨率、界面大小、直播稳定性、自动切换备用线路、屏幕信息、频道源与节目单、站点插件目录
- [x] 同步删除 `MainActivity` 中对应的 11 个 `settings` 分支（不再接受这些设置）
- [x] 默认值调整：`webViewResolution` 固定 1080P；`uiScaleMode` 旧档位统一归到「大字号」
- [x] 顺带清理飞鼠遗留的指针变量与整套 legacy 下拉选择器（410 行 JS）

### D. 架构瘦身（`MainActivity` 拆分，不改功能）· **第一批已完成**

已完成（`MainActivity` 7183 → **6023** 行，-16%；新增 6 个职责单一的类）：

- [x] `SourceTags`（152 行）：源类型判定与网页/央视频参数解析
- [x] `TextFormats`（178 行）：文本解析与码率/时间格式化
- [x] `ChannelKeys`（79 行）：频道查找与收藏标识
- [x] `PlaybackSupport`（116 行）：CMG 探测与播放层重置
- [x] `SystemCpu`（63 行）：CPU 使用率采集
- [x] `UiMetrics`（52 行）：UI 度量与手势换算

未完成（目标 ≤ 4300 行未达成，差距 1723 行）：

- [ ] `ControlPageApi`：`handleWebSettings`(237) + `buildControlState`(101) + `startManagementServer`(90) + 控制页相关小方法
- [ ] `SourceManager`：`resolveFallbackUrl`(102) + `resolveYangshipinUrl`(78) + `installCjsPluginAndStart`(51) 等源解析编排
- [ ] `PlaybackController`：`startResolvedPlayer`(88) + `startChannel`(86) + 播放层状态

**为什么没一次拆完**：剩下的大方法都直接读写 `MainActivity` 的 200+ 个实例字段（`videoView`、`webView`、`currentGroupIndex`、各类 settings 等），
搬移必须先把这些状态改成 package-private 并逐处加 `host.` 前缀；在没有单元测试、回归依赖手动跑真机的前提下，
一次性搬 1700+ 行的出错概率远高于收益。建议作为独立迭代，并先补一层"播放状态持有对象"再拆。

## 验收标准

| 维度 | 指标 | 目标 |
| --- | --- | --- |
| 设置项 | 控制页可配置项数量 | ≤ 12 |
| 代码规模 | `MainActivity.java` | 下降 ≥ 40%（≤ 4300 行） |
| 清理完整性 | 删除项在代码 / 资源 / 控制页 / 文档中的残留 | 0 |
| 播放不劣化 | 抽样首帧耗时 | 不高于 I1 基线（1.7~2.3 s） |
| 播放不劣化 | 抽样实测分辨率 | 仍为 1920x1080 |
| 功能不回退 | 换台、收藏、EPG、源管理、自动更新、崩溃自愈 | 全部正常 |

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| 清理误伤 Android 4.x 兼容能力 | 兼容栈（旧 TLS、Ku9 双引擎、MStar 特判等）明确不动；清理只针对与"看电视"无关的功能 |
| 收敛设置项时误删老设备兜底能力 | B5 走"改为自动决策"而非删除能力；老设备仍能自动回退到兼容路径 |
| 删网页源嗅探影响 `webview://` 频道 | 删除前用设备实测确认央视频频道仍能起播（嗅探与 WebView 播放是两条路径） |
| `MainActivity` 拆分引入回归 | 纯搬移、不改逻辑，分批提交；每批后用设备侧抽样脚本回归 |
| 设置项收敛后用户找不到入口 | 保留项集中在控制页首屏；被删项在文档中说明"已改为自动" |
| 删错东西不可恢复 | 每个工作项独立提交，便于单独回退 |

## 产出物

- 清理后的代码与控制页（含删除项清单）
- 收敛后的设置项清单
- 拆分后的 `MainActivity` 结构说明
- 本目录 `reports/`：删除前后的对照数据（行数、设置项数、抽样回归结果）

## 文档约定

与 I1 相同：一个迭代两份文档，不另外造空壳。

| 文档 | 用途 | 时点 |
| --- | --- | --- |
| `README.md`（本文件） | 迭代计划 | 开始时定稿，执行中更新勾选与进度 |
| [`验收报告.md`](./验收报告.md) | 验收结论 + 对照数据 + 回顾 | 迭代收尾 |

## 进度记录

| 日期 | 事件 |
| --- | --- |
| 2026-09-24 | 迭代建立，工作项 A~D 待推进 |
| 2026-09-24 | 决策：继续支持 Android 4.x、现有源依赖 Ku9 → B1/B2 保留，B 组收缩为 B3~B5 |
| 2026-09-24 | A 组完成：删飞鼠/录制/系统信息页 + 清理冗余；共 -1965 行 / +111 行，`MainActivity` 7183 → 6778，控制页 105 KB → 72 KB；设备回归 4 个频道全 1080p |
| 2026-09-25 | B3/B4/B5 完成：删网页源嗅探、UI 缩放收敛 2 档、解码/显示开关改自动决策 |
| 2026-09-25 | C 完成：设置项 26 → 11，控制页 72 KB → 60 KB；`MainActivity` 删 11 个 settings 分支 |
| 2026-09-25 | D 第一批完成：抽出 6 个工具类，`MainActivity` 6778 → 6023 行；设备回归 4 频道全 1080p |
| 2026-09-25 | 清理后复核（宿主侧）：内置源 35/35 可用、≥720p 100%、新失效 0 条，I1 合并的 15 条备用线路全部通过 → [`reports/`](./reports/check-builtin_channels-2026-09-25-rerun.md) |
| 2026-09-25 | 清理后复核（设备侧）：央视频道 6 个频道全起播、首帧 1.1~2.3s；3 个仅 `webview://` 的频道由 CJS CMG runtime 解析出流，未覆盖 `onStreamDiscovered` → [`reports/`](./reports/device-sample-webview-2026-09-25.md) |
| 2026-09-25 | 清理后复核（设备侧·卫视）：7 个频道 6 个 OK；**凤凰卫视**唯一线路被上游 403 拒绝、静默冻结帧（新增线路的设备侧不可用）；连续换台 12/12、持续播放 120s 无中断 → [`reports/`](./reports/device-sample-satellite-2026-09-25.md) |
