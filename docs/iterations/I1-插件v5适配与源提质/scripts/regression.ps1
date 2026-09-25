# v5 回归用例（一键执行）
#
# 把迭代 I1 中「已验证并依赖」的行为固化成断言，防止后续改动把它们碰坏。
# 分三层：
#   S 静态断言 —— 直接检查源码/资产文件，不需要设备，改动一提交就能发现问题
#   D 设备断言 —— 检查运行中应用的真实状态（需设备在线 + 应用已启动）
#   P 播放断言 —— 可选（-WithPlayback），真起一次流，最慢也最接近真实体验
#
# 用法：
#   pwsh -File regression.ps1                        # 静态 + 设备
#   pwsh -File regression.ps1 -WithPlayback          # 追加播放断言
#   pwsh -File regression.ps1 -AdbPath <adb> -Device <serial>
#
# 退出码：0 = 全部通过；非 0 = 失败条数（便于接入 CI）

param(
    [string]$AdbPath = '',
    [string]$Device = '',
    [string]$RepoRoot = '',
    [string]$Package = 'xiao.bu.tv',
    [int]$Port = 9966,
    [switch]$WithPlayback,
    [int]$PlaybackSeconds = 10,
    [int]$TimeoutSec = 20
)

$ErrorActionPreference = 'Continue'
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path "$PSScriptRoot\..\..\..\..").Path }
$script:failed = 0
$script:passed = 0

function Check($id, $name, [bool]$ok, $detail = '') {
    if ($ok) {
        $script:passed++
        "{0,-7} {1,-48} {2}" -f '[PASS]', "$id $name", $detail
    } else {
        $script:failed++
        "{0,-7} {1,-48} {2}" -f '[FAIL]', "$id $name", $detail
    }
}

function Read-Text($relPath) {
    $p = Join-Path $RepoRoot $relPath
    if (-not (Test-Path $p)) { return $null }
    return (Get-Content -Raw -Encoding UTF8 $p)
}

"=== v5 回归用例 ==="
"仓库根: $RepoRoot"
""

# ---------------- S 静态断言 ----------------
'--- S. 静态断言（源码 / 资产）---'

$runtime = Read-Text 'app/src/main/java/xiao/bu/tv/CjsPluginRuntime.java'
Check 'S1' 'HOST_PROTOCOL = 5' ($runtime -match 'HOST_PROTOCOL\s*=\s*5') 'app/src/main/java/xiao/bu/tv/CjsPluginRuntime.java'
Check 'S2' '缓存命名空间为 cjs_sites_v5' ($runtime -match '"cjs_sites_v5"') 'SharedPreferences 名'
Check 'S3' 'v5 目录为 cjs-sites-v5' ($runtime -match '"cjs-sites-v5"') 'files 目录名'
Check 'S4' 'native profile 择优（isBetterNativeProfile）' ($runtime -match 'isBetterNativeProfile') '按 ABI 收多个变体时取最高可用 minSdk'
Check 'S5' 'v4/v3 命名空间未出现在代码里' (-not ($runtime -match '"cjs_sites_v4"' -or $runtime -match '"cjs_sites_v3"')) '升级后旧缓存不再被引用'

$activity = Read-Text 'app/src/main/java/xiao/bu/tv/MainActivity.java'
Check 'S6' '自动回退默认开启' ($activity -match 'getBoolean\(AUTO_SWITCH_SOURCE,\s*true\)') '打不开比自动切换更伤体验'
$maxAttempts = if ($activity -match 'AUTO_SWITCH_MAX_ATTEMPTS\s*=\s*(\d+)') { $Matches[1] } else { '' }
Check 'S7' '自动尝试上限常量存在' ($maxAttempts -ne '') "上限=$maxAttempts"
Check 'S8' '自动切换记录固定格式日志' ($activity -match '"Auto source switch channel="') '便于脚本聚合'
Check 'S9' '首帧固定格式日志' ($activity -match '"First video frame rendered decoder="') '便于脚本聚合'

$hls = Read-Text 'app/src/main/java/xiao/bu/tv/HlsProxyServer.java'
Check 'S10' '选档固定格式日志' ($hls -match '"Selected HLS variant quality="') '画质档位可追溯'

$doc = Read-Text 'docs/cjs-plugin.md'
Check 'S11' '文档与 protocol 版本一致' ($doc -match 'protocol 5' -and -not ($doc -match '# Website plugins \(protocol 4\)')) 'docs/cjs-plugin.md'

$gradle = Read-Text 'app/build.gradle'
Check 'S12' 'arm64 flavor：minSdk 21 + arm64-v8a' (
    $gradle -match "arm64[\s\S]{0,400}?minSdkVersion\s+21[\s\S]{0,200}?abiFilters\s+'arm64-v8a'") 'app/build.gradle'
