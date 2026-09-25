# NativeWasmTv 部署到 MuMu 模拟器调试记录

- 记录时间：2026-09-24
- 目标：把 `https://github.com/buhanzhe/NativeWasmTv`（应用名 nTv，包名 `xiao.bu.tv`）在 MuMu 模拟器上构建、安装并跑通，验证网络、界面与播放链路
- 结论摘要：**应用安装、启动、频道列表、管理接口、播放器渲染全部验证通过**；**内置频道无法播放**，根因是上游 CJS 插件目录协议号升级到 5，而当前源码只支持 4

---

## 一、环境信息

### 1.1 主机

| 项 | 值 |
| --- | --- |
| OS | Windows 10.0.26200 |
| Shell | PowerShell (Core) |
| Android SDK | `D:\Users\Administrator\AppData\Local\AndriodSDK` |
| SDK platforms | android-31 ~ android-37（**没有 android-27 / android-30**） |
| SDK build-tools | 33.0.1 / 34.0.0 / 35.0.0 / 36.0.0（**没有 27.0.3**） |
| `GRADLE_USER_HOME` | `D:\SoftWareTools\GradleUserHome-Legado`（自定义，不是 `~/.gradle`） |
| JAVA_HOME（全局） | `D:\SoftWareTools\Java\jdk-21.0.11+10` |
| java（PATH） | `D:\SoftWareTools\Java\jdk8u181` |
| 宿主机默认路由 | `0.0.0.0/0 -> 192.168.101.1 (WLAN)` |

### 1.2 MuMu 模拟器

最初连接的实例 0（Android 12）在调试中途被替换为实例 1（Android 15），两者差异直接决定了打哪个 ABI 的包：

| 项 | 实例 0（旧） | 实例 1（新，本次使用） |
| --- | --- | --- |
| index | 0 | 1 |
| 名称 | MuMu安卓设备 | MuMu安卓设备-1 |
| Android | 12 / API 32 | 15 / API 35 |
| 机型 | HONOR YOK-AN10 | 23127PN0CC（Xiaomi 14, houji） |
| adb 端口 | 16384（同时 5555 被 VMM 监听） | **16416** |
| ABI 列表 | `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi` | `x86_64,arm64-v8a,x86`（**无 armeabi-v7a**） |
| 分辨率 | 1280x720 @240dpi | 1920x1080 @280dpi |

关键影响：**实例 1 不再支持 armeabi-v7a**，因此必须构建 arm64 包；ARM 应用通过 MuMu 的 `libhoudini` 本地桥接运行（日志中 `NB_NAME=libhoudini.so`，`RunningArchitecture=armeabi-v7a`）。

---

## 二、步骤一：adb 连接与排障

### 2.1 问题：设备反复 offline

首次执行 `adb devices` 时出现两个条目，且随时间从 `device` 变为 `offline`：

```
127.0.0.1:16384   offline   product:York model:YOK_AN10
emulator-5554     device    product:York model:YOK_AN10
```

`kill-server` / `start-server` / `reconnect offline` 均无法恢复。

### 2.2 定位手段

1. 确认 MuMu 进程在跑：

```powershell
Get-Process | Where-Object { $_.ProcessName -match 'MuMu|Nemu' }
# MuMuNxDevice / MuMuNxMain / MuMuNxService / MuMuVMMHeadless / MuMuVMMSVC ...
```

2. 查监听端口，确认 MuMu 的 VMM 占用了哪些端口：

```powershell
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 5555,16384,7555,5037 }
```

3. 查实例真实 adb 端口（**最可靠**）：

```powershell
cd 'D:\Program Files\Netease\MuMu Player 12\nx_main'
.\MuMuManager.exe info -v 0      # 单个实例；-v all 查全部
```

返回 JSON 中 `adb_host_ip` / `adb_port` / `is_android_started` / `player_state` 是判断依据。

### 2.3 解决

实例 0 的 adb 处于假死状态，`MuMuManager.exe control -v 0 restart` 后恢复正常：

