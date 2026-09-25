# NativeWasmTv v5 适配与源质量评估记录

- 日期：2026-09-24
- 仓库：`d:\WorkStation\TVNY`（master @ `2645848`）
- 设备：MuMu 实例 1，`127.0.0.1:16416`，Android 15 / SDK 35，ABI `x86_64,arm64-v8a,x86`（**无 armeabi-v7a**）
- 配套文档：[`MuMu调试指南.md`](./MuMu调试指南.md)（方法）、[`NativeWasmTv-MuMu部署与调试记录.md`](./NativeWasmTv-MuMu部署与调试记录.md)（部署）

---

## 一、插件协议 v5 适配（已完成，验证通过）

### 1.1 问题与根因

切台报「兼容插件安装失败：插件协议不兼容」，流量法测得 15 秒仅 +5 KB（未拉流）。

报错链路：

```
MainActivity → CjsPluginRuntime.ensureCatalog()
→ refreshCatalog() 下载 https://raw.githubusercontent.com/TvWasm/cjs/main/catalog.json
→ readManifest(): if (value.optInt("protocol") != HOST_PROTOCOL) throw "插件协议不兼容"
→ MainActivity#showChannelBar("兼容插件安装失败：…")
```

实测线上返回 `{"protocol":5,...}`，而宿主代码 `HOST_PROTOCOL = 4`。

**为什么官方 release 反而能用**：反汇编对比发现，官方 release（1.6.0/vc6）是**旧架构**——
`CjsPluginRuntime`/`CjsSource`/`QuickJs` 在其 dex 中出现次数为 **0**，站点解析全部内置
（`YangshipinWebResolver`、`NativeCmgDecryptor` + `libcctv_h5e.so`/`libcmg_decrypt.so`/`libysp_keygen.so`），
根本不下载在线插件，因此不受 catalog v5 影响。master 分支把内置实现迁移成了在线 CJS 插件架构（QuickJS + 按需下载 .so），却尚未跟上 v5。

### 1.2 v5 与 v4 的实际差异

| 文件 | 差异 |
| --- | --- |
| `catalog.json` | **结构完全一致**，仅 `protocol` 4→5 |
| `sites/*/version.cjs` | **结构完全一致**，仅 `v` 数值 |
| `sites/*/dist/runtime.json` | **结构完全一致**，仅 `protocol`/`version` 数值 |
| `sites/*/plugin.json` | `.so` 条目**新增 `profile`/`minSdk`/`ndk` 字段**；armeabi-v7a 出现两个变体（`armv7-base` minSdk=14、`armv7-perf` minSdk=19）；arm64 仍是单一条目 |

结论：v5 是"软升级"——真正的结构性变更只有多 native profile，以及**强制新缓存命名空间**（v5 不加载 v4 存储）。

### 1.3 代码改动（共 3 处，均在 `app/src/main/java/xiao/bu/tv/CjsPluginRuntime.java`）

1. `HOST_PROTOCOL = 4` → `5`
2. 缓存命名空间：SharedPreferences `cjs_sites_v4` → `cjs_sites_v5`；目录 `cjs-sites-v4` → `cjs-sites-v5`
3. 站点文件选择逻辑重写：原逻辑遇到重名文件直接抛「站点文件声明无效」；改为**先按 ABI 收集候选、再按 `minSdk` 择优**（新增 `isBetterNativeProfile()`，取设备 SDK 可加载的最高 profile）

### 1.4 验证结果

| 检查项 | 结果 |
| --- | --- |
| 协议校验 | 通过（`CJS plugin activated version=站点目录`） |
| catalog 下载 | `files/cjs-sites-v5/catalog.json` |
| 站点插件安装 | `yangshipin.cn/arm64-v8a/2/{abi.txt, runtime.json, yangshipin.so}`，SHA-256 校验通过 |
| **首帧渲染** | `First video frame rendered decoder=hardware channel=CCTV-1 综合`，1920x1080 |
| 流量（15s） | 改前 +5 KB → 改后 **+6.9 MB**（官方 release 对照 +10.5 MB） |

---

## 二、源质量综合评估（nTv vs my-tvForTest）

### 2.1 两边源体系本质不同

| | nTv（本项目） | my-tvForTest |
| --- | --- | --- |
| 源来源 | 内置固定列表 `assets/builtin_channels.txt` | 在线聚合订阅（5 个公开源，`TVList.kt` DEFAULT_SOURCES） |
| 频道条目 | 83（央视 50 / 卫视 33）；**直连 m3u8 仅 20 条**，其余 63 条 `webview://` 央视频页面 | CCTV/卫视 **679** 条，单源全量 1600~1955 条 |
| 换源能力 | 无 | 有（按历史成功率评分的智能换源 `rotateSource()`） |
| 上游测速 | 无 | `iptv-api` 已自动测速排序 |

