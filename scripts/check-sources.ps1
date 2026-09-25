# 源可用性巡检（无外部进程）
#
# 用途：定期检查内置源 / 任意 m3u 的劣化情况，输出 JSON 明细 + Markdown 报告，支持跨期对比。
#
# 探测方式（与播放器口径对齐）：
#   master playlist → 取声明的最高 RESOLUTION / BANDWIDTH
#   media playlist  → 依次尝试首/次/末分片，解析 MPEG-TS 里的 H.264 SPS 得到真实分辨率
#   webview:// 条目  → 站点页面解析，无法在宿主侧直连探测，单独统计
#
# 重要：本脚本是**宿主侧**口径，与设备侧实测不等价——实测中多次出现
#       「宿主判 probe-failed 但设备侧能播」（如 198.204.228.26 分片 404 仍可播）。
#       因此巡检结果用于**发现劣化趋势**，不作为「设备侧可用」的唯一依据；
#       写入内置源前必须用 device-sample-test.ps1 做设备侧确认。
#
# 参数取值（2026-09-25 实测教训，务必先读）：
#   并发 32 + 超时 4000ms  → 35 条里误判 28 条 unreachable（可用率 20%），耗时 12.4s
#   并发  8 + 超时 15000ms → 35/35 全部可用，耗时 4.1s
#   原因：探测是「阻塞式等待 HTTP」（sync-over-async），并发过高会互相拖慢并集体撞上超时窗口，
#         表现为「并发越高、越慢、误判越多」。低并发既更准也更快。
#   调参时以「可用率 + 耗时」双指标验证，不要盲目加并发。
#
# 用法：
#   pwsh -File check-sources.ps1                                  # 巡检内置源
#   pwsh -File check-sources.ps1 -Playlist <m3u> -OutDir <dir>
#   pwsh -File check-sources.ps1 -Baseline docs/reports/check-<date>.json   # 跨期对比
#   pwsh -File check-sources.ps1                                            # 退出码：0=发布门禁通过，1=存在「全死且无 webview 兜底」的频道

param(
    [string]$Playlist = "$PSScriptRoot\..\app\src\main\assets\builtin_channels.txt",
    [string]$OutDir = "$PSScriptRoot\..\docs\reports",
    [int]$Parallel = 8,
    [int]$TimeoutMs = 15000,
    [string]$Baseline = '',
    [switch]$NoReport
)

$ErrorActionPreference = 'Continue'

