# Android TV 应用 MuMu 调试指南

- 适用：`my-tv`（本仓库，`com.lizongying.mytv`）及同类横屏直播应用的日常调试
- 配套文档：[`NativeWasmTv-MuMu部署与调试记录.md`](./NativeWasmTv-MuMu部署与调试记录.md)（一次完整实战记录，本文只讲方法）
- 环境基线：Windows + MuMu Player 12 + Android SDK

---

## 零、环境自检（换机器/换实例先跑一遍）

```powershell
# MuMu 安装目录（下文的 $MUMU 均指此路径）
$MUMU = 'D:\Program Files\Netease\MuMu Player 12\nx_main'
$ADB  = "$MUMU\adb.exe"     # 用 MuMu 自带的 adb（版本与设备更匹配）

# 1) 实例与 adb 端口（唯一的权威来源）
& "$MUMU\MuMuManager.exe" info -v all

# 2) 连接
& $ADB connect 127.0.0.1:<adb_port>
& $ADB devices -l

# 3) 设备画像：系统版本 / ABI / 分辨率 —— 决定要打哪个包、UI 按什么尺寸调
& $ADB -s 127.0.0.1:<adb_port> shell "getprop ro.build.version.release; getprop ro.build.version.sdk;
    getprop ro.product.cpu.abilist; wm size; wm density"
```

**画像是打包的前提**，两种常见翻车：

| ABI 列表 | 能装的包 |
| --- | --- |
| 含 `armeabi-v7a` | arm32、arm64 都可以 |
| 只有 `x86_64,arm64-v8a,x86` | **只能 arm64（或 x86_64）**，arm32 包会安装失败或装了起不来 |

> MuMu 上 ARM 应用靠 `libhoudini` 本地桥接运行（logcat 中可见 `NB_NAME=libhoudini.so`、`RunningArchitecture=armeabi-v7a`）。桥接下绝大多数应用可用，但**极少数依赖特定 NEON/指令集的 native 库可能异常**，遇到只有 native 崩的情况要先怀疑它。

---

## 一、连接与设备管理

### 1.1 MuMuManager 速查

```powershell
& "$MUMU\MuMuManager.exe" info -v all               # 实例列表（含 adb_port / player_state）
& "$MUMU\MuMuManager.exe" control -v 0 restart      # 重启实例（adb 假死时的首选手段）
& "$MUMU\MuMuManager.exe" control -v 0 shutdown
& "$MUMU\MuMuManager.exe" control -v 0 launch
& "$MUMU\MuMuManager.exe" setting -v 0 -a           # 全部设置（分辨率、DPI、root、NET 等）
& "$MUMU\MuMuManager.exe" setting -v 0 -aw          # 仅可写项
& "$MUMU\MuMuManager.exe" adb -v 0 -c "connect"     # 走 MuMu 自己的 adb 通道
& "$MUMU\MuMuManager.exe" sh  -v 0 -c "getprop ro.build.version.sdk"   # 免 adb 直接下发 shell
```

### 1.2 adb 状态异常的标准处理顺序

```
1) MuMuManager info -v all          → 确认 is_android_started / player_state
2) MuMuManager control -v 0 restart → 30s 后重连
3) adb kill-server / start-server   → 再 connect
4) adb reconnect offline            → 兜底
5) 仍不行 → 完全 shutdown + launch（冷启动）
```

### 1.3 多设备条目的坑

同一实例常出现两条记录：

```
127.0.0.1:16416   device    <- 显式 connect 的
emulator-5556     device    <- adb 扫描 5554~5584 自动发现的，同一个实例
```

后果：不加 `-s` 会报 `adb: more than one device/emulator`。**所有命令统一带 `-s 127.0.0.1:<adb_port>`**，不要依赖 `emulator-xxxx`（端口会随实例变化）。

---

## 二、构建与安装

### 2.1 本项目构建

- Gradle Wrapper `8.6-rc-1`，AGP `8.3.0`，Kotlin `1.9.22`，**Java 17**
- `compileSdk 34 / targetSdk 33 / minSdk 21`
- 签名：`keystore.properties`（已存在于仓库根目录，debug 包不需要）

