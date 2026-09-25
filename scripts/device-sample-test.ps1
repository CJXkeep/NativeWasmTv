# 设备侧抽样切台实测：首帧耗时 + 15s 流量
# 前置：设备已启动应用，且宿主已执行 adb forward tcp:9966 tcp:9966
# 用法：pwsh -File device-sample-test.ps1 -Targets 东方卫视,安徽卫视 -SampleSeconds 15

param(
    [string[]]$Targets = @('CCTV-1 综合', 'CCTV-4', '东方卫视', '安徽卫视', '北京卫视', '安多卫视', '澳门卫视'),
    [int]$GroupIndex = 3,
    [int]$SampleSeconds = 15,
    [string]$GroupName = '筛选源'
)

$ErrorActionPreference = 'Stop'
$ADB = 'D:\Program Files\Netease\MuMu Player 12\nx_main\adb.exe'
$DEV = '127.0.0.1:16416'
$BASE = 'http://127.0.0.1:9966'
$NET = '/sys/class/net/wlan0/statistics/rx_bytes'

function Invoke-State {
    $r = Invoke-WebRequest "$BASE/api/state" -TimeoutSec 30
    [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
}

function Get-RxBytes {
    $v = (& $ADB -s $DEV shell cat $NET) -replace '\D', ''
    [int64]$v
}

function Invoke-Play([int]$group, [int]$channel) {
    $body = '{"action":"play","group":' + $group + ',"channel":' + $channel + '}'
    Invoke-WebRequest "$BASE/api/control" -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 20 | Out-Null
}

$state = Invoke-State
$group = $state.groups | Where-Object { $_.name -eq $GroupName }
if (-not $group) { throw "未找到分组 $GroupName" }
$channels = $group.channels
"分组=$GroupName 频道数=$($channels.Count) 采样时长=${SampleSeconds}s"
""
"{0,-16} {1,-6} {2,-12} {3,-10} {4,-12} {5}" -f '频道', '线路', '首帧(ms)', '流量(MB)', '分辨率', '结果'

foreach ($name in $Targets) {
    $idx = -1
    for ($k = 0; $k -lt $channels.Count; $k++) {
        if ($channels[$k].name -eq $name) { $idx = $k; break }
    }
    if ($idx -lt 0) {
        "{0,-16} {1}" -f $name, 'SKIP(未找到)'
        continue
    }

    & $ADB -s $DEV logcat -c
    $rx0 = Get-RxBytes
    $t0 = Get-Date
    Invoke-Play $GroupIndex $idx

    $ms = -1
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        $o = & $ADB -s $DEV logcat -d -t 2000 2>$null | Select-String 'First video frame'
        if ($o) { $ms = [int]((Get-Date) - $t0).TotalMilliseconds; break }
    }

    Start-Sleep -Seconds $SampleSeconds
    $mb = [math]::Round((Get-RxBytes) - $rx0) / 1MB
    $mb = [math]::Round($mb, 2)

    # 播放器实测分辨率：画质最可靠的判据（不受源声明影响）
    $resolution = '-'
    try {
        $st = Invoke-State
        if ($st.video.width -gt 0) { $resolution = "{0}x{1}" -f $st.video.width, $st.video.height }
    }
    catch { }

    $verdict = if ($ms -ge 0) { 'OK' } else { 'NOFRAME' }
    "{0,-16} {1,-6} {2,-12} {3,-10} {4,-12} {5}" -f $name, $channels[$idx].sourceCount, $ms, $mb, $resolution, $verdict
}