if (-not ('LineQualityProbe' -as [type])) {
    Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

public static class LineQualityProbe
{
    public class Item
    {
        public string group = "";
        public string name = "";
        public string url = "";
        public int width;
        public int height;
        public int bandwidth;
        public int ttfbMs;
        public string kind = "";
    }

    static readonly Regex StreamInf = new Regex("#EXT-X-STREAM-INF", RegexOptions.Compiled);
    static readonly Regex ResBw = new Regex("RESOLUTION=(\\d+)x(\\d+)[^\\n]*?BANDWIDTH=(\\d+)", RegexOptions.Compiled);
    static readonly Regex BwRes = new Regex("BANDWIDTH=(\\d+)[^\\n]*?RESOLUTION=(\\d+)x(\\d+)", RegexOptions.Compiled);

    public static string Run(string[] groups, string[] names, string[] urls, int parallel, int timeoutMs)
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.All
        };
        var items = new Item[urls.Length];
        using (var client = new HttpClient(handler))
        {
            client.Timeout = TimeSpan.FromMilliseconds(timeoutMs);
            client.DefaultRequestHeaders.UserAgent.ParseAdd("nTv/1.0");
            for (int i = 0; i < urls.Length; i++)
                items[i] = new Item { group = groups[i], name = names[i], url = urls[i] };

            var options = new ParallelOptions { MaxDegreeOfParallelism = parallel };
            Parallel.For(0, urls.Length, options, i =>
            {
                try { Probe(client, items[i]); }
                catch (Exception e) { items[i].kind = "error:" + e.GetType().Name; }
            });
        }

        var sb = new StringBuilder();
        sb.Append("[");
        for (int i = 0; i < items.Length; i++)
        {
            if (i > 0) sb.Append(",");
            sb.Append("{\"group\":\"").Append(Escape(items[i].group))
              .Append("\",\"name\":\"").Append(Escape(items[i].name))
              .Append("\",\"url\":\"").Append(Escape(items[i].url))
              .Append("\",\"width\":").Append(items[i].width)
              .Append(",\"height\":").Append(items[i].height)
              .Append(",\"bandwidth\":").Append(items[i].bandwidth)
              .Append(",\"ttfbMs\":").Append(items[i].ttfbMs)
              .Append(",\"kind\":\"").Append(Escape(items[i].kind)).Append("\"}");
        }
        sb.Append("]");
        return sb.ToString();
    }

    static string Escape(string s)
    {
        if (s == null) return "";
        return s.Replace("\\", "\\\\").Replace("\"", "\\\"");
    }

    static void Probe(HttpClient client, Item it)
    {
        var sw = Stopwatch.StartNew();
        string body = FetchText(client, it.url, 65536);
        sw.Stop();
        it.ttfbMs = (int)sw.ElapsedMilliseconds;
        if (body == null) { it.kind = "unreachable"; return; }

        if (StreamInf.IsMatch(body))
        {
            BestVariant(body, it);
            it.kind = it.height > 0 ? "master" : "master-nores";
            if (it.height > 0) return;
        }

        if (body.Contains("#EXT-X-KEY") && !body.Contains("METHOD=NONE")) { it.kind = "encrypted"; return; }

        var segments = ExtractSegments(body);
        if (segments.Count == 0) { it.kind = "no-segments"; return; }

        bool fmp4 = body.Contains("#EXT-X-MAP");

        // 候选顺序：首个（最可能已生成）→ 第二个 → 末尾（部分源末尾已过期返回 404）
        var candidates = new List<string>();
        candidates.Add(segments[0]);
        if (segments.Count > 1) candidates.Add(segments[1]);
        if (segments.Count > 2 && segments[segments.Count - 1] != segments[0])
            candidates.Add(segments[segments.Count - 1]);

        foreach (string rel in candidates)
        {
            string segUrl = Abs(it.url, rel);
            if (segUrl == null) continue;
            byte[] data = FetchBytes(client, segUrl, 131072);
            if (data == null || data.Length < 1024) continue;
            int w, h;
            if (TryParseTs(data, out w, out h))
            {
                it.width = w; it.height = h;
                it.kind = "ts-sps";
                return;
            }
        }
        it.kind = fmp4 ? "fmp4-no-probe" : "probe-failed";
    }

    static void BestVariant(string body, Item it)
    {
        int best = 0;
        foreach (Match m in ResBw.Matches(body))
        {
            int px = int.Parse(m.Groups[1].Value) * int.Parse(m.Groups[2].Value);
            if (px > best)
            {
                best = px;
                it.width = int.Parse(m.Groups[1].Value);
                it.height = int.Parse(m.Groups[2].Value);
                it.bandwidth = int.Parse(m.Groups[3].Value);
            }
        }
        foreach (Match m in BwRes.Matches(body))
        {
            int px = int.Parse(m.Groups[2].Value) * int.Parse(m.Groups[3].Value);
            if (px > best)
            {
                best = px;
                it.width = int.Parse(m.Groups[2].Value);
                it.height = int.Parse(m.Groups[3].Value);
                it.bandwidth = int.Parse(m.Groups[1].Value);
            }
        }
    }

    static List<string> ExtractSegments(string body)
    {
        var list = new List<string>();
        foreach (string raw in body.Split('\n'))
        {
            string line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("#")) continue;
            if (line.EndsWith(".m3u8") || line.EndsWith(".m3u")) continue;
            list.Add(line);
        }
        return list;
    }

    static string Abs(string baseUrl, string rel)
    {
        try { return new Uri(new Uri(baseUrl), rel.Trim()).AbsoluteUri; }
        catch { return null; }
    }

    static string FetchText(HttpClient client, string url, int maxBytes)
    {
        byte[] data = FetchBytes(client, url, maxBytes);
        return data == null ? null : Encoding.UTF8.GetString(data);
    }

    static byte[] FetchBytes(HttpClient client, string url, int maxBytes)
    {
        try
        {
            using (var req = new HttpRequestMessage(HttpMethod.Get, url))
            using (var resp = client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult())
            {
                if (!resp.IsSuccessStatusCode) return null;
                using (var stream = resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
                using (var ms = new MemoryStream())
                {
                    byte[] buffer = new byte[65536];
                    int total = 0;
                    while (total < maxBytes)
                    {
                        int want = Math.Min(buffer.Length, maxBytes - total);
                        int read = stream.Read(buffer, 0, want);
                        if (read <= 0) break;
                        ms.Write(buffer, 0, read);
                        total += read;
                    }
                    byte[] result = ms.ToArray();
                    return result.Length == 0 ? null : result;
                }
            }
        }
        catch { return null; }
    }

    static bool TryParseTs(byte[] data, out int width, out int height)
    {
        width = 0; height = 0;
        int start = -1;
        for (int i = 0; i + 376 < data.Length; i++)
        {
            if (data[i] == 0x47 && data[i + 188] == 0x47 && data[i + 376] == 0x47) { start = i; break; }
        }
        if (start < 0) return false;

        var es = new MemoryStream();
        int pos = start;
        while (pos + 188 <= data.Length)
        {
            if (data[pos] != 0x47) { pos++; continue; }
            bool afPresent = (data[pos + 1] & 0x20) != 0;
            int off = pos + 4;
            if (afPresent)
            {
                if (off >= data.Length) break;
                int afLen = data[off];
                off += 1 + afLen;
            }
            int end = pos + 188;
            if (off < end) es.Write(data, off, end - off);
            pos += 188;
        }
        byte[] buf = es.ToArray();
        if (buf.Length < 16) return false;

        for (int i = 0; i + 5 < buf.Length; i++)
        {
            if (buf[i] != 0 || buf[i + 1] != 0 || buf[i + 2] != 0 || buf[i + 3] != 1) continue;
            int type = buf[i + 4] & 0x1F;
            if (type != 7) continue;
            int nalStart = i + 4;
            int nalEnd = buf.Length;
            for (int j = nalStart + 4; j + 3 < buf.Length; j++)
            {
                if (buf[j] == 0 && buf[j + 1] == 0 && buf[j + 2] == 0 && buf[j + 3] == 1) { nalEnd = j; break; }
            }
            int w, h;
            if (ParseSps(buf, nalStart, nalEnd, out w, out h)) { width = w; height = h; return true; }
        }
        return false;
    }

    static bool ParseSps(byte[] data, int start, int end, out int width, out int height)
    {
        width = 0; height = 0;
        var rbsp = new List<byte>(end - start);
        int zeros = 0;
        for (int i = start; i < end; i++)
        {
            byte b = data[i];
            if (zeros >= 2 && b == 0x03) { zeros = 0; continue; }
            rbsp.Add(b);
            if (b == 0) zeros++; else zeros = 0;
        }
        int bitPos = 8;
        try
        {
            int profileIdc = ReadBits(rbsp, ref bitPos, 8);
            ReadBits(rbsp, ref bitPos, 8);
            ReadBits(rbsp, ref bitPos, 8);
            ReadUe(rbsp, ref bitPos);

            int chromaFormatIdc = 1;
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 ||
                profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 ||
                profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134 ||
                profileIdc == 135)
            {
                chromaFormatIdc = ReadUe(rbsp, ref bitPos);
                if (chromaFormatIdc == 3) ReadBits(rbsp, ref bitPos, 1);
                ReadUe(rbsp, ref bitPos);
                ReadUe(rbsp, ref bitPos);
                ReadBits(rbsp, ref bitPos, 1);
                if (ReadBits(rbsp, ref bitPos, 1) != 0)
                {
                    int count = chromaFormatIdc != 3 ? 8 : 12;
                    for (int i = 0; i < count; i++)
                    {
                        if (ReadBits(rbsp, ref bitPos, 1) != 0) SkipScalingList(rbsp, ref bitPos, i < 6 ? 16 : 64);
                    }
                }
            }

            ReadUe(rbsp, ref bitPos);
            int pocType = ReadUe(rbsp, ref bitPos);
            if (pocType == 0) ReadUe(rbsp, ref bitPos);
            else if (pocType == 1)
            {
                ReadBits(rbsp, ref bitPos, 1);
                ReadSe(rbsp, ref bitPos);
                ReadSe(rbsp, ref bitPos);
                int num = ReadUe(rbsp, ref bitPos);
                for (int i = 0; i < num; i++) ReadSe(rbsp, ref bitPos);
            }
            ReadUe(rbsp, ref bitPos);
            ReadBits(rbsp, ref bitPos, 1);

            int picWidthInMbs = ReadUe(rbsp, ref bitPos) + 1;
            int picHeightInMapUnits = ReadUe(rbsp, ref bitPos) + 1;
            int frameMbsOnly = ReadBits(rbsp, ref bitPos, 1);
            if (frameMbsOnly == 0) ReadBits(rbsp, ref bitPos, 1);
            ReadBits(rbsp, ref bitPos, 1);

            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (ReadBits(rbsp, ref bitPos, 1) != 0)
            {
                cropLeft = ReadUe(rbsp, ref bitPos);
                cropRight = ReadUe(rbsp, ref bitPos);
                cropTop = ReadUe(rbsp, ref bitPos);
                cropBottom = ReadUe(rbsp, ref bitPos);
            }

            int subWidthC = chromaFormatIdc == 3 ? 1 : 2;
            int subHeightC = chromaFormatIdc == 1 ? 2 : 1;
            width = picWidthInMbs * 16 - (cropLeft + cropRight) * subWidthC;
            height = (2 - frameMbsOnly) * picHeightInMapUnits * 16
                     - (cropTop + cropBottom) * subHeightC * (2 - frameMbsOnly);
            return width > 0 && height > 0;
        }
        catch { return false; }
    }

    static void SkipScalingList(List<byte> data, ref int bitPos, int size)
    {
        int lastScale = 8, nextScale = 8;
        for (int j = 0; j < size; j++)
        {
            if (nextScale != 0)
            {
                int delta = ReadSe(data, ref bitPos);
                nextScale = (lastScale + delta + 256) % 256;
            }
            lastScale = nextScale == 0 ? lastScale : nextScale;
        }
    }

    static int ReadBits(List<byte> data, ref int bitPos, int count)
    {
        int value = 0;
        for (int i = 0; i < count; i++)
        {
            int byteIndex = bitPos >> 3;
            if (byteIndex >= data.Count) throw new InvalidOperationException("eof");
            int bit = (data[byteIndex] >> (7 - (bitPos & 7))) & 1;
            value = (value << 1) | bit;
            bitPos++;
        }
        return value;
    }

    static int ReadUe(List<byte> data, ref int bitPos)
    {
        int zeros = 0;
        while (ReadBits(data, ref bitPos, 1) == 0) zeros++;
        if (zeros == 0) return 0;
        return (1 << zeros) - 1 + ReadBits(data, ref bitPos, zeros);
    }

    static int ReadSe(List<byte> data, ref int bitPos)
    {
        int ue = ReadUe(data, ref bitPos);
        int sign = (ue & 1) == 1 ? 1 : -1;
        return sign * ((ue + 1) / 2);
    }
}
'@
}