### 2.2 实测数据（宿主机发起）

| 指标 | nTv 直连源 | my-tvForTest 聚合源 |
| --- | --- | --- |
| 可用率 | **20/20 = 100%** | 随机抽样：src1 44% / src2 44% / src3 20% |
| 同口径（仅 CCTV/卫视） | 100% | **11/30 = 36.7%** |
| 域名集中度 | 7 个（全部央视/腾讯云官方 CDN：`volcfcdn`、`kcdnvip`、`bdydns`、`myqcloud`、`wscdns`） | 207~328 个（大量第三方小站、裸 IP、IPv6） |
| 画质 | 单码率固定（响应无 `BANDWIDTH`/`RESOLUTION`），实测 CCTV-1 为 1080p | 参差，仅个别标注 1080p |

### 2.3 结论

- nTv 的源**"少而稳"**：可用性 100%，但频道少、多数频道依赖央视频页面解析（起播慢）
- my-tvForTest 的源**"多而杂"**：数量多但当下可用率仅约 1/3，靠智能换源兜底
- 用户感知 nTv「源质量差」的真实原因是**频道数量少 + 起播链路长**，而不是源的可用性差

---

## 三、聚合源全量筛选（已完成）

方法：从 3 个聚合源提取全部 CCTV/卫视条目（679 → URL 去重 647），并发探测（32 线程，GET 只读前 4KB，
校验 `#EXTM3U`，提取 `RESOLUTION`/`BANDWIDTH`，记录延迟），组内按 分辨率→码率→延迟 排序。

**产物：`cctv-filtered.m3u`**（246 条可用线路，覆盖 64 个频道：CCTV-1~17 全部 + 绝大多数省级卫视，每个频道 1~7 条备选）。

注意事项：

1. 246 条里大量是**裸 IP + HTTP**（如 `http://63.141.230.178:82/...`），还有带 `?auth=test20251009` 等临时授权参数的，会失效
2. 其中**几乎没有央视官方 CDN**（聚合源的央视源多为第三方转发）——nTv 原有 20 条官方 CDN 在"央视"维度上仍是最优
3. 243 条是单码率媒体列表（无多码率信息），仅 3 条标注 1920x1080
4. 探测脚本：`%TEMP%\probe-sources.ps1`；源快照：`%TEMP%\mytv-sources\src{1..3}.m3u`，可随时重跑

---

## 四、导入实测（进行中，以下是已确认的机制与剩余步骤）

### 4.1 已确认的接入机制（读码结论）

- 订阅源存储：SharedPreferences **`management`** 的 `playlist_sources_v1`（JSON 数组），元素格式
  `{"id","name","location","enabled"}`；另写 `playlist_url` = 第一个源的 location（见 `PlaylistManager.saveSources/parseSources`）
- 源大小上限 `playlistByteLimit()` = max(8MB, min(64MB, heap/4))，是**上限**不是门槛，26KB 的 M3U 无需担心
- **多线路自动合并**：`ChannelBucket.add()` 把同名频道合并为一个 `Channel`，URL 追加进 `urls`——
  所以 M3U 里同名频道的多条线路会自动变成一个频道的多条线路
- 加载优先级：`catalogStore`（SQLite 缓存）> 在线源 > 内置；**换源前必须清掉 catalogStore 缓存**，否则看不到新源

### 4.2 已就绪的环境

- 本地 HTTP 服务已在跑：`python -m http.server 8899`，根目录 `%TEMP%\ntv-serve`，
  供源地址 `http://10.0.2.2:8899/cctv-filtered.m3u`（宿主机 200 OK；设备侧 `ping 10.0.2.2` 通）

### 4.3 剩余步骤

```powershell
$ADB='D:\Program Files\Netease\MuMu Player 12\nx_main\adb.exe'; $DEV='127.0.0.1:16416'
# 1. 清数据（catalogStore 有缓存优先级，必须清）
& $ADB -s $DEV shell am force-stop xiao.bu.tv
& $ADB -s $DEV shell pm clear xiao.bu.tv
# 2. 写入源配置（management.xml，playlist_sources_v1 + playlist_url）
#    注意 pm clear 后需 run-as mkdir -p shared_prefs，再 push+cp
# 3. 启动 → 确认频道列表出现「筛选源」分组 64 个频道
# 4. 抽样切台实测：keyevent 20 切台，看 logcat 的 First video frame rendered / 流量法
#    （同名多线路会自动 fallback，测"频道"而不是"线路"）
# 5. 汇总设备侧各频道的可用线路数
```

---

## 五、合并重建（待做）