```powershell
cd d:\WorkStation\my-point\MyTv\my-tvForTest
.\gradlew.bat :app:assembleDebug            # 日常调试
.\gradlew.bat :app:assembleRelease          # 出包（读 keystore.properties）
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（或 `release\`）

### 2.2 JDK 选择：`org.gradle.java.home` 陷阱

本机 `GRADLE_USER_HOME` 是自定义目录（`D:\SoftWareTools\GradleUserHome-Legado`），其中的 `gradle.properties` 写死了 `org.gradle.java.home=JDK21`。**这是全局配置，其它项目在用，不要改它**，需要时按次覆盖：

```powershell
# 本机可用 JDK：
#   D:\SoftWareTools\Java\jdk-21.0.11+10        （全局默认，AGP 8.3 / Gradle 8.6 可用）
#   D:\SoftWareTools\Java\microsoft-jdk-17.0.18
#   D:\SoftWareTools\Java\jdk8u181              （老项目：Gradle 4.x / AGP 3.x 只能用它）

.\gradlew.bat :app:assembleDebug "-Dorg.gradle.java.home=D:/SoftWareTools/Java/microsoft-jdk-17.0.18"
```

- 本项目（AGP 8.3.0 + Gradle 8.6-rc-1 + `sourceCompatibility 17`）用全局的 JDK 21 **可以直接构建**，无需覆盖；
- 覆盖只在遇到老项目时用（例如 Gradle 4.4 / AGP 3.1.4 只认 JDK 8，喂 JDK 21 会报 `Could not determine Java version using executable <JDK21路径>`）；
- 报上面这条错的判断方法：**报错里的 JDK 路径 ≠ 你期望的 JDK**，就是被 `org.gradle.java.home` 或 `JAVA_HOME` 覆盖了，先 `Get-ChildItem env: | Where-Object Name -match 'JAVA|GRADLE'` 看一眼。

### 2.3 依赖拉取慢/超时

`google()` / `mavenCentral()` 在国内可能很慢。**不要改仓库里的 `repositories`**，用一个临时 init script 注入镜像：

```powershell
.\gradlew.bat :app:assembleDebug --init-script build-mirror.init.gradle
```

（脚本内容见配套文档第 4.3 节。）

Gradle 发行包本身超时时，改 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 为腾讯镜像：

```
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.6-rc-1-bin.zip
```

### 2.4 安装 / 卸载 / 清数据

```powershell
$DEV = '127.0.0.1:16416'

& $ADB -s $DEV install -r   <apk>     # 覆盖安装，保留数据
& $ADB -s $DEV install -r -d <apk>    # 允许降版本覆盖
& $ADB -s $DEV uninstall com.lizongying.mytv
& $ADB -s $DEV shell pm clear com.lizongying.mytv        # 等价于"清除数据"（会清掉令牌/缓存）

# 只装代码不动数据（增量调试，比 install -r 更快）
& $ADB -s $DEV install -r -d --fastdeploy <apk>
```

> 换签名（例如从别人的 release 包换成自己的 debug 包）会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先 `uninstall`。
> 若报 `INSTALL_FAILED_NO_MATCHING_ABIS`，就是第 0 节的 ABI 画像问题。

---

## 三、运行时观测

### 3.1 logcat

```powershell
$DEV = '127.0.0.1:16416'

