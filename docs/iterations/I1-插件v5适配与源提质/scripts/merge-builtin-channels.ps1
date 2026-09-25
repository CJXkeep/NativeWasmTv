# 把聚合筛选线路并入内置源，保持原有条目顺序不变（追加在频道尾部）
# 规则：官方 CDN / webview 条目原样保留在前，聚合线路按 画质档→线路类别→延迟 排序后追加
# 画质优先（75 寸电视场景）：>=1080p 最前，720p 次之，未知再次，<720p 直接剔除
# 用法：pwsh -File merge-builtin-channels.ps1                      # 预演，只打印统计
#       pwsh -File merge-builtin-channels.ps1 -AddUnmatched -Apply  # 写回 builtin_channels.txt

param(
    [string]$Builtin = 'd:\WorkStation\TVNY\app\src\main\assets\builtin_channels.txt',
    [string]$Filtered = 'd:\WorkStation\TVNY\cctv-filtered.m3u',
    [string]$QualityJson = 'd:\WorkStation\TVNY\docs\iterations\I1-插件v5适配与源提质\reports\line-quality.json',
    [string]$ReportPath = 'd:\WorkStation\TVNY\docs\iterations\I1-插件v5适配与源提质\reports\merge-result.json',
    [string]$UnmatchedGroup = '卫视频道',
    [int]$MinHeight = 720,
    [switch]$AddUnmatched,
    [switch]$KeepUnverified,
    [switch]$Apply
)

# 画质表：url -> 实测/声明高度
$quality = @{}
$qualityStats = @{ known = 0; unknown = 0; low = 0; tested = 0 }
if (Test-Path $QualityJson) {
    $q = Get-Content -Raw -Encoding UTF8 $QualityJson | ConvertFrom-Json
    foreach ($row in $q) {
        $qualityStats.tested++
        $quality[$row.url] = [int]$row.height
    }
}

# 画质档：0 = >=1080p，1 = >=720p，2 = 未知（仅在 -KeepUnverified 时保留），9 = 剔除
# 默认剔除"未验证到视频流"的线路：播放列表可拿但分片 404/超时的源占聚合源大多数，
# 它们正是低画质与高失败率的主要来源（宿主侧只校验 #EXTM3U 会把它们误判为可用）。
function Get-QualityRank([string]$url) {
    $h = 0
    if ($quality.ContainsKey($url)) { $h = $quality[$url] }
    if ($h -ge 1080) { return 0 }
    if ($h -ge $MinHeight) { return 1 }
    if ($h -le 0) { if ($KeepUnverified) { return 2 } else { return 9 } }
    return 9
}

$ErrorActionPreference = 'Stop'

# 内置源里名字与聚合源不一致的频道
$aliases = @{
    '东南卫视' = '福建东南卫视'
}

# 剔除规则（对齐 D:/WorkStation/my-point/MyTv/my-tvForTest 的过滤逻辑）
$rejectPatterns = @(
    'raw\.githubusercontent\.com',   # 实测为 YouTube 播放列表（假源）
    'auth=',                          # 临时授权参数
    'testpub',
    # 黑名单域名，含所有子域（TVList.BLOCKED_HOSTS）
    '//[^/]*iill\.top',
    # 疑似录像轮播 / 点播（TVList.isLoopSuspect / isVodUrl）：不是真直播
    'kwimgs',                         # 快手 CDN 轮播
    '/video-hls/',                    # 点播转直播常见路径
    '\.mp4(\?|$)',
    '\.mkv(\?|$)',
    '\.avi(\?|$)'
)

function Get-ChannelKey([string]$tvgId, [string]$title) {
    if ($tvgId) { return "id:$tvgId" }
    return "title:$title"
}

function Resolve-TargetKey([string]$name) {
    $n = $name.Trim()
    if ($n -match '^CCTV-?(\d+)(\+)?$') {
        return "id:CCTV$($Matches[1])$($Matches[2])"
    }
    if ($aliases.ContainsKey($n)) { return "title:$($aliases[$n])" }
    return "title:$n"
}

# ---------- 解析内置源 ----------
$builtinLines = Get-Content -Encoding UTF8 $Builtin
$records = New-Object System.Collections.ArrayList   # {Header, Line, Key, IsEntry}
$lastKeyIndex = @{}                                   # Key -> 该频道最后一条记录的下标

for ($i = 0; $i -lt $builtinLines.Count; $i++) {
    $line = $builtinLines[$i]
    if ($line -match '^\s*#EXTINF') {
        $tvgId = ''
        if ($line -match 'tvg-id="([^"]*)"') { $tvgId = $Matches[1] }
        $title = ($line -split ',', 2)[1].Trim()
        $key = Get-ChannelKey $tvgId $title
        [void]$records.Add([pscustomobject]@{ Header = $line; Url = ''; Key = $key })
        $lastKeyIndex[$key] = $records.Count - 1
        if ($i + 1 -lt $builtinLines.Count -and $builtinLines[$i + 1] -notmatch '^\s*#') {
            $i++
            $records[$records.Count - 1].Url = $builtinLines[$i].Trim()
        }
    }
    elseif ($line.Trim() -eq '') {
        [void]$records.Add([pscustomobject]@{ Header = ''; Url = ''; Key = '' })
    }
    else {
        [void]$records.Add([pscustomobject]@{ Header = $line; Url = ''; Key = '' })
    }
}