```powershell
cd 'D:\Program Files\Netease\MuMu Player 12\nx_main'
.\MuMuManager.exe control -v 0 restart
Start-Sleep -Seconds 30
.\MuMuManager.exe info -v 0          # 确认 player_state=start_finished
.\adb.exe connect 127.0.0.1:16384
.\adb.exe devices -l
```

> 经验：`emulator-5554/5556` 这类条目是 adb 扫描 5554~5584 端口自动发现的，与 `127.0.0.1:<port>` 指向同一实例。同实例出现两个条目时 `adb shell` 会报 `more than one device/emulator`，必须用 `-s 127.0.0.1:<port>` 指定。

### 2.4 切换到实例 1

实例 0 消失后，连接改为：

```powershell
cd 'D:\Program Files\Netease\MuMu Player 12\nx_main'
.\MuMuManager.exe info -v all        # 得到 index=1, adb_port=16416
.\adb.exe connect 127.0.0.1:16416
.\adb.exe -s 127.0.0.1:16416 shell "getprop ro.product.model; getprop ro.build.version.release; getprop ro.product.cpu.abilist"
```

---

## 三、步骤二：网络排查（实例 0 无外网）

调试中途发现实例 0 完全无法联网，这是当时"什么都不播"的直接原因，记录下来备查。

### 3.1 现象

```
E/MainActivity: Unable to install CJS plugin
W/EpgManager: Unable to refresh EPG
```

`/proc/net/dev` 中 `wlan0` 的 rx 字节 10 秒只涨几 KB。

### 3.2 判断方法

```powershell
# 1) 看 guest 路由表（关键）
adb -s <dev> shell "ip route show table all"

# 2) 看网关与公网连通性
adb -s <dev> shell "ping -c 2 -W 2 10.0.2.2"
adb -s <dev> shell "ping -c 2 -W 2 223.5.5.5"
```

实例 0 的坏结果：

```
10.0.2.0/24 dev wlan0 proto kernel scope link src 10.0.2.15
# 没有 default 路由，没有任何 10.0.2.2 网关
connect: Network is unreachable
```

实例 1 的正常结果：

```
default via 10.0.2.2 dev wlan0 table wlan0 proto static
2 packets transmitted, 2 received, 0% packet loss     # ping 10.0.2.2
2 packets transmitted, 1 received, +1 duplicates      # ping 223.5.5.5，通
```

> 经验：MuMu 走 VBox NAT，guest 正常应有 `default via 10.0.2.2`。只有 `10.0.2.0/24` 链路路由 = DHCP 没下发网关，属于模拟器侧网络异常（实例 0 冷启动/重启都无法恢复，最终换实例解决）。
> 注意：Android 的 netd 会按 fwmark 分表路由，用 `ip route show table all` 才能看全，只看 `ip route` 会漏。

---

## 四、步骤三：构建 arm64 包

### 4.1 项目构建约束（源码自带）

- Gradle Wrapper：**4.4**
- Android Gradle Plugin：**3.1.4**
- compileSdkVersion 30 / buildToolsVersion 27.0.3 / minSdk 14(arm32)、21(arm64) / targetSdk 25
- productFlavors：`arm32`（armeabi-v7a）、`arm64`（arm64-v8a）
- 原生库已预编译进仓库：`app/src/main/libs/{armeabi-v7a,arm64-v8a}/*.so`（ijkffmpeg / ijkplayer / ijksdl / ntvquickjs，armeabi-v7a 另有 ntvtls），**不需要 NDK 重编**
- release 需要 `.signing/` 密钥（仓库里没有）→ 本次走 **debug 变体**

### 4.2 四个环境坑与绕过