# ---------- 解析 playlist ----------
$Playlist = (Resolve-Path $Playlist).Path
$lines = Get-Content -Encoding UTF8 $Playlist
$groups = New-Object System.Collections.ArrayList
$names = New-Object System.Collections.ArrayList
$urls = New-Object System.Collections.ArrayList
$channelTotal = @{}      # 频道名 -> 线路总数（含 webview）
$channelWebview = @{}    # 频道名 -> webview 条目数
$group = ''
$name = ''
$webviewCount = 0
foreach ($line in $lines) {
    $l = $line.Trim()
    if ($l -match '^#EXTINF') {
        if ($l -match 'group-title="([^"]*)"') { $group = $Matches[1] }
        $name = (($l -split ',', 2)[1]).Trim() -replace '\s*\[[^\]]*\]\s*$', ''
        continue
    }
    if ($l -match '^webview://' -and $name) {
        $webviewCount++
        $channelTotal[$name] = 1 + [int]$channelTotal[$name]
        $channelWebview[$name] = 1 + [int]$channelWebview[$name]
        $name = ''
        continue
    }
    if ($l -match '^https?://' -and $name) {
        [void]$groups.Add($group); [void]$names.Add($name); [void]$urls.Add($l)
        $channelTotal[$name] = 1 + [int]$channelTotal[$name]
        $name = ''
    }
}

