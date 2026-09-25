# 迭代 I1 · 插件 v5 适配与源提质

- 状态：**A、B、C、D 已完成**（A 有条件通过）；E 部分完成 —— 迭代主体完成，可进入收尾验收
- 验收报告：[`验收报告.md`](./验收报告.md)
- 上位文档：[`NativeWasmTv-v5适配与源质量评估.md`](../../../NativeWasmTv-v5适配与源质量评估.md)
- 原则：一个迭代一个文件夹；本迭代内的工作项统一记在本文件，不按工作项再拆目录。

## 迭代目标

完成插件协议 v5 适配的收尾，并解决用户感知的源质量问题，最终交付一个内置源已提质的 arm64 APK。

**画质约束（2026-09-24 补充，优先级最高）**：使用场景是 **75 寸电视，画质优先**。
只保留"验证到视频流且 ≥720p"的线路，宁可少而精；线路排序以画质档为第一键。
低画质源、以及播放列表可拿但分片取不到的半死源，一律不追加。

## 迭代范围

**做**：v5 收尾与文档同步、内置源合并重建、源可用性巡检机制、起播与多线路回退、v5 回归与发布清单。

**不做**：跨端（Android 4.x / 低版本 ABI）适配、在线订阅后端、EPG 改造、播放器内核替换。

## 背景

| 项 | 现状 |
| --- | --- |
| v5 适配 | 已完成并验证：`CjsPluginRuntime.java` 三处改动（`HOST_PROTOCOL` 4→5、`cjs_sites_v4`→`cjs_sites_v5`、按 ABI 收集 + `minSdk` 择优）；CCTV-1 首帧 1920x1080，15s 流量 +6.9 MB |
| 源筛选 | 已完成：679 → 去重 647 → 246 条可用线路，覆盖 64 个频道，产物 `cctv-filtered.m3u` |
| 源质量对照 | 内置 20 条官方 CDN 直连可用率 100%；聚合源同口径仅 36.7%，域名集中度 207~328 |
| 导入机制 | 已确认：订阅源存于 `management` / `playlist_sources_v1`；同名频道由 `ChannelBucket.add()` 自动合并多线路；换源前必须清 `catalogStore` |
| 缺口 | 设备侧导入实测未跑完、内置源未合并、探测脚本未入仓、无首帧耗时量化、v5 无回归用例 |

## 工作项与任务

### A. v5 收尾与内置源提质（P0）· **已完成**

- [x] 设备侧导入实测：`pm clear` → `POST /api/playlist/merge` 导入 →「筛选源」64 个频道（57 个多线路）
- [x] 抽样切台实测：内置源 17 个频道，首帧 17/17 成功、流量中位 8.8 MB
- [x] 线路净化：剔除 41 条（`auth=` 临时授权、GitHub 上的 YouTube 假源）；并修正频道名标注后缀导致的线路无法合并
- [x] **画质筛选**：246 条聚合线路全量探测画质，仅 18 条能验证到视频流；按"≥720p 且已验证"追加 14 条（11 条 ≥1080p），剔除 188 条低于阈值/未验证
- [x] **黑名单与假直播清理**（对齐 `my-tvForTest`）：剔除黑名单域名 `iill.top`、快手 `kwimgs` 录像轮播、`/video-hls/` 点播转直播、`.mp4/.mkv/.avi` 点播文件，共 43 条
- [x] 合并重建 `builtin_channels.txt`：原条目（`webview://` + 官方 CDN）保持在前，新增 1 个频道，设备侧实测 9/9 全部 1920x1080
- [x] 命名归一化：按 `tvg-id`（`CCTV-4` → `CCTV4`）与别名表匹配内置频道
- [x] 构建 `assembleArm64Debug` 并安装验证（首帧 / 流量）
- [x] 同步 `docs/cjs-plugin.md`：protocol 4 → 5，补充 native profile / `minSdk` 择优与 `cjs_sites_v5` 命名空间说明

实测结论见 [`验收报告.md`](./验收报告.md)、[`reports/2026-09-24-device-sample.md`](./reports/2026-09-24-device-sample.md)。

### B. 源可用性运维机制（P1）· **已完成**

- [x] 巡检脚本入仓：`scripts/check-sources.ps1`（无外部进程、.NET 内并发、TS 的 SPS 解析画质、延迟测量）
      —— 取代并删除 `probe-quality.ps1` / `probe-quality-fast.ps1`
- [x] 结构化报告（JSON + Markdown：可用率 / 画质分布 / 状态分布 / 域名集中度 / 延迟分位 / 频道级告警），
      文件名带输入标识，支持 `-Baseline` 跨期对比