| # | 问题 | 现象 | 处理 |
| --- | --- | --- | --- |
| 1 | Gradle 官方源不可达 | `Downloading .../gradle-4.4-bin.zip failed: timeout` | wrapper 改用腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-4.4-bin.zip` |
| 2 | JDK 版本冲突 | `Could not determine Java version using executable ...jdk-21.0.11+10\bin\java.exe` | 全局 `GRADLE_USER_HOME\gradle.properties` 里写死了 `org.gradle.java.home=JDK21`，Gradle 4.4 解析不了 Java 21 的版本号；用 `-Dorg.gradle.java.home=...jdk8u181` 覆盖（**不要去改全局配置**，其它项目在用） |
| 3 | 依赖拉取慢 | google()/mavenCentral() 卡住 | 用 init script 注入阿里云镜像 |
| 4 | 签名失败 | `KeytoolException: Failed to read key AndroidDebugKey from store "C:\Users\Administrator\.android\debug.keystore": Invalid keystore format` | 全局 debug.keystore 是 JDK 21 生成的 **PKCS12**，JDK 8 读不了；本项目单独生成 **JKS**，通过 `-Pandroid.injected.signing.*` 注入，不动全局密钥 |

### 4.3 辅助文件

`local.properties`（指向 SDK，已被 .gitignore）：

```
sdk.dir=D\:\\Users\\Administrator\\AppData\\Local\\AndriodSDK
```

`build-mirror.init.gradle`（临时，注入阿里云镜像）：

```groovy
def mirrors = [
        'https://maven.aliyun.com/repository/google',
        'https://maven.aliyun.com/repository/public',
        'https://maven.aliyun.com/repository/central',
        'https://maven.aliyun.com/repository/jcenter',
        'https://maven.aliyun.com/repository/gradle-plugin'
]

gradle.beforeProject { project ->
    mirrors.each { mirror ->
        project.buildscript.repositories.maven { url mirror }
        project.repositories.maven { url mirror }
    }
}
```

生成专用调试签名（JDK 8 的 keytool）：

```powershell
& 'D:\SoftWareTools\Java\jdk8u181\bin\keytool.exe' -genkeypair `
  -keystore 'd:\WorkStation\my-point\MyTv\NativeWasmTv\.debug-keystore\ntv-debug.jks' `
  -storetype JKS -storepass android -keypass android -alias ntvdebug `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=nTv Debug, OU=Test, O=Test, L=Test, ST=Test, C=CN"
```

### 4.4 构建命令（arm64 debug）

```powershell
$env:JAVA_HOME='D:\SoftWareTools\Java\jdk8u181'
$env:Path="$env:JAVA_HOME\bin;"+$env:Path
cd d:\WorkStation\my-point\MyTv\NativeWasmTv

.\gradlew.bat :app:assembleArm64Debug `
  --init-script build-mirror.init.gradle `
  "-Dorg.gradle.java.home=D:/SoftWareTools/Java/jdk8u181" `
  "-Pandroid.injected.signing.store.file=D:/WorkStation/my-point/MyTv/NativeWasmTv/.debug-keystore/ntv-debug.jks" `
  "-Pandroid.injected.signing.store.password=android" `
  "-Pandroid.injected.signing.key.alias=ntvdebug" `
  "-Pandroid.injected.signing.key.password=android"
```

产物：`app/build/outputs/apk/arm64/debug/app-arm64-debug.apk`（约 3.1 MB）

> 注意：PowerShell 下所有 `-Pxxx=yyy` 必须加引号，否则会被拆参数，报 `Task '.injected.signing.store.password=android' not found`。

---

## 五、步骤四：安装与启动验证

```powershell
cd 'D:\Program Files\Netease\MuMu Player 12\nx_main'
.\adb.exe -s 127.0.0.1:16416 install -r "d:\WorkStation\my-point\MyTv\NativeWasmTv\app\build\outputs\apk\arm64\debug\app-arm64-debug.apk"
.\adb.exe -s 127.0.0.1:16416 logcat -c
.\adb.exe -s 127.0.0.1:16416 shell am start -n xiao.bu.tv/.MainActivity
```

启动日志（正常）：