& $ADB -s $DEV logcat -c                                    # 先清，避免旧日志干扰
& $ADB -s $DEV logcat -v time -s MainActivity MainFragment TVList ChannelCache
& $ADB -s $DEV logcat -v time --pid=((& $ADB -s $DEV shell pidof com.lizongying.mytv).Trim())
& $ADB -s $DEV logcat -v time *:E                           # 只看错误
```

本项目主要 TAG（`Log.*(TAG, ...)`）：

| TAG | 关注点 |
| --- | --- |
| `MainActivity` | 生命周期、按键、`ready $tag`、频道不可用计数 |
| `MainFragment` | 起播、切台、`ready/switch/request`、线路选择 |
| `TVList` | 订阅源加载/轮换、解析失败、缓存写入与否决 |
| `ChannelCache` | 缓存读写 |

排查链路建议：**先过滤本项目 TAG，再叠加 `AndroidRuntime`/`FATAL`，最后才看全量**。全量日志里系统噪声极大（`nemuinit`、`NewFileUpdater`、`BoostKit` 等 MuMu 自己的日志会刷屏）。

### 3.2 崩溃定位

```powershell
& $ADB -s $DEV logcat -b crash -v time                      # crash 缓冲
& $ADB -s $DEV shell "ls -l /data/tombstones/ 2>/dev/null"  # native 崩溃
& $ADB -s $DEV shell "dumpsys dropbox --print system_app_crash | tail -80"
```

本项目自带崩溃留存：`filesDir/crash`，通过局域网接口直接读取：

```powershell
Invoke-WebRequest "http://127.0.0.1:34567/api/crash?token=<令牌>"
```

### 3.3 判断"到底有没有在干活"——流量法

UI 黑屏/卡住时，先量网卡计数，比看 UI 可靠：

```powershell
& $ADB -s $DEV shell "cat /proc/net/dev | grep wlan0; sleep 15; echo '=== after 15s ==='; cat /proc/net/dev | grep wlan0"
```

| 现象 | 含义 |
| --- | --- |
| 15s 只涨几 KB | 根本没在拉流（源没解析出来／被拦截／播放器没启动） |
| 15s 涨几 MB ~ 几十 MB | 在拉流，此时黑屏要往解码/渲染方向查 |

### 3.4 截图

```powershell
& $ADB -s $DEV shell screencap -p /sdcard/s.png
& $ADB -s $DEV pull /sdcard/s.png .\s.png
```

**不要用** `adb exec-out screencap -p > s.png`：PowerShell 会把二进制按文本重定向，得到的是几 KB 的坏图。

两个已知坑：

- **视频层可能截不到**（典型表现：整屏纯白/纯黑，但其它 UI 正常）。这是 SurfaceView 独立图层没被合成进截屏，不代表没在播 → 用 3.3 的流量法交叉验证。
- 想确认图层是否存在：

```powershell
& $ADB -s $DEV shell "dumpsys SurfaceFlinger --list" | Select-String -Pattern 'com.lizongying.mytv'
```

### 3.5 输入模拟（遥控器/触屏）

```powershell
& $ADB -s $DEV shell input keyevent 19      # DPAD_UP
& $ADB -s $DEV shell input keyevent 20      # DPAD_DOWN
& $ADB -s $DEV shell input keyevent 21      # DPAD_LEFT
& $ADB -s $DEV shell input keyevent 22      # DPAD_RIGHT
& $ADB -s $DEV shell input keyevent 23      # DPAD_CENTER / 确认
& $ADB -s $DEV shell input keyevent 4       # BACK
& $ADB -s $DEV shell input keyevent 3       # HOME
& $ADB -s $DEV shell input keyevent 85      # MEDIA_PLAY_PAUSE
& $ADB -s $DEV shell input text "123"       # 数字键快速切台
& $ADB -s $DEV shell input tap  960 540     # 触摸：点击
& $ADB -s $DEV shell input swipe 1700 900 1700 300 300   # 触摸：滑动（右滑切台等）
```

> 本项目的按键分发集中在 `MainActivity.onKeyDown`（日志 `keyCode $keyCode, event $event`），按不出反应时先确认日志里有没有这条。

### 3.6 内存 / CPU / 卡顿

```powershell
& $ADB -s $DEV shell "dumpsys meminfo com.lizongying.mytv"
& $ADB -s $DEV shell "dumpsys gfxinfo com.lizongying.mytv framestats"
& $ADB -s $DEV shell "top -n 1 -b | Select-String mytv"
```

---

## 四、用应用自带的服务做"黑盒驱动"

本项目内置局域网配置服务（`ConfigServer.kt`，NanoHTTPD）：

- 监听 `0.0.0.0:34567`
- 鉴权：请求头 `x-config-token`（兼容 `?token=`），令牌存在 `SharedPreferences("MainActivity")` 的 `config_token`
- 接口：`POST /api/save`、`GET /api/test?source=`、`GET /api/reset`、`GET /api/crash`

从宿主机直连（模拟器在 NAT 后面，先端口转发）：

```powershell
$DEV = '127.0.0.1:16416'
& $ADB -s $DEV forward tcp:34567 tcp:34567