- [x] 首次巡检建立基线：内置源 **35/35 = 100% 可用**、全部 ≥720p、延迟 p50 172 ms、无黑名单命中、无零可用频道
- [x] 聚合源复测：246 条仅 24 条可用（9.8%），220 条 `probe-failed`，Top 域名全是裸 IP —— 印证聚合源质量
- [x] 补源评估（原「按需补源」）：**结论是不补** —— 可补的只有 1~2 条裸 IP 的 IPTV 源，风险大于收益；
      39 个纯 `webview://` 频道实测 8/8 起帧、全 1080p
- [ ] 排序与健康规则（分辨率 → 码率 → 延迟）已在合并阶段实现；「连续 N 次失败降级 / M 次失败移除」
      的运行时健康度尚未做，见 C 的遗留项

实测证据见 [`reports/2026-09-25-source-audit.md`](./reports/2026-09-25-source-audit.md)。

### C. 起播体验与多线路回退（P0，A 阶段实测后升级）

A 阶段实测发现：筛选源 12 个抽样频道里有 5 个因**首条线路失败且不回退**而完全无画面
（CCTV-4 有 3 条可用线路却一条都不尝试）。该项已成为体验的最大瓶颈，优先级 P1 → **P0**。

**根因（2026-09-25）**：回退代码本就完整（6 个自动触发点），但被 `autoSwitchSource` 默认关闭拦成手动提示；
且同文件的 `playbackRecovery`（播放中断恢复）本就会自动换线路，两处行为不一致。

- [x] 建立首帧耗时基线：官方 CDN 优先频道中位 ≈2.0 s、`webview://` 优先频道中位 ≈2.3 s
- [x] 打通起播失败自动切换：默认开启 + 自动尝试上限 4 + 命中线路日志 `Auto source switch`；
      实测「超时→可用」8.13 s 出画面、「报错→可用」**1.88 s** 出画面、「5 条全坏」第 4 条后停止
- [ ] 复用 CJS 已有的 `ttlSec`（≤600）url 缓存，减少返回频道时的重复解析
- [ ] `webview://` 频道加速：页面预连接 / 复用 / 命中既有解析快照缓存
- [ ] 复核超标频道（CCTV-2 7.6 s、厦门卫视 22.1 s）在回退开启后的表现
- [ ] 首跳选择引入健康度评分（成功率 / 冷却 / TTL，参考 `LineHealth.kt`）——当前顺序策略够用，留作后续

实测证据见 [`reports/2026-09-25-autoswitch-fallback.md`](./reports/2026-09-25-autoswitch-fallback.md)。

参考实现（`D:/WorkStation/my-point/MyTv/my-tvForTest`，可直接移植的设计输入）：

| 机制 | 做法 | 对应文件 |
| --- | --- | --- |
| 健康度 | `count/success/at` 三字段；失败阈值 2、冷却 2h 自动复活、成功即清零、7 天 TTL；"未知"按中性处理 | `LineHealth.kt` |
| 换线窗口 | 先只在前 2 条最优线轮换，失败 +1、封顶 4；用户手选到窗口外则放开全部 | `models/TVViewModel.kt` |
| 触发区分 | 播放报错先换封装类型再换线；静默 15 s 无画面直接换线，单线给两次机会 | `PlayerFragment.kt` |
| 排序优先级 | 坏线沉底 → 用户手选 → 画质分档 → 探活档位 → 成功率 → 延迟 | `TVList.kt#sortLines` |
| 网段打散 | 用 302 后的真实 host（裸 IP 按 /16 归并）去重，避免自动轮换的前两条同机房 | `TVList.kt` |
| 播放实测画质 | `onVideoSizeChanged` 回写真实分辨率，是画质维度最可靠的来源 | `ChannelProbe.recordPlaybackSize` |
| 线路裁剪 | 每频道最多保留 6 条（自动轮换 2 + 手动 4） | `TVList.MAX_LINES_PER_CHANNEL` |
| 预热 | 相邻台 + 下一候选线提前建连，缓存 302 落点 | `StreamPreheat.kt` |

注意：本项目 `HlsProxyServer` 已有 `actualDescription()`（TS 实际分辨率探测）与
`Selected HLS variant quality=` 日志，画质回写可以直接复用，不必另起一套。

### D. 可观测性与发布保障（P2）· **已完成**

#### 日志埋点（已固化为 `key=value`，可直接用 `logcat | Select-String` 聚合）

