# 源巡检报告（2026-09-25）

- 输入：`builtin_channels.txt`
- 方式：宿主侧探测，并发 8，超时 15000ms，耗时 5.2s
- 明细：[`check-builtin_channels-2026-09-25.json`](./check-builtin_channels-2026-09-25.json)

> 口径说明：本报告是**宿主侧**结果，与设备侧实测不等价（存在「宿主判失败但设备可播」的情况）。
> 用于发现劣化趋势；写入内置源前须用 `device-sample-test.ps1` 做设备侧确认。

## 一、总览

| 指标 | 数值 |
| --- | --- |
| 频道数 | 65 |
| 直连线路 | 34 |
| `webview://` 条目 | 63 |
| └ 仅依赖 `webview://` 的频道（本就无直连） | 39 |
| 可用（验证到视频流） | 34 |
| └ 其中 ≥720p | 34 |
| └ 其中 <720p（低于画质下限） | 0 |
| **直连可用率** | **100%** |
| **直连可用率（≥720p）** | **100%** |
| 探测失败 | 0 |
| 可用线路延迟 | p50 183ms / p90 431ms |

## 二、画质分布

| 画质 | 线路数 |
| --- | --- |
| ≥2160p | 1 |
| 1080p | 27 |
| 720p | 6 |
| <720p | 0 |
| 未知/失败 | 0 |

## 三、状态分布

| kind | 数量 | 含义 |
| --- | --- | --- |
| `master` | 21 | 多码率主列表，取声明最高档 |
| `ts-sps` | 13 | 单码率，从 TS 的 SPS 解析 |

## 四、域名集中度（Top 10）

| 域名 | 线路数 |
| --- | --- |
| `ldocctvwbcdbyte.volcfcdn.com` | 12 |
| `74.91.26.218` | 3 |
| `ali-m-l.cztv.com` | 3 |
| `112.30.73.119` | 2 |
| `bp-resource-dfl.bestv.cn` | 2 |
| `ldcctvwbcdks.v.kcdnvip.com` | 2 |
| `ldocctvwbcdks.v.kcdnvip.com` | 2 |
| `ldcctvwbcdtxy.liveplay.myqcloud.com` | 1 |
| `hls-qhmh.lanzhousobey.cn` | 1 |
| `ldncctvwbcdbd.a.bdydns.com` | 1 |