$directCount = $urls.Count
"巡检输入: $Playlist"
"直连线路: $directCount     webview 条目: $webviewCount     频道: $($channelTotal.Count)"
"探测中（并发 $Parallel，超时 ${TimeoutMs}ms，无外部进程）…"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$json = if ($directCount -gt 0) {
    [LineQualityProbe]::Run($groups.ToArray(), $names.ToArray(), $urls.ToArray(), $Parallel, $TimeoutMs)
} else { '[]' }
$sw.Stop()
"耗时: $([math]::Round($sw.Elapsed.TotalSeconds,1))s"

$result = $json | ConvertFrom-Json
if ($null -eq $result) { $result = @() }
$result = @($result)

# ---------- 落盘明细 ----------
if (-not (Test-Path $OutDir)) { [void](New-Item -ItemType Directory -Path $OutDir -Force) }
$stamp = Get-Date -Format 'yyyy-MM-dd'
# 文件名带上输入标识，避免同一天巡检不同 playlist 时互相覆盖
$tag = [System.IO.Path]::GetFileNameWithoutExtension($Playlist)
$jsonPath = Join-Path $OutDir "check-$tag-$stamp.json"
$result | ConvertTo-Json -Depth 4 | Set-Content -Path $jsonPath -Encoding utf8
"明细已写出: $jsonPath"