| 埋点 | 格式 | 位置 |
| --- | --- | --- |
| 插件激活 | `CJS plugin activated version=N` | `MainActivity` |
| 首帧 | `First video frame rendered decoder=hardware\|software channel=NAME` | `MainActivity` |
| 自动换源 | `Auto source switch channel=NAME to=i/n attempt=k reason=R` | `MainActivity`（C 阶段新增） |
| 选档 | `Selected HLS variant quality=Q choices=N bandwidth=B advertised=WxH` | `HlsProxyServer` |
| 恢复换线 | `Playback recovery exhausted; using backup source i/n` | `MainActivity` |
| 恢复换台 | `Playback recovery exhausted; moving to next channel group=g channel=c` | `MainActivity` |

流量采样不进应用日志，由 `device-sample-test.ps1` 读 `/sys/class/net/*/statistics/rx_bytes` 增量
（比应用内计数更贴近链路真实值）。

#### 回归用例（一键执行）

```powershell
pwsh -File scripts/regression.ps1                # 静态 + 设备，约 10 s
pwsh -File scripts/regression.ps1 -WithPlayback  # 追加真起一次流
```

- [x] 三层断言：**S 静态 14 项**（不需设备）/ **D 设备 14 项** / **P 播放 1 项**（可选）
- [x] 覆盖：`HOST_PROTOCOL=5`、`cjs_sites_v5` 命名空间、v4/v3 不被加载、`isBetterNativeProfile` 择优、
      两个 flavor 的 minSdk/ABI、内置源频道数与多线路数与 assets 一致、自动回退默认开启与上限常量、
      设备侧 `catalog.protocol=5`、设备无 v4 目录与 prefs
- [x] 退出码 = 失败条数，可接入 CI
- [x] 首次执行 **29/29 全绿**（含播放断言，首帧 1204 ms）

#### 发布检查清单

- [x] 签名：自建 debug 与官方 release 不同，切换安装前先 `uninstall`（否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）
- [x] ABI：本机无 `armeabi-v7a`，本次只出 `assembleArm64Debug`（arm32 flavor 保留但不发布）
- [x] 文档一致性：`docs/cjs-plugin.md` protocol 5 ↔ `HOST_PROTOCOL=5`（由回归 S11 守护）
- [x] 发布前流程：`regression.ps1 -WithPlayback` 全绿 → `check-sources.ps1` 确认内置源未劣化 → 打包

#### 本地非上游文件清单

已确认被 `.gitignore` 拦住、不会进上游：

| 文件 | 原因 |
| --- | --- |
| `local.properties` | 本地 SDK 路径 |
| `build-mirror.init.gradle` | 本地 Gradle 镜像与路径（本次新增忽略） |
| `.debug-signing/`（含 `debug.jks`） | 本地调试签名私钥（本次新增忽略） |
| `.signing/`、`build/`、`app/build/`、`.gradle/`、`.idea/` | 构建与 IDE 产物 |

待提交（当前未跟踪）：迭代文档 `docs/iterations/`、6 个从 `MainActivity` 拆出的类
（`ChannelKeys` / `PlaybackSupport` / `SourceTags` / `SystemCpu` / `TextFormats` / `UiMetrics`）、
合并输入 `cctv-filtered.m3u`、项目文档（`NativeWasmTv-*.md`、`docs/开发理念.md` 等）。

### E. 控制页信息架构收敛（P2，随 A/C 实测补充）

背景：控制页部分文案与用户认知脱节——「低版本兼容插件」听起来像老设备补丁，实际是所有网页线路
（央视 / 卫视）的解析运行时；「站点目录 URL」属于需要先理解概念的高级项，默认摆在首屏，误改会让相关频道集体打不开。

- [x] 精简原「系统信息」页，只保留 CJS 插件部分
- [x] 页面与入口改名「网站插件」，说明写清影响范围（央视频等网站的解析组件，央视与卫视的网页线路依赖它）
- [x] 「站点目录 URL」折叠为「高级：站点目录地址」，标注「仅在插件无法下载时修改，改错会导致相关频道打不开」
- [x] 主按钮「刷新目录并检查已安装站点」不再因目录地址为空而失败（空值不回传 `cjsPluginManifestUrl`）
- [x] 修复删除 `legacySelectPicker` 时残留的 1 个闭合 `</div>`（DOM 不平衡）
- [ ] 复核其余二级页（播放与显示 / 浏览器配置 / 频道分组管理）是否存在同类「技术项摆首屏」问题

## 验收标准

