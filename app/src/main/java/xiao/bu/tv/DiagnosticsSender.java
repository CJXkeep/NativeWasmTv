package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 诊断发送通道（I4 决策 7）：把脱敏报告发到钉钉群机器人，可选通用 JSON 端点兜底。
 *
 * <p>规则：
 * <ul>
 *   <li>只有用户主动触发才会发送（不做后台自动上报）；
 *   <li>钉钉的成功判定必须看响应体 {@code errcode}，不能只看 HTTP 状态；
 *   <li>最多两个候选地址，第一个失败依次尝试第二个；第二个可留空；
 *   <li>频控：同一设备 10 分钟内最多 3 次。
 * </ul>
 */
final class DiagnosticsSender {
    static final String PREF_WEBHOOK = "diagnostics_webhook";
    static final String PREF_SECRET = "diagnostics_secret";
    static final String STATE_IDLE = "idle";
    static final String STATE_SENDING = "sending";
    static final String STATE_SENT = "sent";
    static final String STATE_FAILED = "failed";
    static final String STATE_SKIPPED = "skipped";
    static final int MAX_CANDIDATES = 2;
    static final int MAX_SENDS_PER_WINDOW = 3;
    static final long SEND_WINDOW_MS = 10 * 60 * 1000L;
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 8000;
    static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private static final String TAG = "DiagnosticsSender";
    private static final char[] BASE64_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final ArrayDeque<Long> SEND_HISTORY = new ArrayDeque<Long>();

    private static volatile String state = STATE_IDLE;
    private static volatile String stateReason = "";
    private static volatile long lastAttemptAt;
    private static volatile long lastSentAt;
    private static volatile String usedChannel = "";
    private static volatile boolean sending;

    private DiagnosticsSender() {
    }

    /** 配置优先取控制页「高级」里的覆盖值，其次取编译期注入（家人零配置）。 */
    static String webhook(Context context) {
        return configured(context, PREF_WEBHOOK, BuildConfig.DIAGNOSTIC_WEBHOOK);
    }

    static String secret(Context context) {
        return configured(context, PREF_SECRET, BuildConfig.DIAGNOSTIC_SECRET);
    }

    static boolean isConfigured(Context context) {
        return webhook(context).length() > 0;
    }