# ---------- 统计 ----------
$ok = @($result | Where-Object { $_.height -gt 0 })
$failed = @($result | Where-Object { $_.height -le 0 })
# 能解析出尺寸 ≠ 画质达标：SPS 偶尔会从异常数据解析出 128x32 / 224x32 这类荒谬尺寸，
# 因此单独统计低于画质下限（默认 720p）的线路，报告里把「可用」与「画质达标」分开看。
$minHeight = 720
$okHigh = @($ok | Where-Object { $_.height -ge $minHeight })
$okLow = @($ok | Where-Object { $_.height -gt 0 -and $_.height -lt $minHeight })
$okRate = if ($directCount -gt 0) { [math]::Round(100.0 * $ok.Count / $directCount, 1) } else { 0 }
$okRateHigh = if ($directCount -gt 0) { [math]::Round(100.0 * $okHigh.Count / $directCount, 1) } else { 0 }

# 已知风险域名：命中即需人工确认（录像轮播、点播转直播、盗链站的常见宿主）
$blacklist = @('iill.top', 'kwimgs.com', 'jdshipin.com')
$blacklisted = @($result | Where-Object {
        $u = $_.url
        ($blacklist | Where-Object { $u -like "*$_*" }).Count -gt 0
    })

# 频道级：可用直连线路数
$channelOk = @{}
foreach ($it in $ok) { $channelOk[$it.name] = 1 + [int]$channelOk[$it.name] }

# 三类必须分开看，否则会把「本来就没有直连线路」误报成「直连全失败」：
#   webviewOnly   —— 频道本就没有直连线路，只依赖 webview://（正常状态，不是告警）
#   directDead    —— 有直连但全部探测失败，尚有 webview 兜底（需关注）
#   deadChannels  —— 有直连但全部失败且无 webview 兜底（严重，会直接打不开）
$webviewOnlyChannels = @()
$directDeadChannels = @()
$deadChannels = @()
foreach ($n in ($channelTotal.Keys | Sort-Object)) {
    if ([int]$channelOk[$n] -gt 0) { continue }
    $web = [int]$channelWebview[$n]
    $direct = [int]$channelTotal[$n] - $web
    if ($direct -eq 0) { $webviewOnlyChannels += $n }
    elseif ($web -gt 0) { $directDeadChannels += $n }
    else { $deadChannels += $n }
}

# 域名集中度
$domains = @{}
foreach ($it in $result) {
    try { $h = ([Uri]$it.url).Host } catch { $h = 'invalid' }
    $domains[$h] = 1 + [int]$domains[$h]
}
$topDomains = $domains.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 10

# 延迟分位（可用线路）
$ttfbs = @($ok | ForEach-Object { [int]$_.ttfbMs } | Sort-Object)
function Percentile($sorted, $p) {
    if ($sorted.Count -eq 0) { return 0 }
    $idx = [int][math]::Floor(($sorted.Count - 1) * $p)
    return $sorted[$idx]
}

# 画质分布
function Bucket([int]$h) {
    if ($h -le 0) { return '未知/失败' }
    if ($h -ge 2160) { return '≥2160p' }
    if ($h -ge 1080) { return '1080p' }
    if ($h -ge 720) { return '720p' }
    return '<720p'
}