1. 依据设备侧实测结果，把可用线路并入 `app/src/main/assets/builtin_channels.txt`：
   - **保留**原有 20 条官方 CDN 直连 + 63 条 `webview://` 条目（高可用基线）
   - **追加**聚合筛选出的线路到对应频道（同名即自动合并为多线路，起播失败可 fallback）
2. 重新构建：`assembleArm64Debug`（命令见第六节），安装验证首帧与流量
3. 可选：按 `CCTV_NAME` 归一化规则（`^cctv(\d+)(\+?)(.*)$`）统一频道命名，避免同名不同组

---

## 六、环境与构建备忘（可复制）

```powershell
cd d:\WorkStation\TVNY
$env:JAVA_HOME='D:\SoftWareTools\Java\jdk8u181'   # AGP 3.1.4 + Gradle 4.4 只认 JDK 8
$G='D:\SoftWareTools\GradleUserHome-Legado\wrapper\dists\gradle-4.4-bin\94ov83m0i0y9xxxpsar4yx6s9\gradle-4.4\bin\gradle.bat'
$ADB='D:\Program Files\Netease\MuMu Player 12\nx_main\adb.exe'; $DEV='127.0.0.1:16416'

& $G :app:assembleArm64Debug --init-script build-mirror.init.gradle --console=plain `
  "-Dorg.gradle.java.home=D:/SoftWareTools/Java/jdk8u181" `
  "-Pandroid.injected.signing.store.file=d:/WorkStation/TVNY/.debug-signing/debug.jks" `
  "-Pandroid.injected.signing.store.type=JKS" `
  "-Pandroid.injected.signing.store.password=android" `
  "-Pandroid.injected.signing.key.alias=androiddebugkey" `
  "-Pandroid.injected.signing.key.password=android"

& $ADB -s $DEV install -r .\app\build\outputs\apk\arm64\debug\app-arm64-debug.apk
& $ADB -s $DEV shell am start -n xiao.bu.tv/.MainActivity
```

关键坑位速记：

| 坑 | 处理 |
| --- | --- |
| Wrapper 下载 `services.gradle.org` 超时 | 本地缓存哈希不一致会重新下载；直接调用缓存中已解压的 `gradle.bat` |
| 全局 `org.gradle.java.home=JDK21`（`GRADLE_USER_HOME/gradle.properties`） | 命令行 `-Dorg.gradle.java.home=JDK8` 覆盖 + 同步设置 `JAVA_HOME` |
| `google()`/`mavenCentral()` 直连慢 | `--init-script build-mirror.init.gradle` 注入阿里云镜像（**必须前置**，追加无效） |
| `Invalid keystore format` | 全局 debug.keystore 是 JDK21 的 PKCS12，JDK8 读不了；用 JDK21 keytool 转成项目内 JKS（`.debug-signing/debug.jks`，**密钥对与指纹不变**），`-Pandroid.injected.signing.*` 注入 |
| 官方 release 与自建 debug 签名不同 | 切换安装前先 `uninstall` |
| 设备 ABI 无 `armeabi-v7a` | 只能装 arm64 flavor（`assembleArm64Debug`） |
| `git archive` 在 Windows 会把 LF 转 CRLF | 导致 SHA-256 校验失败；需要字节级一致时用 `git cat-file blob` 二进制导出 |

已修改/新增的本地文件（不属于上游）：

- `local.properties`（SDK 路径，必需）
- `build-mirror.init.gradle`（镜像加速，临时）
- `.debug-signing/debug.jks`（项目内签名，必需）
- `C:\Users\Administrator\.android\debug.keystore.pkcs12.bak`（原 PKCS12 备份；原文件未改动）
- `cctv-filtered.m3u`（本次筛选产物）

待办：更新 `docs/cjs-plugin.md` 的 protocol 4 → 5 说明（不影响运行）。

---

## 七、迭代计划

一个迭代一个文件夹，迭代内的工作项统一记在该目录的 `README.md`，不按工作项再拆目录。

| 迭代 | 主题 | 状态 | 目录 |
| --- | --- | --- | --- |
| I1 | 插件 v5 适配与源提质 | A 已完成（有条件通过） | [`I1-插件v5适配与源提质/`](./docs/iterations/I1-插件v5适配与源提质/README.md) |
| I2 | 功能收敛与冗余清理 | A/B/C 完成，D 部分完成 | [`I2-功能收敛与冗余清理/`](./docs/iterations/I2-功能收敛与冗余清理/README.md) |

I2 的依据是 [`docs/开发理念.md`](./docs/开发理念.md)：把与"看电视"主业无关的功能、以及只为老设备存在的开关清掉，
收敛设置项并拆分 `MainActivity`。