```
I/TlsCompat: HTTPS certificate and hostname verification disabled for Android 15
I/MainActivity: Resource profile low=false memoryClassMb=192 largeMemoryClassMb=512 heapLimitMb=512
I/MainActivity: Native UI scale mode=auto factor=1.0 viewport=1920x1080 densityDpi=280
I/LocalControlServer: Management server listening on 9966
I/MainActivity: Video surface created size=1920x1080 sdk=35
I/MainActivity: Channel catalog ready in 126 ms: 2 groups, 65 channels
```

> 若 `pm list packages` 显示 `Failure [not installed for 0]`，说明该实例上本来就没装，属正常。

---

## 六、步骤五：功能验证手法

### 6.1 用管理接口做黑盒验证（本次最有效的手段）

应用自带本地管理服务（默认 9966，被占用会顺延），把端口从宿主机转发出来即可用 HTTP 驱动应用：

```powershell
adb -s 127.0.0.1:16416 forward tcp:19966 tcp:9966

# 读状态
Invoke-WebRequest http://127.0.0.1:19966/api/state

# 切频道
Invoke-WebRequest -Uri http://127.0.0.1:19966/api/control -Method POST `
  -Body ([Text.Encoding]::UTF8.GetBytes('{"action":"play","group":3,"channel":0}')) `
  -ContentType 'application/json'

# 改设置（例：开启自动切源）
Invoke-WebRequest -Uri http://127.0.0.1:19966/api/settings -Method POST `
  -Body ([Text.Encoding]::UTF8.GetBytes('{"autoSwitchSource":true}')) `
  -ContentType 'application/json'
```

可用接口（读自 `LocalControlServer.java`）：`GET /api/state`、`POST /api/control`、`POST /api/settings`、`POST /api/pointer`、`POST /api/playlist/upload?id=&name=`、`POST /api/playlist/merge`、`GET /api/playlist/source`、`POST /api/ku9/script/upload`、`GET /api/recording/playlist`。

`/api/state` 里对排障最有用的字段：`current`（当前分组/频道/线路）、`settings.decodeMode`、`settings.hardwareDecoders`、`cjsPlugin`、`system`。

### 6.2 判断"到底有没有在拉流"

不看 UI，直接量网卡计数（最客观）：

```powershell
adb -s 127.0.0.1:16416 shell "cat /proc/net/dev | grep wlan0; sleep 15; echo '=== after 15s ==='; cat /proc/net/dev | grep wlan0"
```

- 不播时：15 秒只涨几 KB
- 正常播放：15 秒涨约 20 MB（≈1.4 MB/s）

### 6.3 截图

```powershell
adb -s 127.0.0.1:16416 shell screencap -p /sdcard/n.png
adb -s 127.0.0.1:16416 pull /sdcard/n.png d:\...\n.png
```

> 注意：PowerShell 里 `adb exec-out screencap -p > file.png` 会把二进制当文本重定向导致损坏（结果只有几 KB 的白图）；必须用 `screencap` 到设备 + `adb pull`。
> 另外实例 0（Android 12）上 MainActivity 的视频 SurfaceView 截不到，只能得到白底图；实例 1（Android 15）可以正常截到视频画面。

### 6.4 关键日志 TAG

`MainActivity`、`SharpVideoView`、`HlsProxyServer`、`LocalControlServer`、`EpgManager`、`CjsPlugin`、`TlsCompat`、`YangshipinResolver`、`WebSourceView`。

```powershell
adb -s 127.0.0.1:16416 shell "logcat -d -v time -s MainActivity HlsProxyServer EpgManager CjsPlugin"
```

---

## 七、核心问题与根因

### 7.1 内置频道全部无法播放（主要问题）

**现象**：画面黑屏，底部提示条显示

```
CCTV-2 财经  --fps--  兼容插件安装失败：插件协议不兼容 · 线路 2/2
```

**日志**：