# 跨期对比
$diffLines = @()
if ($Baseline -and (Test-Path $Baseline)) {
    $base = @(Get-Content -Raw -Encoding UTF8 $Baseline | ConvertFrom-Json)
    $baseOk = @{}
    foreach ($it in $base) { if ($it.height -gt 0) { $baseOk[$it.url] = $true } }
    $nowOk = @{}
    foreach ($it in $ok) { $nowOk[$it.url] = $true }
    $newlyFailed = @($baseOk.Keys | Where-Object { -not $nowOk.ContainsKey($_) })
    $recovered = @($nowOk.Keys | Where-Object { -not $baseOk.ContainsKey($_) })
    $diffLines += "| 新失效（基线可用 → 现在失败） | $($newlyFailed.Count) |"
    $diffLines += "| 新恢复（基线失败 → 现在可用） | $($recovered.Count) |"
    if ($newlyFailed.Count -gt 0) {
        $diffLines += ""
        $diffLines += '新失效线路：'
        $diffLines += ''
        foreach ($u in ($newlyFailed | Select-Object -First 20)) { $diffLines += "- ``$u``" }
    }
}

# ---------- Markdown 报告 ----------
$md = New-Object System.Collections.ArrayList
[void]$md.Add("# 源巡检报告（$stamp）")
[void]$md.Add("")
[void]$md.Add("- 输入：``$(Split-Path $Playlist -Leaf)``")
[void]$md.Add("- 方式：宿主侧探测，并发 $Parallel，超时 ${TimeoutMs}ms，耗时 $([math]::Round($sw.Elapsed.TotalSeconds,1))s")
[void]$md.Add("- 明细：[``check-$tag-$stamp.json``](./check-$tag-$stamp.json)")
[void]$md.Add("")
[void]$md.Add('> 口径说明：本报告是**宿主侧**结果，与设备侧实测不等价（存在「宿主判失败但设备可播」的情况）。')
[void]$md.Add('> 用于发现劣化趋势；写入内置源前须用 `device-sample-test.ps1` 做设备侧确认。')
[void]$md.Add("")
[void]$md.Add('## 一、总览')
[void]$md.Add("")
[void]$md.Add('| 指标 | 数值 |')
[void]$md.Add('| --- | --- |')
[void]$md.Add("| 频道数 | $($channelTotal.Count) |")
[void]$md.Add("| 直连线路 | $directCount |")
[void]$md.Add("| ``webview://`` 条目 | $webviewCount |")
[void]$md.Add("| └ 仅依赖 ``webview://`` 的频道（本就无直连） | $($webviewOnlyChannels.Count) |")
[void]$md.Add("| 可用（验证到视频流） | $($ok.Count) |")
[void]$md.Add("| └ 其中 ≥720p | $($okHigh.Count) |")
[void]$md.Add("| └ 其中 <720p（低于画质下限） | $($okLow.Count) |")
[void]$md.Add("| **直连可用率** | **$okRate%** |")
[void]$md.Add("| **直连可用率（≥720p）** | **$okRateHigh%** |")
[void]$md.Add("| 探测失败 | $($failed.Count) |")
[void]$md.Add("| 可用线路延迟 | p50 $((Percentile $ttfbs 0.5))ms / p90 $((Percentile $ttfbs 0.9))ms |")
[void]$md.Add("")
[void]$md.Add('## 二、画质分布')
[void]$md.Add("")
[void]$md.Add('| 画质 | 线路数 |')
[void]$md.Add('| --- | --- |')
foreach ($b in @('≥2160p', '1080p', '720p', '<720p', '未知/失败')) {
    $c = @($result | Where-Object { (Bucket ([int]$_.height)) -eq $b }).Count
    [void]$md.Add("| $b | $c |")
}
[void]$md.Add("")
[void]$md.Add('## 三、状态分布')
[void]$md.Add("")
[void]$md.Add('| kind | 数量 | 含义 |')
[void]$md.Add('| --- | --- | --- |')
$kindDesc = @{
    'master' = '多码率主列表，取声明最高档'; 'ts-sps' = '单码率，从 TS 的 SPS 解析'
    'unreachable' = 'playlist 取不到'; 'probe-failed' = 'playlist 可拿但分片取不到'
    'encrypted' = '加密流，无法直接探测'; 'no-segments' = 'playlist 无分片'
    'master-nores' = '主列表无 RESOLUTION'; 'fmp4-no-probe' = 'fMP4 分片，SPS 探测不适用'
}
foreach ($g in ($result | Group-Object kind | Sort-Object Count -Descending)) {
    $d = if ($kindDesc.ContainsKey($g.Name)) { $kindDesc[$g.Name] } else { '' }
    [void]$md.Add("| ``$($g.Name)`` | $($g.Count) | $d |")
}
[void]$md.Add("")
[void]$md.Add('## 四、域名集中度（Top 10）')
[void]$md.Add("")
[void]$md.Add('| 域名 | 线路数 |')
[void]$md.Add('| --- | --- |')
foreach ($d in $topDomains) { [void]$md.Add("| ``$($d.Key)`` | $($d.Value) |") }
[void]$md.Add("")