| 工作项 | 内容 | 优先级 | 状态 |
| --- | --- | --- | --- |
| A | v5 收尾与内置源提质（第四节导入实测 + 第五节合并重建 + 文档同步） | P0 | **已完成** |
| B | 源可用性运维机制（巡检脚本入仓 + 报告留档 + 补源评估） | P1 | **已完成**（补源结论：不补） |
| C | 起播体验与多线路回退（首帧基线 + 失败自动切换） | **P0**（A 实测后升级） | **已完成**（主项） |
| D | 可观测性与发布保障（埋点规范 + v5 回归 + 发布清单） | P2 | **已完成** |
| E | 控制页信息架构收敛（插件页改名 / 高级项折叠 / 残留标签修复） | P2 | 部分完成 |

验收指标、风险对策与任务清单见迭代目录 README；实测证据（报告 / 日志）落在该目录的 `reports/`、`logs/` 下。

### 7.1 I1 阶段结论（2026-09-24）

- 导入实测：`pm clear` + `POST /api/playlist/merge` →「筛选源」64 个频道（57 个多线路）
- 关键根因：**多线路并存但不会自动回退**——筛选源 12 个抽样频道中 5 个因首条线路失败而无画面（CCTV-4 有 3 条可用线路却一条都不尝试）
- 合并结果：内置源 171 → 205 行（画质优先改造后），新增 1 个频道
- **画质优先**：246 条聚合线路全量探测，**仅 18 条能验证到真实视频流**（171 条 playlist 可拿但分片 404、57 条连不上）；
  按"≥720p 且已验证"追加 14 条，剔除 188 条低画质/半死源
- **假直播清理**：按 `my-tvForTest` 的过滤逻辑剔除黑名单域名 `iill.top`、快手 `kwimgs` 录像轮播等 43 条
- 内置源复测：抽样 9 个频道 **9/9 起帧且全部 1920x1080**，首帧 1.7~2.3 s，流量 8.6~13.2 MB
- 详见 [`docs/iterations/I1-插件v5适配与源提质/验收报告.md`](./docs/iterations/I1-插件v5适配与源提质/验收报告.md)、[`reports/2026-09-24-device-sample.md`](./docs/iterations/I1-插件v5适配与源提质/reports/2026-09-24-device-sample.md)

### 7.2 I1 阶段结论（2026-09-25）

- **C 打通自动回退**：根因是回退代码本就完整（6 个自动触发点），却被 `autoSwitchSource` 默认关闭拦成手动提示。
  改为默认开启 + 自动尝试上限 4 + 命中线路日志。3 场景实测：超时→可用 8.13 s、报错→可用 **1.88 s**、5 条全坏第 4 条停止。
- **首帧基线（当前版本）**：10 个多线路频道 **10/10 起帧、全部 1920x1080**，首帧 1.15~2.84 s（中位 1.70 s）。
  7.1 里记录的「CCTV-2 7.63 s、厦门卫视 22.05 s」属 581 行版本，画质优先重构为 205 行后已不适用。
- **回退覆盖率 36%**：66 个频道中 24 个有多线路，其余 42 个单线路（多为卫视 `webview://`）。
- **B 巡检机制**：`scripts/check-sources.ps1` 入仓。内置源 **35/35 = 100% 可用**、全部 ≥720p、延迟 p50 172 ms；
  聚合源 246 条仅 24 条可用（9.8%），220 条 `probe-failed`，Top 域名全是裸 IP。
- **补源结论：不补** —— 可补的只有 1~2 条裸 IP 的 IPTV 源，风险大于收益；39 个纯 `webview://` 频道实测 8/8 起帧、全 1080p。
- **探测参数教训**：并发 32 + 超时 4 s 会把 35 条里的 28 条误判为不可达（可用率 20%）；并发 8 + 超时 15 s 才是 100%。
  本场景下高并发"越慢越不准"，该结论已固化进脚本头部注释。
- **E 控制页收敛**：插件页改名「网站插件」并写清影响范围，站点目录 URL 折叠为高级项，修复残留的闭合标签。
- **D 发布保障**：`scripts/regression.ps1` 三层断言（S 静态 14 / D 设备 14 / P 播放 1）首次执行 **29/29 全绿**，
  覆盖协议、命名空间、v4 不被加载、ABI 择优、内置源与 assets 一致、回退配置；六类日志埋点固化为 `key=value`；
  `build-mirror.init.gradle` 与 `.debug-signing/`（签名私钥）加入 `.gitignore`。
- 详见 [`reports/2026-09-25-autoswitch-fallback.md`](./docs/iterations/I1-插件v5适配与源提质/reports/2026-09-25-autoswitch-fallback.md)、[`reports/2026-09-25-source-audit.md`](./docs/iterations/I1-插件v5适配与源提质/reports/2026-09-25-source-audit.md)