```
E/MainActivity: Unable to install CJS plugin
E/MainActivity: java.io.IOException: 插件协议不兼容
E/MainActivity:   at xiao.bu.tv.CjsPluginRuntime.readManifest(CjsPluginRuntime.java:87)
E/MainActivity:   at xiao.bu.tv.CjsPluginRuntime.refreshCatalog(CjsPluginRuntime.java:192)
E/MainActivity:   at xiao.bu.tv.CjsPluginRuntime.ensureCatalog(CjsPluginRuntime.java:146)
E/MainActivity:   at xiao.bu.tv.MainActivity$44.run(MainActivity.java:2730)
```

**根因链**：

1. `CjsPluginRuntime.HOST_PROTOCOL = 4`，`readManifest()` 校验 `protocol != 4` 就抛"插件协议不兼容"（`CjsPluginRuntime.java:87`）
2. 实际拉取到的插件目录是 `https://gh-proxy.com/https://raw.githubusercontent.com/TvWasm/cjs/main/catalog.json`，内容为 **`"protocol": 5`**，站点为 `tv.cctv.com` / `tv.gxtv.cn` / `yangshipin.cn` —— **上游已升级到 5，当前源码只认 4**
3. `MainActivity.startChannel()` 中：

```java
if (requiresCjsPlugin(channel, source) && !CjsPluginRuntime.hasCatalog()) {
    installCjsPluginAndStart(currentChannelIndex, channel.name, "");
    return;   // 安装失败即放弃起播，不进入播放流程
}
```

4. `requiresCjsPlugin()` 对 `SOURCE_CUSTOM` 频道的判定是"**任一**线路命中 `isCctvDirectStream` / yangshipin pid / `webview://` / `cjs:` 就为真"。内置频道（`assets/builtin_channels.txt`）每条都是：
   - 线路 1：`webview://https://yangshipin.cn/tv/home?pid=...`
   - 线路 2：`https://ldncctvwbcd*.v.wscdns.com/.../index.m3u8?b=200-4000`（CCTV 直连，同样被判定需要插件）

   结果是**全部 65 个内置频道都被 CJS 插件安装失败卡死**，与是否为 CCTV 直连无关。

5. 安装失败后 `installCjsPluginAndStart` 的收尾逻辑只弹提示条然后 `return`，因此表现为"能切台、能显示线路 N/M、但永远黑屏"。

### 7.2 播放器本身是正常的（已排除环境因素）

为把"播放能力"与"插件问题"解耦，构造了一个**中立 HLS 源**（域名既非 CCTV 也非 yangshipin，因此 `requiresCjsPlugin()` 为 false），走应用自带的频道源导入接口：

```powershell
# 1) 上传本地播放列表
$m3u = "#EXTM3U`n#EXTINF:-1 group-title=`"测试直连`",BigBuckBunny HLS`nhttps://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`n"
Invoke-WebRequest -Uri 'http://127.0.0.1:19966/api/playlist/upload?id=testsrc&name=test.m3u' -Method POST `
  -Body ([Text.Encoding]::UTF8.GetBytes($m3u)) -ContentType 'application/octet-stream'

# 2) 作为频道源启用
Invoke-WebRequest -Uri 'http://127.0.0.1:19966/api/settings' -Method POST -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes(
  '{"playlistSources":[{"id":"testsrc","name":"测试直连源","location":"file:///data/user/0/xiao.bu.tv/files/imported-playlists/testsrc.playlist","enabled":true}]}'))

# 3) 播放（合并后新分组排在最后）
#    -> {"action":"play","group":3,"channel":0}
```

结果：**15 秒拉取约 20 MB，截图可见视频画面正常渲染**，说明：

- IJK 播放器 + 硬解（`OMX.qcom.video.decoder.avc`）在 MuMu + libhoudini ARM 转译下工作正常
- 应用 UI、频道列表、线路切换、管理接口、截图链路全部正常

测试完成后已把频道源清空还原：

```
POST /api/settings  {"playlistSources":[]}   -> {"ok":true,"message":"已停用全部在线频道"}
```

