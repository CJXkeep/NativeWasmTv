package xiao.bu.tv;

import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Extracted from MainActivity (kept behaviour identical). */
final class SourceTags {
    private static final String TAG = "SourceTags";
    private SourceTags() {
    }
    static boolean isWebViewSource(String url) {
        return url != null && (url.startsWith("webview://http://")
                || url.startsWith("webview://https://"));
    }

    static void appendWebHeader(StringBuilder headers, String name, String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        String safeValue = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (safeValue.length() > 0) {
            headers.append(name).append(": ").append(safeValue).append("\r\n");
        }
    }

    static boolean isCctvDirectStream(String url) {
        if (url == null) {
            return false;
        }
        String normalized = url.toLowerCase(Locale.US);
        return normalized.contains("cctvwbcd") && normalized.contains("/cdrmld")
                && normalized.contains(".m3u8");
    }

    static boolean requiresParallelHlsPrefetch(String streamUrl) {
        if (streamUrl == null) {
            return false;
        }
        String value = streamUrl.toLowerCase(Locale.US);
        // This IPTV server family publishes ~5 MB/5 s segments but throttles each
        // connection. Two bounded Java downloads keep one upcoming segment ready.
        return value.contains(":9901/tsfile/live/") || value.contains("key=txiptv");
    }

    static boolean isHttpHlsSource(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(Locale.US);
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return false;
        }
        return value.contains(".m3u8") || value.contains("format=m3u8")
                || value.contains("type=m3u8");
    }

    static boolean isDirectHttpMediaSource(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(Locale.US);
        if (!value.startsWith("http://")) {
            // This IJK profile intentionally relies on the Java proxy for HTTPS/TLS.
            return false;
        }
        String path = Uri.parse(value).getPath();
        if (path == null) {
            return false;
        }
        return path.endsWith(".flv") || path.endsWith(".mp4")
                || path.endsWith(".mkv") || path.endsWith(".webm")
                || path.endsWith(".mov") || path.endsWith(".avi")
                || path.endsWith(".ts") || path.endsWith(".aac")
                || path.endsWith(".mp3");
    }

    static boolean isRtmpSource(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(Locale.US);
        return value.startsWith("rtmp://") || value.startsWith("rtmpt://")
                || value.startsWith("rtmps://");
    }

    static boolean isRtspSource(String sourceUrl) {
        return sourceUrl != null
                && sourceUrl.trim().toLowerCase(Locale.US).startsWith("rtsp://");
    }

    static boolean isNativeStreamingSource(String sourceUrl) {
        return isRtmpSource(sourceUrl) || isRtspSource(sourceUrl);
    }

    static String webViewPage(String configuredUrl) {
        Uri pageUri = parseWebViewPageUri(configuredUrl);
        if (pageUri == null || pageUri.getHost() == null) {
            return null;
        }
        return pageUri.toString();
    }

    static Uri parseWebViewPageUri(String configuredUrl) {
        if (!isWebViewSource(configuredUrl)) {
            return null;
        }
        try {
            return Uri.parse(configuredUrl.substring("webview://".length()));
        } catch (RuntimeException error) {
            return null;
        }
    }

    static boolean hostMatches(String host, String domain) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.US);
        return lower.equals(domain) || lower.endsWith("." + domain);
    }

    static String extractYangshipinPid(String configuredUrl) {
        Uri pageUri = parseWebViewPageUri(configuredUrl);
        if (pageUri == null || !hostMatches(pageUri.getHost(), "yangshipin.cn")) {
            return null;
        }
        try {
            String pid = pageUri.getQueryParameter("pid");
            return pid == null || pid.trim().length() == 0 ? null : pid.trim();
        } catch (UnsupportedOperationException error) {
            return null;
        }
    }

    static Channel extractCctvWebChannel(String configuredUrl) {
        Uri pageUri = parseWebViewPageUri(configuredUrl);
        if (pageUri == null || !hostMatches(pageUri.getHost(), "cctv.com")) {
            return null;
        }
        java.util.List<String> segments = pageUri.getPathSegments();
        for (int index = 0; index + 1 < segments.size(); index++) {
            if ("live".equalsIgnoreCase(segments.get(index))) {
                return ChannelCatalog.findCctvChannelByWebSlug(segments.get(index + 1));
            }
        }
        return null;
    }
}
