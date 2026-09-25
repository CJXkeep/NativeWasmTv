package xiao.bu.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * GitHub 加速源（I4 决策 5）：候选列表 + 连接级回退 + 直连兜底。
 *
 * <p>改动面：只影响「直接下载」链路（更新清单、APK、CJS 插件与目录）。
 * 推荐源地址（{@code PlaylistManager}）仍是持久化单值，见迭代文档的已知限制。
 *
 * <p>回退使用情况经 {@link #statusJson()} 暴露给诊断页（原则 6：先让时间可观测）。
 */
final class GithubProxy {
    /** 加速源候选：首位是首选加速域，最后一位固定为直连 GitHub。 */
    private static final String[] ACCELERATORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
    };
    private static final String GITHUB_HOST = "github.com";
    private static final String GITHUB_RAW_HOST = "raw.githubusercontent.com";
    private static final int HISTORY_LIMIT = 10;

    private static final ArrayDeque<String> HISTORY = new ArrayDeque<String>();
    /** 按线程记录最近一次实际使用的地址：避免清单检查的来源被 CJS 插件等其它链路覆盖。 */
    private static final ThreadLocal<String> THREAD_LAST_USED = new ThreadLocal<String>();
    private static volatile String lastUsed = "";
    private static volatile int lastFallbacks;
    private static volatile long lastElapsedMs;
    private static volatile long lastAt;
    private static volatile int totalFallbacks;

    private GithubProxy() {
    }

    /** 供直接下载链路使用：返回候选序列（加速域 + 直连）。 */
    static String[] candidates(String githubUrl) {
        String origin = unwrap(githubUrl);
        if (origin.length() == 0) {
            return new String[0];
        }
        if (!isGithubUrl(origin)) {
            // 非 GitHub 地址（如自建端点、Gitee）不走加速，仅直连。
            return new String[] { origin };
        }
        String[] result = new String[ACCELERATORS.length + 1];
        for (int index = 0; index < ACCELERATORS.length; index++) {
            result[index] = ACCELERATORS[index] + origin;
        }
        result[ACCELERATORS.length] = origin;
        return result;
    }

    /** 展示场景保留的旧语义：返回首选地址（不保证可用）。 */
    static String apply(String githubUrl) {
        String[] urls = candidates(githubUrl);
        return urls.length == 0 ? "" : urls[0];
    }

    /** 去掉任一加速前缀；兼容多前缀后，目录比对逻辑仍然成立。 */
    static String unwrap(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        for (String prefix : ACCELERATORS) {
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }

    static boolean isProxied(String url) {
        if (url == null) {
            return false;
        }
        for (String prefix : ACCELERATORS) {
            if (url.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isGithubUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("https://" + GITHUB_HOST + "/")
                || lower.startsWith("https://" + GITHUB_RAW_HOST + "/")
                || lower.contains("://" + GITHUB_HOST + "/")
                || lower.contains("://" + GITHUB_RAW_HOST + "/");
    }

    /** 逐候选尝试；返回响应码可用的连接，全部失败时抛出最后一个错误。 */
    static HttpURLConnection open(String githubUrl, int connectTimeoutMs, int readTimeoutMs,
            HeaderSetter headers) throws IOException {
        String[] urls = candidates(githubUrl);
        if (urls.length == 0) {
            throw new IOException("下载地址为空");
        }
        IOException lastError = null;
        for (int index = 0; index < urls.length; index++) {
            long started = System.currentTimeMillis();
            HttpURLConnection connection = null;
            try {
                connection = NetworkClient.open(new URL(urls[index]));
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setInstanceFollowRedirects(true);
                connection.setUseCaches(false);
                if (headers != null) {
                    headers.apply(connection);
                }
                int status = connection.getResponseCode();
                if (status >= 200 && status < 400) {
                    recordAttempt(urls[index], index, System.currentTimeMillis() - started);
                    return connection;
                }
                lastError = new IOException("HTTP " + status);
                // 失败也要留痕（I4 B9）：否则「更新为什么失败」看不到试过哪些地址。
                THREAD_LAST_USED.set(PlaybackDiagnostics.sanitizeUrl(urls[index])
                        + "（HTTP " + status + "）");
                connection.disconnect();
            } catch (IOException error) {
                lastError = error;
                THREAD_LAST_USED.set(PlaybackDiagnostics.sanitizeUrl(urls[index])
                        + "（" + (error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage()) + "）");
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw lastError == null ? new IOException("所有下载地址均不可用") : lastError;
    }

    /** 连接建立前的请求头设置：必须在候选真正发起请求前应用。 */
    interface HeaderSetter {
        void apply(HttpURLConnection connection);
    }

    private static synchronized void recordAttempt(String url, int fallbacks, long elapsedMs) {
        String masked = PlaybackDiagnostics.sanitizeUrl(url);
        lastUsed = masked;
        lastFallbacks = fallbacks;
        lastElapsedMs = elapsedMs;
        lastAt = System.currentTimeMillis();
        if (fallbacks > 0) {
            totalFallbacks++;
        }
        THREAD_LAST_USED.set(masked
                + (fallbacks > 0 ? "（回退 " + fallbacks + " 次）" : "（首选命中）")
                + " · " + elapsedMs + "ms");
        String summary = masked + " · fallback=" + fallbacks + " · " + elapsedMs + "ms";
        HISTORY.addLast(summary);
        while (HISTORY.size() > HISTORY_LIMIT) {
            HISTORY.removeFirst();
        }
    }

    static String statusJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("lastUsed", lastUsed);
            root.put("lastFallbacks", lastFallbacks);
            root.put("lastElapsedMs", lastElapsedMs);
            root.put("lastAt", lastAt);
            root.put("totalFallbacks", totalFallbacks);
            synchronized (GithubProxy.class) {
                org.json.JSONArray history = new org.json.JSONArray();
                for (String item : HISTORY) {
                    history.put(item);
                }
                root.put("history", history);
            }
            return root.toString();
        } catch (JSONException error) {
            return "{}";
        }
    }

    /** 本次线程最近一次下载实际使用的地址摘要（清单检查用它，避免被其它链路覆盖）。 */
    static String lastUsedInThread() {
        String value = THREAD_LAST_USED.get();
        return value == null ? "" : value;
    }

    /** 纯文本摘要：发送报告与诊断页共用（避免 JSON 二次解析）。 */
    static String summaryText() {
        if (lastAt == 0L) {
            return "加速源未使用";
        }
        return "加速源 " + (lastUsed.length() == 0 ? "-" : lastUsed)
                + (lastFallbacks > 0
                ? "（第 " + (lastFallbacks + 1) + " 个候选命中）" : "（首选命中）")
                + " · " + lastElapsedMs + "ms";
    }

    static void resetStatus() {
        synchronized (GithubProxy.class) {
            HISTORY.clear();
            lastUsed = "";
            lastFallbacks = 0;
            lastElapsedMs = 0L;
            lastAt = 0L;
            totalFallbacks = 0;
        }
    }
}