### 7.3 次要问题

| 问题 | 日志/现象 | 影响 | 结论 |
| --- | --- | --- | --- |
| EPG 拉取失败 | `java.io.IOException: 节目单下载失败：HTTP 403`（`EpgManager.download:226`） | 无节目单 | 上游 `https://liliu.serv00.net/epg/cn.xml` 拒绝访问，非致命 |
| 实例 0 无外网 | 无 default 路由、DNS 未配置 | 全部联网功能失效 | 换实例解决 |
| MainActivity 首启弹出内嵌管理页 | `topResumedActivity=...ManagementActivity` | 需按一次返回键 | 应用自身行为，非缺陷 |

---

## 八、命令速查

```powershell
# ---- MuMu 侧 ----
cd 'D:\Program Files\Netease\MuMu Player 12\nx_main'
.\MuMuManager.exe info -v all                     # 实例列表 + adb 端口
.\MuMuManager.exe control -v 0 restart            # 重启实例
.\MuMuManager.exe control -v 0 shutdown
.\MuMuManager.exe control -v 0 launch
.\MuMuManager.exe setting -v 0 -a                # 全部设置
.\MuMuManager.exe adb -v 0 -c "connect"          # 走 MuMu 自己的 adb
.\adb.exe connect 127.0.0.1:16416

# ---- 设备侧 ----
.\adb.exe -s 127.0.0.1:16416 shell "ip route show table all"
.\adb.exe -s 127.0.0.1:16416 shell "getprop ro.product.cpu.abilist"
.\adb.exe -s 127.0.0.1:16416 install -r <apk>
.\adb.exe -s 127.0.0.1:16416 shell am start -n xiao.bu.tv/.MainActivity
.\adb.exe -s 127.0.0.1:16416 shell "logcat -d -v time -s MainActivity CjsPlugin EpgManager"
.\adb.exe -s 127.0.0.1:16416 forward tcp:19966 tcp:9966
.\adb.exe -s 127.0.0.1:16416 shell dumpsys SurfaceFlinger --list
```

---

## 九、本次改动与遗留文件

均在克隆目录 `d:\WorkStation\my-point\MyTv\NativeWasmTv`，未提交：

| 文件 | 说明 |
| --- | --- |
| `gradle/wrapper/gradle-wrapper.properties` | **已改**：distributionUrl 指向腾讯镜像 |
| `build-mirror.init.gradle` | **新增**：阿里云镜像 init script（临时） |
| `local.properties` | **新增**：SDK 路径（.gitignore 已忽略） |
| `.debug-keystore/ntv-debug.jks` | **新增**：本项目专用 JKS 调试签名 |
| `.screenshots-mumu/` | **新增**：验证截图（`v15-play.png` / `v15-play2.png` 为播放画面） |

构建产物：`app/build/outputs/apk/arm64/debug/app-arm64-debug.apk`

---

## 十、后续建议

1. **内置频道恢复播放的前提**是解决 CJS 插件协议不匹配，二选一：
   - 等上游提供与 `HOST_PROTOCOL=4` 兼容的目录，或作者放出新版本 APK；
   - 试探性把 `CjsPluginRuntime.HOST_PROTOCOL` 改为 5 重编，验证 `protocol:5` 目录能否被当前运行时消费（**未验证，可能与 QuickJS 运行时接口不兼容**）。
2. 若只是想验证播放链路，可用本文 7.2 的方式挂一个非 CCTV / 非 yangshipin 的 HLS 源，A 验证通过。
3. 该应用**每次切台都会触发一次插件目录拉取**（`MainActivity$44.run`），目录失败时会持续打印异常堆栈，排查时不要被刷屏误导。
4. 换 MuMu 实例后务必重新确认 `adb_port` 与 `abilist`：实例 1 已不支持 armeabi-v7a。
5. 若要在实例 1 上跑 `my-tv`，同样应改打 arm64（或 x86_64）包。