| 维度 | 指标 | 目标 |
| --- | --- | --- |
| 频道 | 直连可用频道路数 | 由 20 → **≥ 60**（覆盖 64 个筛选频道） |
| 播放 | 抽样切台首帧成功率 | 100%（≥10 个频道） |
| 播放 | 15s 流量 | ≥ 5 MB（对照官方 release +10.5 MB） |
| 播放 | 首帧耗时 | 直连 ≤ 2 s、`webview` ≤ 6 s（先出基线再定稿） |
| 回退 | 起播失败自动切换成功率 | ≥ 90%，且日志可区分命中线路 |
| 源运维 | 内置源可用率 | ≥ 95%，一条命令产出报告 |
| 保障 | 回归与发布 | 回归用例一键执行全绿；发布清单逐项勾选 |
| 文档 | 文档一致性 | `docs/cjs-plugin.md` 与 `HOST_PROTOCOL` 一致 |

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| 聚合线路为第三方转发，随时失效 | 官方 CDN 与 `webview://` 条目始终保留在前，聚合线路仅作追加 |
| 同名频道命名不一致导致不合并 | 归一化规则统一命名 |
| `pm clear` 后 `shared_prefs` 不存在导致写入失败 | 先 `run-as mkdir -p shared_prefs` 再 push + cp |
| 自动切换引入卡顿/黑屏抖动 | 设切换阈值与最大切换次数，切换不阻塞 UI |
| 埋点或报告口径漂移，无法跨期对比 | 格式与字段版本化，变更在进度记录中说明 |
| 上游后续升 protocol 6 | `HOST_PROTOCOL`、命名空间、择优规则集中常量化，升级只改一处 |

## 产出物

- `app/src/main/assets/builtin_channels.txt`（画质优先重构后的内置源：66 频道 / 35 直连 / 63 `webview://`）
- 代码：`CjsPluginRuntime.java`（protocol v5）、`MainActivity.java`（自动回退默认开启 + 尝试上限 + 统一日志）、
  `control.html`（控制页收敛）
- `scripts/check-sources.ps1`（源巡检）+ `reports/check-*-2026-09-25.{json,md}`
- `scripts/regression.ps1`（v5 回归，三层断言，29/29 全绿）
- `scripts/device-sample-test.ps1`（设备侧抽样）、`scripts/merge-builtin-channels.ps1`（内置源合并）
- `docs/cjs-plugin.md`（protocol 5 说明）
- 本目录 `reports/`：实测记录与证据片段（首帧基线、巡检报告、回退验证）

## 文档约定

一个迭代只有两份文档，不额外造空壳：

| 文档 | 用途 | 时点 |
| --- | --- | --- |
| `README.md`（本文件） | 迭代计划：目标 / 范围 / 工作项 / 验收 / 风险 / 进度 | 开始时定稿，执行中更新勾选与进度 |
| [`验收报告.md`](./验收报告.md) | 收尾一次写：验收结论 + 实测记录 + 发布信息 + 回顾 | 迭代收尾 |

巡检报告、首帧日志、流量采样等证据直接放 `reports/`、`logs/`，不单独成文。

## 进度记录

| 日期 | 事件 |
| --- | --- |
| 2026-09-24 | v5 适配完成并验证通过；聚合源筛选完成；迭代建立，工作项 A~D 待推进 |
| 2026-09-24 | A 完成：导入实测（筛选源 64 频道）→ 定位"无自动回退"根因 → 合并内置源（直连覆盖 64 频道，卫视频道 33→47）→ 复测 17/17 起帧 → 文档升 protocol 5 |
| 2026-09-24 | 验收：有条件通过（A 达标；C 由实测升为下轮 P0） |
| 2026-09-24 | 画质优先改造：全量探测 246 条线路（仅 18 条验证到视频流）→ 剔除 188 条低画质/半死源 → 设备侧实测 9/9 全部 1920x1080 |
| 2026-09-24 | 黑名单清理：按 `my-tvForTest` 的过滤逻辑剔除 `iill.top`、快手 `kwimgs` 录像轮播等 43 条假直播/风险源 → 复测受影响频道仍为 1080p |
| 2026-09-25 | 控制页收敛（E）：插件页改名「网站插件」并写清影响范围、站点目录 URL 折叠为高级项、主按钮加空值保护、清理残留闭合标签；标签平衡校验 + 截图验证通过 |
| 2026-09-25 | 工作项 C（P0）打通：定位到「回退代码完整但被默认关闭的开关拦住」→ 默认开启 + 尝试上限 4 + 命中线路日志；3 场景实测通过（超时→可用 8.13 s、报错→可用 1.88 s、全坏第 4 条停止） |
| 2026-09-25 | 工作项 B 完成：巡检脚本 `check-sources.ps1` 入仓（含并发参数教训）；内置源基线 35/35 = 100% 可用；聚合源复测 9.8% 可用；补源评估结论为「不补」（收益 < 风险） |
| 2026-09-25 | 工作项 D 完成：`regression.ps1` 三层断言（S 静态 14 / D 设备 14 / P 播放 1）首次执行 29/29 全绿；固化日志埋点格式与发布检查清单；`build-mirror.init.gradle` 与 `.debug-signing/` 加入 `.gitignore` |