# 取令牌（仅 debug 包可用 run-as；release 包请看 TV 设置页展示的地址）
$token = (& $ADB -s $DEV shell "run-as com.lizongying.mytv cat shared_prefs/MainActivity.xml" |
          Select-String -Pattern 'config_token').ToString() -replace '.*>([^<]+)</string>.*','$1'

# 测订阅源
Invoke-WebRequest "http://127.0.0.1:34567/api/test?source=https://example.com/tv.m3u" -Headers @{'x-config-token'=$token}

# 换源并触发热重载
Invoke-WebRequest -Uri "http://127.0.0.1:34567/api/save" -Method POST -Headers @{'x-config-token'=$token} `
  -ContentType 'application/json' -Body '{"source":"https://example.com/tv.m3u"}'

& $ADB -s $DEV forward --remove tcp:34567
```

**价值**：不碰 UI 就能复现「换源 → 重新加载 → 起播」整条链路，回归测试时比手点遥控器快得多。

---

## 五、网络排查

播放类问题里，**先排除网络，再怀疑代码**。判据优先级从高到低：

```powershell
$DEV = '127.0.0.1:16416'

# 1) 路由表（要看 all，Android 是 fwmark 多表路由，ip route 会漏）
& $ADB -s $DEV shell "ip route show table all"

# 2) 网关 / 公网 可达性
& $ADB -s $DEV shell "ping -c 2 -W 2 10.0.2.2"      # MuMu 的 NAT 网关
& $ADB -s $DEV shell "ping -c 2 -W 2 223.5.5.5"

# 3) DNS
& $ADB -s $DEV shell "ping -c 2 -W 3 www.baidu.com"
& $ADB -s $DEV shell "getprop | Select-String -Pattern 'net.dns|dhcp'"

# 4) 应用侧真正的报错（比 ping 更能说明问题）
& $ADB -s $DEV logcat -v time | Select-String -Pattern 'UnknownHost|ConnectException|HTTP 403|HTTP 404|SSLHandshake|timeout'
```

设备侧正常的样子：

```
default via 10.0.2.2 dev wlan0 table wlan0 proto static
10.0.2.0/24 dev wlan0 proto kernel scope link src 10.0.2.15
```

只有 `10.0.2.0/24` 链路路由 = 没有默认网关 = 设备侧网络坏了（换实例或修 MuMu 网络）。

其它常见情况：

| 报错 | 判断 |
| --- | --- |
| `HTTP 403` | 服务端拒绝（防盗链/地域/UA），请求已到达，属源问题 |
| `UnknownHostException` | DNS 问题 |
| `SSLHandshakeException` / `Certificate` | 老设备 TLS 套件协商失败（本项目有 `OkHttpTLSCompat`，见于 `ApiClient.kt`） |
| 只有某几个域名不通 | 目标站被墙/被限，不是模拟器问题；想办法在宿主机验证同一 URL 做对照 |

---

## 六、播放问题决策树

按"流量是否在涨"分两条路：

```
黑屏/无声
├─ 流量不涨（15s 只有几 KB）
│   ├─ 日志有 HTTP/DNS 报错        → 第五节，源或网络问题
│   ├─ 日志有"解析/线路/空地址"     → 订阅源解析、线路选择逻辑
│   ├─ 日志有第三方插件/脚本安装失败 → 该频道依赖的能力缺失（本项目无此路径；NativeWasmTv 遇到过）
│   └─ 什么都没有                   → 根本没走到起播：查 View 层/状态机，别查播放器
└─ 流量在涨（黑屏但确实在拉流）
    ├─ Surface 未创建               → logcat 搜索 "surface created"；查 SurfaceView 生命周期
    ├─ 解码器初始化失败              → 搜索 MediaCodec/OMX/c2；换软解或换硬解器对比
    ├─ 转译层问题（MuMu ARM 桥接）    → 同时验证 arm64/x86 包，或换实例对比
    └─ 截图黑但实际能看               → 只是截屏截不到视频层，属观测误差
```