if ($deadChannels.Count -gt 0 -or $directDeadChannels.Count -gt 0 -or $okLow.Count -gt 0 -or $blacklisted.Count -gt 0) {
    [void]$md.Add('## 五、需关注')
    [void]$md.Add("")
    if ($deadChannels.Count -gt 0) {
        [void]$md.Add("**无任何可用线路（有直连但全部失败、且无 ``webview://`` 兜底）**：$($deadChannels.Count) 个 —— 会直接打不开")
        [void]$md.Add("")
        [void]$md.Add(($deadChannels -join '、'))
        [void]$md.Add("")
    }
    if ($directDeadChannels.Count -gt 0) {
        [void]$md.Add("**直连线路全部探测失败，仅剩 ``webview://`` 兜底**：$($directDeadChannels.Count) 个")
        [void]$md.Add("")
        [void]$md.Add(($directDeadChannels -join '、'))
        [void]$md.Add("")
    }
    if ($okLow.Count -gt 0) {
        [void]$md.Add("**能探测到但低于 ${minHeight}p（画质优先场景应剔除）**：$($okLow.Count) 条")
        [void]$md.Add("")
        foreach ($it in ($okLow | Sort-Object height -Descending)) {
            [void]$md.Add("- $($it.name) — $($it.width)x$($it.height) — ``$($it.url)``")
        }
        [void]$md.Add("")
    }
    if ($blacklisted.Count -gt 0) {
        [void]$md.Add("**命中已知风险域名（黑名单）**：$($blacklisted.Count) 条")
        [void]$md.Add("")
        foreach ($it in $blacklisted) {
            [void]$md.Add("- $($it.name) — ``$($it.url)``")
        }
        [void]$md.Add("")
    }
}

if ($diffLines.Count -gt 0) {
    [void]$md.Add('## 六、与上期对比')
    [void]$md.Add("")
    [void]$md.Add('| 项 | 数量 |')
    [void]$md.Add('| --- | --- |')
    foreach ($l in $diffLines) { [void]$md.Add($l) }
    [void]$md.Add("")
}

$mdPath = Join-Path $OutDir "check-$tag-$stamp.md"
$md -join "`n" | Set-Content -Path $mdPath -Encoding utf8
if (-not $NoReport) { "报告已写出: $mdPath" }

# ---------- 控制台摘要 ----------
""
"--- 摘要 ---"
"直连可用率: $okRate% ($($ok.Count)/$directCount)"
$result | Where-Object { $_.height -gt 0 } | Group-Object height | Sort-Object { [int]$_.Name } -Descending |
    ForEach-Object { "  {0}p: {1}" -f $_.Name, $_.Count }
$result | Group-Object kind | Sort-Object Count -Descending |
    ForEach-Object { "  {0}: {1}" -f $_.Name, $_.Count }
if ($deadChannels.Count -gt 0) { "!! 无可用线路频道: $($deadChannels -join '、')" }
if ($directDeadChannels.Count -gt 0) { "!  直连全失败、仅剩 webview: $($directDeadChannels.Count) 个" }
"   仅依赖 webview 的频道: $($webviewOnlyChannels.Count) 个（正常状态，非告警）"
if ($okLow.Count -gt 0) { "!  低于 ${minHeight}p 的线路: $($okLow.Count) 条" }
if ($blacklisted.Count -gt 0) { "!! 命中黑名单域名: $($blacklisted.Count) 条" }

# ---------- 发布门禁 ----------
# 只有一类问题是硬门禁：既没有可用直连、又没有 webview:// 兜底的频道——
# 用户打开它会直接打不开，而其它告警（低画质、仅剩 webview 兜底）都属于「能看但不够好」。
# 发布流程在打包前先跑本脚本，退出码非 0 即停止。
""
if ($deadChannels.Count -gt 0) {
    "!! 发布门禁未通过：无可用线路的频道 $($deadChannels.Count) 个 —— $($deadChannels -join '、')"
    exit 1
}
"发布门禁通过：无「全死且无 webview 兜底」的频道（仅依赖 webview 的 $($webviewOnlyChannels.Count) 个属正常）"
exit 0
