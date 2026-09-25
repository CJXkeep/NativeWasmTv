# 设备侧抽样实测（2026-09-25 · 央视频道 / 依赖 `webview://` 的频道）

- 设备：MuMu 实例 1（`127.0.0.1:16416`，Android 15 / arm64）
- 应用：`xiao.bu.tv` 1.6.0（versionCode 6，安装时间 2026-09-25 09:16:26 —— I2 交付包）
- 方法：[`device-sample-test.ps1`](../../I1-插件v5适配与源提质/scripts/device-sample-test.ps1)，`-GroupName 央视频道 -GroupIndex 1`，采样 10~12s；首帧由 logcat `First video frame` 判定，分辨率取 `GET /api/state` 的 `video` 字段（播放器实测，不受源声明影响）
- 对照组：宿主侧巡检见 [`check-builtin_channels-2026-09-25-rerun.md`](./check-builtin_channels-2026-09-25-rerun.md)

## 一、结果

| 频道 | 线路数 | 首帧(ms) | 流量(MB/10~12s) | 分辨率 | 结果 | 起播路径 |
| --- | --- | --- | --- | --- | --- | --- |
| CCTV-1 综合 | 3 | 2303 | 9.32 | 1920x1080 | OK | CJS CMG/Yangshipin runtime |
| CCTV-5 体育 | 3 | 1715 | 9.02 | 1920x1080 | OK | 未抓取 |
| CCTV16（4K） | 1 | 2277 / 1148（二次） | 8.28 / 7.76 | 1920x1080 | OK | CJS CMG/Yangshipin runtime |
| CGTN | 1 | 1701 | 8.40 | 1920x1080 | OK | 未抓取 |
| CCTV4K | 1 | 1721 | 8.20 | 1920x1080 | OK | CJS CMG/Yangshipin runtime |
| CCTV-4 中文国际（欧） | 1 | 2262 | 3.39 | 1280x720 | OK | 直连 CDN（无 `webview://`） |

- 6/6 起播成功；首帧 1.1 ~ 2.3 s；5 个 1920x1080、1 个 1280x720（`CCTV-4 中文国际（欧）` 的单条直连线路本身就是 720p）
- 其中 `CCTV16（4K）`、`CCTV4K`、`CGTN` 三个频道**只有一条 `webview://` 线路**（`sourceCount=1`），本次均能起播

## 二、起播路径（logcat 证据）

```
09-25 12:11:12.202 I MainActivity: Configured CMG runtime from Yangshipin tag=... ok=true warmup=0/0
09-25 12:11:12.849 I MainActivity: First video frame rendered decoder=hardware channel=CCTV16（4K）
```

依赖 `webview://` 的央视频频道，实际走的是 **CJS 的 CMG / Yangshipin runtime**（由插件解析出直连流），随后 `decoder=hardware` 出首帧。

## 三、覆盖边界（按开发理念原则 8）

- **未覆盖 `WebSourceView.onStreamDiscovered`**：本次全部央视频频道都由 CJS 解析出直连流，日志中没有 `Web source stream discovered`。要覆盖这条 WebView 发现流的路径，需要一个「插件解析不出、必须由浏览器授权页面出流」的站点，本轮没有这样的样本。I2 验收报告记载它是 `webview://` 频道的播放主路径，本次**不能**据此判定该路径可用。
- 只测了 `央视频道` 分组（32 个频道里的 6 个）；`卫视频道` 未测（I2 验收报告另有含卫视的 5 频道记录，但那次未区分起播路径）。
- 流量取设备 `wlan0` 的 `rx_bytes` 差值，含应用自身的其它网络请求，不是纯粹的流比特率。
- 未测长稳（连续换台 1h、待机唤醒）与 Android 4.x 真机。