**关键原则：先用流量法把"拉流"和"渲染"分开**，否则会在错误的方向上翻代码。

---

## 七、常见坑速查表

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| `more than one device/emulator` | 同实例两条 adb 记录 | 一律 `-s 127.0.0.1:<port>` |
| 设备 `offline` 反复 | adb 通道假死 | `MuMuManager control restart` |
| `Could not determine Java version` | 全局 `org.gradle.java.home` 指向不匹配的 JDK | `-Dorg.gradle.java.home=...` 覆盖 |
| `Invalid keystore format` | 用 JDK 8 读 JDK 21 生成的 PKCS12 签名文件 | 换匹配的 JDK，或另建 JKS 用 `-Pandroid.injected.signing.*` 注入 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 打包 ABI 与实例不符 | 按第 0 节画像重打 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名变了 | 先 `uninstall` |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 版本号变小 | `install -r -d` |
| 截屏纯白/纯黑 | 视频层独立图层 或 PowerShell 重定向损坏 | 用 `screencap`+`pull`；用流量法交叉验证 |
| 应用里服务连不上 | 模拟器在 NAT 后 | `adb forward tcp:本地 tcp:设备` |
| `gradlew` 下载超时 | Gradle 官方源不可达 | 换腾讯镜像 |
| 依赖下载卡住 | google/mavenCentral 慢 | init script 注入阿里云镜像 |

---

## 八、可选：把常用动作包成函数

放进 PowerShell profile，或每次 `dot-source` 一个 `devtools.ps1`：

```powershell
function Get-MumuDev([int]$Index = 1) {
    $MUMU = 'D:\Program Files\Netease\MuMu Player 12\nx_main'
    $info = & "$MUMU\MuMuManager.exe" info -v $Index | ConvertFrom-Json
    "127.0.0.1:$($info.adb_port)"
}

function Connect-Mumu([int]$Index = 1) {
    $MUMU = 'D:\Program Files\Netease\MuMu Player 12\nx_main'
    $dev = Get-MumuDev $Index
    & "$MUMU\adb.exe" connect $dev | Out-Null
    & "$MUMU\adb.exe" -s $dev devices -l
    $dev
}

function Watch-Net([string]$Dev, [int]$Seconds = 15) {
    $MUMU = 'D:\Program Files\Netease\MuMu Player 12\nx_main'
    & "$MUMU\adb.exe" -s $Dev shell "cat /proc/net/dev | grep wlan0; sleep $Seconds; echo '=== after ${Seconds}s ==='; cat /proc/net/dev | grep wlan0"
}

function Show-Shot([string]$Dev, [string]$Path = '.\shot.png') {
    $MUMU = 'D:\Program Files\Netease\MuMu Player 12\nx_main'
    & "$MUMU\adb.exe" -s $Dev shell screencap -p /sdcard/_shot.png
    & "$MUMU\adb.exe" -s $Dev pull /sdcard/_shot.png $Path | Out-Null
    Get-Item $Path
}
```

---

## 九、一次标准调试流程（照抄即可）

```powershell
# 0. 变量（第 8 节的函数若未加载，则手动定义）
$MUMU = 'D:\Program Files\Netease\MuMu Player 12\nx_main'

# 1. 连接并确认画像
$dev = Connect-Mumu 1        # 未加载函数时： Get-MumuDev / adb connect 手动来一遍

# 2. 构建 + 安装
cd d:\WorkStation\my-point\MyTv\my-tvForTest
.\gradlew.bat :app:assembleDebug
& "$MUMU\adb.exe" -s $dev install -r .\app\build\outputs\apk\debug\app-debug.apk

# 3. 清日志、冷启动
& "$MUMU\adb.exe" -s $dev shell am force-stop com.lizongying.mytv
& "$MUMU\adb.exe" -s $dev logcat -c
& "$MUMU\adb.exe" -s $dev shell am start -n com.lizongying.mytv/.MainActivity

# 4. 抓本项目日志
& "$MUMU\adb.exe" -s $dev logcat -v time -s MainActivity MainFragment TVList ChannelCache

# 5. 平行验证：流量 + 截图
Watch-Net $dev 15
Show-Shot $dev .\shot.png
```