    /** 触发一次发送；立即返回，结果经 {@link #statusJson()} 轮询。 */
    static void send(final Context context, final String reportText) {
        final String configured = webhook(context);
        if (configured.length() == 0) {
            setState(STATE_SKIPPED, "未配置接收地址");
            return;
        }
        if (reportText == null || reportText.trim().length() == 0) {
            setState(STATE_SKIPPED, "没有可发送的诊断内容");
            return;
        }
        if (!beginSend()) {
            return;
        }
        if (!allowsSend(System.currentTimeMillis())) {
            markSending(false);
            setState(STATE_SKIPPED, "发送太频繁，请稍后再试");
            return;
        }
        final String secret = secret(context);
        final String keyword = BuildConfig.DIAGNOSTIC_KEYWORD;
        final Context appContext = context == null ? null : context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = attemptSend(appContext, configured, secret, keyword, reportText);
                if (failure.length() == 0) {
                    lastSentAt = System.currentTimeMillis();
                    setState(STATE_SENT, "");
                } else {
                    setState(STATE_FAILED, failure);
                }
                markSending(false);
            }
        }, "diagnostics-send").start();
    }

    static String statusJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("state", state);
            root.put("reason", stateReason);
            root.put("lastAttemptAt", lastAttemptAt);
            root.put("lastSentAt", lastSentAt);
            root.put("channel", usedChannel);
            return root.toString();
        } catch (JSONException error) {
            return "{\"state\":\"failed\",\"reason\":\"状态生成失败\"}";
        }
    }

    static String state() {
        return state;
    }

    static String stateReason() {
        return stateReason;
    }

    /** 逐候选尝试；全部失败时返回最后一条可读原因。 */
    private static String attemptSend(Context context, String configured, String secret,
            String keyword, String reportText) {
        String[] candidates = candidates(configured);
        if (candidates.length == 0) {
            return "接收地址为空";
        }
        String failure = "";
        for (int index = 0; index < candidates.length; index++) {
            String url = candidates[index];
            try {
                String body = requestBody(url, reportText, keyword);
                String response = post(url, secret, body);
                String error = responseError(url, response);
                if (error.length() == 0) {
                    usedChannel = maskUrl(url);
                    return "";
                }
                failure = error;
                Log.w(TAG, "Diagnostics send rejected channel=" + maskUrl(url)
                        + " reason=" + error);
            } catch (Exception error) {
                failure = readableError(error);
                Log.w(TAG, "Diagnostics send failed channel=" + maskUrl(url), error);
            }
        }
        return failure.length() == 0 ? "发送失败" : failure;
    }

    private static String post(String url, String secret, String body) throws IOException {
        String target = isDingTalk(url)
                ? dingTalkUrl(url, secret, System.currentTimeMillis()) : url;
        HttpURLConnection connection = NetworkClient.open(new URL(target));
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "nTv/" + BuildConfig.VERSION_NAME
                    + " Android/" + Build.VERSION.RELEASE);
            byte[] payload = body.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            String response = input == null ? "" : readUtf8(input, MAX_RESPONSE_BYTES);
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status
                        + (response.length() == 0 ? "" : " · " + trim(response)));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String readUtf8(InputStream stream, int limit) throws IOException {
        BufferedInputStream input = new BufferedInputStream(stream);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[4 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) {
                    output.write(buffer, 0, limit - output.size());
                    break;
                }
                output.write(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        try {
            return new String(output.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 以下为纯逻辑：单测覆盖签名、候选、请求体与成功判定
    // ------------------------------------------------------------------

    /** 分号分隔的候选地址；去空、去重，最多两个。 */
    static String[] candidates(String configured) {
        if (configured == null) {
            return new String[0];
        }
        List<String> result = new ArrayList<String>();
        String[] parts = configured.split(";");
        for (String part : parts) {
            String value = part.trim();
            if (value.length() == 0 || result.contains(value)
                    || result.size() >= MAX_CANDIDATES) {
                continue;
            }
            result.add(value);
        }
        return result.toArray(new String[result.size()]);
    }

    /** 钉钉加签：{@code HMAC-SHA256(timestamp + "\n" + secret)} 后 Base64 + URL 编码。 */
    static String dingTalkUrl(String webhook, String secret, long timestampMs) {
        if (webhook == null) {
            return "";
        }
        if (secret == null || secret.trim().length() == 0) {
            return webhook;
        }
        String sign;
        try {
            String stringToSign = timestampMs + "\n" + secret.trim();
            byte[] raw = hmacSha256(secret.trim().getBytes("UTF-8"),
                    stringToSign.getBytes("UTF-8"));
            sign = encode(base64Encode(raw));
        } catch (Exception error) {
            return webhook;
        }
        char separator = webhook.indexOf('?') >= 0 ? '&' : '?';
        return webhook + separator + "timestamp=" + timestampMs + "&sign=" + sign;
    }

    static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** 自实现 Base64：不依赖 android.util（JVM 单测可用），也不依赖 API 26 的 java.util.Base64。 */
    static String base64Encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int index = 0; index < data.length; index += 3) {
            int first = data[index] & 0xff;
            int second = index + 1 < data.length ? data[index + 1] & 0xff : -1;
            int third = index + 2 < data.length ? data[index + 2] & 0xff : -1;
            text.append(BASE64_CHARS[first >>> 2]);
            text.append(BASE64_CHARS[((first & 0x03) << 4) | (second < 0 ? 0 : second >>> 4)]);
            text.append(second < 0 ? '='
                    : BASE64_CHARS[((second & 0x0f) << 2) | (third < 0 ? 0 : third >>> 6)]);
            text.append(third < 0 ? '=' : BASE64_CHARS[third & 0x3f]);
        }
        return text.toString();
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return value;
        }
    }

    /** 钉钉地址按钉钉格式发；其它地址按通用 JSON 发（作者自接端点）。 */
    static boolean isDingTalk(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("dingtalk.com");
    }

    /** 手写拼 JSON：不依赖 android 的 org.json（JVM 单测里它是未实现的 stub）。 */
    static String requestBody(String url, String text, String keyword) {
        String content = text == null ? "" : text;
        if (isDingTalk(url)) {
            String tag = keyword == null ? "" : keyword.trim();
            if (tag.length() > 0 && content.indexOf(tag) < 0) {
                // 机器人安全设置若选了「自定义关键词」，消息必须包含该词。
                content = tag + " " + content;
            }
            return "{\"msgtype\":\"text\",\"text\":{\"content\":\""
                    + escapeJson(content) + "\"}}";
        }
        return "{\"source\":\"nTv\",\"appVersion\":\"" + escapeJson(BuildConfig.VERSION_NAME)
                + "\",\"time\":" + System.currentTimeMillis()
                + ",\"text\":\"" + escapeJson(content) + "\"}";
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"':
                    text.append("\\\"");
                    break;
                case '\\':
                    text.append("\\\\");
                    break;
                case '\n':
                    text.append("\\n");
                    break;
                case '\r':
                    text.append("\\r");
                    break;
                case '\t':
                    text.append("\\t");
                    break;
                default:
                    if (current < 0x20) {
                        text.append(String.format(Locale.US, "\\u%04x", (int) current));
                    } else {
                        text.append(current);
                    }
            }
        }
        return text.toString();
    }

    /**
     * 返回空串表示成功。
     * 钉钉即使业务失败也返回 HTTP 200，必须解析 {@code errcode}，否则会把失败当成功。
     */
    static String responseError(String url, String responseBody) {
        if (!isDingTalk(url)) {
            return "";
        }
        String body = responseBody == null ? "" : responseBody.trim();
        if (body.length() == 0) {
            return "钉钉返回空响应";
        }
        String codeText = jsonField(body, "errcode");
        if (codeText.length() == 0) {
            return "无法解析钉钉响应";
        }
        int code;
        try {
            code = Integer.parseInt(codeText);
        } catch (NumberFormatException error) {
            return "无法解析钉钉响应";
        }
        if (code == 0) {
            return "";
        }
        String message = jsonField(body, "errmsg").trim();
        return "钉钉 errcode=" + code
                + (message.length() == 0 ? "" : " · " + trim(message))
                + (code == 310000 ? "（请检查机器人安全设置与设备时间）" : "");
    }

    /**
     * 极简 JSON 取值：识别 {@code "name":"文本"} 与 {@code "name":数字}。
     * 钉钉响应格式固定，够用；关键是这样就不依赖 org.json，可在 JVM 单测里跑。
     */
    static String jsonField(String json, String name) {
        if (json == null || name == null || name.length() == 0) {
            return "";
        }
        String marker = "\"" + name + "\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int colon = json.indexOf(':', start + marker.length());
        if (colon < 0) {
            return "";
        }
        int index = colon + 1;
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        if (index >= json.length()) {
            return "";
        }
        if (json.charAt(index) == '"') {
            StringBuilder text = new StringBuilder();
            int cursor = index + 1;
            while (cursor < json.length()) {
                char current = json.charAt(cursor);
                if (current == '\\' && cursor + 1 < json.length()) {
                    cursor++;
                    text.append(json.charAt(cursor));
                } else if (current == '"') {
                    break;
                } else {
                    text.append(current);
                }
                cursor++;
            }
            return text.toString();
        }
        int end = index;
        while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        return json.substring(index, end);
    }

    static synchronized boolean allowsSend(long now) {
        while (!SEND_HISTORY.isEmpty() && now - SEND_HISTORY.peekFirst() > SEND_WINDOW_MS) {
            SEND_HISTORY.removeFirst();
        }
        if (SEND_HISTORY.size() >= MAX_SENDS_PER_WINDOW) {
            return false;
        }
        SEND_HISTORY.addLast(now);
        return true;
    }

    /** 仅测试使用：清空频控历史，避免用例之间互相影响。 */
    static synchronized void resetSendHistory() {
        SEND_HISTORY.clear();
    }

    private static synchronized boolean beginSend() {
        if (sending) {
            return false;
        }
        sending = true;
        lastAttemptAt = System.currentTimeMillis();
        setState(STATE_SENDING, "");
        return true;
    }

    private static void markSending(boolean value) {
        sending = value;
    }

    private static void setState(String value, String reason) {
        state = value;
        stateReason = reason == null ? "" : reason;
    }

    private static String configured(Context context, String key, String fallback) {
        if (context != null) {
            SharedPreferences preferences = context.getSharedPreferences(
                    MainActivity.PREFERENCES, Context.MODE_PRIVATE);
            String value = preferences.getString(key, "");
            if (value != null && value.trim().length() > 0) {
                return value.trim();
            }
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String maskUrl(String url) {
        return PlaybackDiagnostics.sanitizeUrl(url);
    }

    private static String readableError(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private static String trim(String value) {
        String text = value.replace('\n', ' ').trim();
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