Check 'S13' 'arm32 flavor：minSdk 14 + armeabi-v7a' (
    $gradle -match "arm32[\s\S]{0,400}?minSdkVersion\s+14[\s\S]{0,200}?abiFilters\s+'armeabi-v7a'") 'app/build.gradle'

# 内置源：静态解析出期望的频道数与多线路频道数，供 D 层比对
$builtinRel = 'app/src/main/assets/builtin_channels.txt'
$builtinText = Read-Text $builtinRel
$expectedChannels = 0
$expectedMulti = 0
$expectedDirect = 0
$expectedWebview = 0
if ($builtinText) {
    $perChannel = @{}
    $name = ''
    foreach ($line in ($builtinText -split "`n")) {
        $l = $line.Trim()
        if ($l -match '^#EXTINF:.*,(.+)$') { $name = $Matches[1].Trim(); continue }
        if (-not $name) { continue }
        if ($l -match '^webview://') { $expectedWebview++; $perChannel[$name] = 1 + [int]$perChannel[$name]; $name = '' }
        elseif ($l -match '^https?://') { $expectedDirect++; $perChannel[$name] = 1 + [int]$perChannel[$name]; $name = '' }
    }
    $expectedChannels = $perChannel.Count
    $expectedMulti = @($perChannel.Values | Where-Object { $_ -ge 2 }).Count
}
Check 'S14' '内置源已加载且含多线路频道' ($expectedChannels -gt 50 -and $expectedMulti -gt 0) "频道=$expectedChannels 多线路=$expectedMulti 直连=$expectedDirect webview=$expectedWebview"

# ---------------- D 设备断言 ----------------
''
'--- D. 设备断言（运行中应用）---'

if (-not $AdbPath) {
    $candidates = @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        'D:\Program Files\Netease\MuMu Player 12\nx_main\adb.exe')
    $AdbPath = ($candidates | Where-Object { Test-Path $_ } | Select-Object -First 1)
    if (-not $AdbPath) { $AdbPath = 'adb' }
}
Check 'D0' 'adb 可用' ($null -ne (Get-Command $AdbPath -ErrorAction SilentlyContinue)) $AdbPath

$devices = @()
if (Get-Command $AdbPath -ErrorAction SilentlyContinue) {
    $devices = @(& $AdbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' } |
        ForEach-Object { ($_ -split '\s+')[0] })
}
if (-not $Device -and $devices.Count -gt 0) { $Device = $devices[0] }
Check 'D1' '设备在线' ($devices.Count -gt 0) $(if ($Device) { $Device } else { '无在线设备' })

if ($devices.Count -eq 0) {
    ''
    "无法继续设备断言（无设备）。静态断言结果：通过 $script:passed / 失败 $script:failed"
    exit $script:failed
}

$pkgInfo = (& $AdbPath -s $Device shell "dumpsys package $Package" 2>$null) -join "`n"
$versionName = if ($pkgInfo -match 'versionName=(\S+)') { $Matches[1] } else { '' }
Check 'D2' '应用已安装' ($versionName -ne '') "versionName=$versionName"

# 设备 ABI：v5 会按 ABI 选 native 变体，设备无对应 ABI 时插件不可用
$abiList = (& $AdbPath -s $Device shell getprop ro.product.cpu.abilist) -join ',' -replace '\s', ''
Check 'D3' '设备含 arm64-v8a' ($abiList -match 'arm64-v8a') "abilist=$abiList"

# 建立端口转发并取 state
& $AdbPath -s $Device forward "tcp:$Port" "tcp:$Port" 2>&1 | Out-Null
$state = $null
for ($i = 1; $i -le 10; $i++) {
    try {
        $resp = Invoke-WebRequest "http://127.0.0.1:$Port/api/state" -TimeoutSec 3
        $state = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray()) | ConvertFrom-Json
        break
    } catch { Start-Sleep -Seconds 2 }
}
Check 'D4' '管理接口可达（应用已启动）' ($null -ne $state) "http://127.0.0.1:$Port/api/state"