$builtinUrls = @{}
foreach ($r in $records) { if ($r.Url) { $builtinUrls[$r.Url] = $true } }

# ---------- 解析聚合筛选源 ----------
$filteredLines = Get-Content -Encoding UTF8 $Filtered
$pending = @{}      # Key -> List of {Url, Class, Delay}
$unmatched = @{}
$rejected = 0
$lowQuality = 0
$current = $null

foreach ($line in $filteredLines) {
    $l = $line.Trim()
    if ($l -match '^#EXTINF') {
        $title = ($l -split ',', 2)[1].Trim()
        $delay = 999999
        if ($title -match '\[[^\]]*?(\d+)ms\]') { $delay = [int]$Matches[1] }
        $name = ($title -replace '\s*\[[^\]]*\]\s*$', '').Trim()
        $current = [pscustomobject]@{ Name = $name; Delay = $delay }
        continue
    }
    if ($l -match '^https?://' -and $current) {
        $url = $l
        $skip = $false
        foreach ($p in $rejectPatterns) { if ($url -match $p) { $skip = $true } }
        if ($skip) { $rejected++; $current = $null; continue }
        if ($builtinUrls.ContainsKey($url)) { $current = $null; continue }

        $qRank = Get-QualityRank $url
        if ($qRank -eq 9) { $lowQuality++; $current = $null; continue }   # 实测低于阈值，剔除

        $class = if ($url -match '^https://' -and $url -notmatch '^https?://\d+\.\d+\.\d+\.\d+') { 0 }
                 elseif ($url -match '^https?://\d+\.\d+\.\d+\.\d+') { 2 }
                 else { 1 }
        $key = Resolve-TargetKey $current.Name
        if (-not $pending.ContainsKey($key)) { $pending[$key] = New-Object System.Collections.ArrayList }
        [void]$pending[$key].Add([pscustomobject]@{
                Url = $url; Class = $class; Delay = $current.Delay; QRank = $qRank
                Height = $(if ($quality.ContainsKey($url)) { $quality[$url] } else { 0 })
            })
        $current = $null
    }
}

# ---------- 汇总 ----------
$applied = @()
$missed = @()
foreach ($key in $pending.Keys) {
    if ($lastKeyIndex.ContainsKey($key)) { $applied += $key } else { $missed += $key }
}

"内置频道键数: $($lastKeyIndex.Keys.Count)"
"匹配到内置频道的聚合频道: $($applied.Count)"
"未匹配（不新增）: $($missed.Count) -> " + (($missed | Sort-Object) -join ', ')
"因特征被剔除的线路: $rejected"
"因画质低于 ${MinHeight}p 被剔除的线路: $lowQuality"
$addCount = 0
foreach ($key in $applied) { $addCount += $pending[$key].Count }
"将追加线路数: $addCount"

$qDist = @{}
foreach ($key in $pending.Keys) {
    foreach ($cand in $pending[$key]) {
        $label = switch ($cand.QRank) { 0 { '>=1080p' } 1 { ">=${MinHeight}p" } 2 { '未知画质' } }
        if (-not $qDist.ContainsKey($label)) { $qDist[$label] = 0 }
        $qDist[$label]++
    }
}
"画质档分布: " + (($qDist.Keys | Sort-Object | ForEach-Object { "$_=$($qDist[$_])" }) -join ' ')

if (-not $Apply) {
    "预演结束（未写回）。加 -Apply 生效。"
    return
}

# ---------- 写回 ----------
$out = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $records.Count; $i++) {
    $r = $records[$i]
    [void]$out.Add($r.Header)
    if ($r.Url) { [void]$out.Add($r.Url) }

    if ($r.Key -and $lastKeyIndex[$r.Key] -eq $i -and $applied -contains $r.Key) {
        $sorted = $pending[$r.Key] | Sort-Object QRank, Class, Delay
        foreach ($cand in $sorted) {
            [void]$out.Add($r.Header)
            [void]$out.Add($cand.Url)
        }
        $applied = $applied | Where-Object { $_ -ne $r.Key }
    }
}

# 未匹配的聚合频道：作为新频道追加到文件末尾的指定分组
$addedChannels = 0
if ($AddUnmatched) {
    $extras = @($pending.Keys | Where-Object { -not $lastKeyIndex.ContainsKey($_) } | Sort-Object)
    if ($extras.Count -gt 0) {
        [void]$out.Add('')
        foreach ($key in $extras) {
            $title = $key -replace '^title:', ''
            foreach ($cand in ($pending[$key] | Sort-Object QRank, Class, Delay)) {
                [void]$out.Add("#EXTINF:-1 group-title=""$UnmatchedGroup"",$title")
                [void]$out.Add($cand.Url)
            }
            $addedChannels++
        }
    }
}
"新增频道数: $addedChannels"

Set-Content -Path $Builtin -Value $out -Encoding utf8

$detail = foreach ($key in ($pending.Keys | Sort-Object)) {
    [pscustomobject]@{
        key      = $key
        added    = if ($lastKeyIndex.ContainsKey($key)) { $pending[$key].Count } else { 0 }
        matched  = $lastKeyIndex.ContainsKey($key)
    }
}
$detail | ConvertTo-Json -Depth 4 | Set-Content -Path $ReportPath -Encoding utf8
"已写回 $Builtin；明细见 $ReportPath"
