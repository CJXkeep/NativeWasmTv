# 设备侧抽样实测（2026-09-25 · 卫视频道 / 换台压测 / 短时长稳）

- 设备：MuMu 实例 1（`127.0.0.1:16416`，Android 15 / arm64）
- 应用：`xiao.bu.tv` 1.6.0（versionCode 6 —— I2 交付包）
- 方法：[`device-sample-test.ps1`](../../I1-插件v5适配与源提质/scripts/device-sample-test.ps1)（`-GroupName 卫视频道 -GroupIndex 2`）+ 轮询脚本（首帧判据为 logcat `First video frame`，分辨率取 `GET /api/state` 的 `video`）
- 姊妹报告：[央视频道](./device-sample-webview-2026-09-25.md)、[宿主侧巡检](./check-builtin_channels-2026-09-25-rerun.md)

## 一、卫视频道（I1 新增备用线路涉及的 7 个频道）

| 频道 | 线路数 | 首帧(ms) | 10s 流量(MB) | 分辨率 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 东方卫视 | 3 | 1727 | 15.43 | 1920x1080 | OK |
| 浙江卫视 | 4 | 1731 | 9.80 | 1920x1080 | OK |
| 深圳卫视 | 2 | 1717 | 8.64 | 1920x1080 | OK |
| 河北卫视 | 2 | 1718 | 7.81 | 1920x1080 | OK |
| 内蒙古卫视 | 2 | 2382 | 8.77 | 1920x1080 | OK |
| 青海卫视 | 2 | 2270 | 7.77 | 1920x1080 | OK |
| **凤凰卫视** | **1** | **-1** | **0.12** | **-** | **NOFRAME** |

6/7 起播成功且均为 1920x1080；凤凰卫视打不开，定位见第二节。

## 二、凤凰卫视失败定位（唯一线路，上游 403）

- 线路：`https://7612-5516-affc-d88b.kylintv.tv/live/pxinhd_iphone.m3u8`
  —— 内置源里**凤凰卫视只有这一条**；`2645848`（合并前）的内置源**没有凤凰卫视**，即该频道是 I1 合并线路时新引入的。
- 上游拒绝：

```
W HlsProxyServer: Retrying upstream request attempt=2/3 pxinhd_iphone.m3u8 after SocketTimeoutException
E HlsProxyServer: Proxy request failed
E HlsProxyServer: java.io.IOException: Upstream HTTP 403
```

- 界面表现（连接 3 次复现，其中 2 次 403 在 1 秒内返回）：
  - `state.current=凤凰卫视`、`state.video=0x0`，持续 ≥60s 不变
  - 画面停在**上一个频道的最后一帧**（间隔 12s 的两张截图 MD5 完全相同：`ECD671080170BBD61933A6CBE047C2C2`）→ 冻结帧
  - 切换后 8s / 12s / 18s / 25s / 55s 的截图都没有提示条；切换后 2~5s 内可见「凤凰卫视 · 正在连接视频 · 线路 1/1」
- 代码路径（供后续处理）：播放失败会重试 2 次，然后 `showChannelBar(channel.name, "播放错误: " + what + "/" + extra)`
  （`MainActivity.java:2951-2953`），而 `showChannelBar` 在 `!loadingActive` 时只挂 3 秒自动隐藏
  （`CHANNEL_BAR_TIMEOUT_MS = 3000`，`MainActivity.java:158/4417-4419`）。
  于是提示窗口很短，之后即静默冻结帧，且本频道只有 1 条线路、没有可自动切换的备选。
- 判定：**新增的凤凰卫视线路在设备上不可用**。宿主侧巡检曾报该线路 720p / ttfb 703ms 通过，
  属「宿主可探到、设备被 403 拒绝」的情形——正是 `check-sources.ps1` 注释里提醒的宿主/设备口径差异。
- **处置（2026-09-25，决策：不要该频道）**：已从 `app/src/main/assets/builtin_channels.txt` 删除这条线路，
  凤凰卫视随之从频道表消失（该频道在合并前本来就不存在）。内置源打包在 APK 资源里，改动需重新构建 APK 才生效，
  因此本次删除**没有**做设备复测。

## 三、连续换台压测（12 次，央视 / 卫视交替）

| 频道 | 首帧(ms) | 结果 |
| --- | --- | --- |
| CCTV-1 综合 | 1143 | OK |
| 东方卫视 | 1123 | OK |
| CCTV-3 综艺 | 2309 | OK |
| 浙江卫视 | 1122 | OK |
| CCTV-5 体育 | 1130 | OK |
| 深圳卫视 | 1693 | OK |
| CCTV-9 纪录 | 1686 | OK |
| 河北卫视 | 1129 | OK |
| CCTV-12 社会与法 | 2239 | OK |
| 内蒙古卫视 | 1124 | OK |
| CCTV16（4K） | 1133 | OK |
| 青海卫视 | 1122 | OK |

失败 **0 / 12**，首帧 1.1 ~ 2.3 s，无掉队。

## 四、持续播放 120s（CCTV-1 综合）

| 时点 | 累计流量 | 分辨率 |
| --- | --- | --- |
| 30s | 13.18 MB | 1920x1080 |
| 60s | 25.72 MB | 1920x1080 |
| 90s | 39.79 MB | 1920x1080 |
| 120s | 51.06 MB | 1920x1080 |

- 平均 ≈0.43 MB/s（≈3.4 Mbps），全程 1080p，无中断、无重连
- 期间错误日志只有系统级噪声（`OMXNodeInstance ... UnsupportedIndex`、`HidlServiceManagement ... race detected`），无应用层播放错误
- **这是 2 分钟短时观察，不等于 1 小时长稳**：未做连续播放 1h、连续换台 1h、待机唤醒

## 五、覆盖边界（按开发理念原则 8）

- 卫视频道只抽了 7 个（共 34 个），央视频道另见报告；两组合计本轮覆盖 13 个频道
- 未测 Android 4.x 真机（模拟器为 Android 15 / arm64）
- 未测待机唤醒、1h 长稳、弱网切换
- 流量取设备 `wlan0` `rx_bytes` 差值，含应用其它网络请求