if ($state) {
    $plugin = $state.cjsPlugin
    Check 'D5' 'CJS 站点目录已安装' ($plugin.installed -eq $true) "abi=$($plugin.abi) sites=$($plugin.sites.Count)"
    $expectAbi = if ($abiList -match 'arm64-v8a') { 'arm64-v8a' } else { 'armeabi-v7a' }
    Check 'D6' '插件 ABI 与设备匹配' ($plugin.abi -eq $expectAbi) "plugin=$($plugin.abi) device=$expectAbi"

    Check 'D7' '自动回退已开启' ($state.settings.autoSwitchSource -eq $true) 'settings.autoSwitchSource'

    # 内置源分组应与 assets 静态解析一致
    $builtinGroups = @($state.groups | Where-Object { $_.name -in @('央视频道', '卫视频道') })
    $liveChannels = ($builtinGroups | ForEach-Object { $_.channels.Count } | Measure-Object -Sum).Sum
    $liveMulti = @($builtinGroups | ForEach-Object { $_.channels } | Where-Object { $_.sourceCount -ge 2 }).Count
    Check 'D8' '内置源频道数与 assets 一致' ($liveChannels -eq $expectedChannels) "设备=$liveChannels 期望=$expectedChannels"
    Check 'D9' '多线路频道数与 assets 一致' ($liveMulti -eq $expectedMulti) "设备=$liveMulti 期望=$expectedMulti"
}

# 设备侧文件：v5 目录存在、v4 目录不存在
$files = (& $AdbPath -s $Device shell "run-as $Package ls files/ 2>/dev/null") -join "`n"
Check 'D10' 'files/cjs-sites-v5 存在' ($files -match 'cjs-sites-v5') ''
Check 'D11' 'files/cjs-sites-v4 不存在（升级不加载旧缓存）' (-not ($files -match 'cjs-sites-v4')) ''

$prefs = (& $AdbPath -s $Device shell "run-as $Package ls shared_prefs/ 2>/dev/null") -join "`n"
Check 'D12' 'shared_prefs 无 cjs_sites_v4' (-not ($prefs -match 'cjs_sites_v4')) ''

# 设备上 catalog.json 的 protocol 必须是 5
$catalogRaw = (& $AdbPath -s $Device shell "run-as $Package cat files/cjs-sites-v5/catalog.json 2>/dev/null") -join "`n"
if ($catalogRaw -and $catalogRaw.Trim().StartsWith('{')) {
    $catalog = $catalogRaw | ConvertFrom-Json
    Check 'D13' '设备 catalog.protocol = 5' ($catalog.protocol -eq 5) "protocol=$($catalog.protocol)"
} else {
    Check 'D13' '设备 catalog.protocol = 5' $false '读不到 catalog.json（应用可能未初始化插件目录）'
}

# ---------------- P 播放断言（可选） ----------------
if ($WithPlayback) {
    ''
    '--- P. 播放断言（真起一次流）---'
    if (-not $state) {
        Check 'P0' '播放断言前置（管理接口可达）' $false '跳过'
    } else {
        $gi = -1
        for ($i = 0; $i -lt $state.groups.Count; $i++) { if ($state.groups[$i].name -eq '央视频道') { $gi = $i } }
        if ($gi -lt 0) { $gi = 0 }

        & $AdbPath -s $Device shell logcat -c 2>$null | Out-Null
        $t0 = Get-Date
        Invoke-WebRequest "http://127.0.0.1:$Port/api/control" -Method Post `
            -Body (@{ action = 'play'; group = $gi; channel = 0 } | ConvertTo-Json -Compress) `
            -ContentType 'application/json' -TimeoutSec $TimeoutSec | Out-Null

        $frameAt = -1
        for ($i = 0; $i -lt ($PlaybackSeconds * 2); $i++) {
            Start-Sleep -Milliseconds 500
            $log = & $AdbPath -s $Device logcat -d -t 4000 2>$null
            if ($log | Select-String 'First video frame rendered') {
                $frameAt = [int]((Get-Date) - $t0).TotalMilliseconds
                break
            }
        }
        Check 'P1' '抽到一个频道能起首帧' ($frameAt -ge 0) $(if ($frameAt -ge 0) { "${frameAt}ms" } else { ">${PlaybackSeconds}s 未出帧" })

        # 选档日志只在多码率主列表出现，单码率源不会有——因此仅作信息输出，不计入失败。
        # 「选档日志语句存在」由静态断言 S10 保证，这里只用于观察实际档位。
        $log = (& $AdbPath -s $Device logcat -d -t 4000 2>$null) -join "`n"
        if ($log -match 'Selected HLS variant quality=(\S+)[^\n]*advertised=(\S+)') {
            "[INFO]  P2 画质档位                                   quality=$($Matches[1]) advertised=$($Matches[2])"
        } else {
            '[INFO]  P2 画质档位                                   本频道为单码率源，无选档日志'
        }
    }
}

# ---------------- 汇总 ----------------
''
"=== 结果：通过 $script:passed / 失败 $script:failed ==="
if ($script:failed -gt 0) {
    '存在失败项：改动前请先让回归全绿，否则可能已破坏 I1 已验证的行为。'
}
exit $script:failed
