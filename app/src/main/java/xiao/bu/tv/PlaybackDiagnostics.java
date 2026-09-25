package xiao.bu.tv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 播放失败与崩溃现场（I4 决策 2）。
 *
 * <p>两份数据、职责不同：
 * <ul>
 *   <li>内存最多 {@link #MEMORY_LIMIT} 条，是诊断读取与发送的唯一数据源；
 *   <li>落盘 {@code diagnostics.log} 滚动留存，进程重启时回填内存；
 *   <li>崩溃路径只读 {@link #snapshotForCrash()}（volatile 快照，无锁无 IO）。
 * </ul>
 *
 * <p>写入方是播放失败链（UI 线程，只做内存追加 + 投递），落盘由单后台线程完成。
 */
final class PlaybackDiagnostics {
    static final String FILE_NAME = "diagnostics.log";
    static final String CRASH_FILE_NAME = "last-crash.txt";
    static final int MEMORY_LIMIT = 50;
    static final int CRASH_SNAPSHOT_LIMIT = 10;
    static final int SEND_SNAPSHOT_LIMIT = 3;
    static final int FILE_LIMIT_BYTES = 64 * 1024;
    static final int LINE_MAX_LENGTH = 160;
    static final int CRASH_TEXT_LIMIT = 12 * 1024;
    static final int CRASH_REPORT_BYTES = 4 * 1024;
    static final int REPORT_MAX_BYTES = 8 * 1024;

    private static final String TAG = "PlaybackDiagnostics";
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<Entry>();
    /** 崩溃路径专用：无锁读，避免在 UncaughtExceptionHandler 里等锁或做 IO。 */
    private static volatile String crashSnapshot = "";
    private static Context context;
    private static ExecutorService writer;

    private PlaybackDiagnostics() {
    }

    static void initialize(Context value) {
        if (value == null) {
            return;
        }
        context = value.getApplicationContext();
        restoreAsync();
    }

    static void record(String stage, String channel, String line, String reason,
            int attempt, long elapsedMs) {
        Entry entry = new Entry(System.currentTimeMillis(), stage, channel,
                sanitizeUrl(line), reason, attempt, elapsedMs);
        synchronized (ENTRIES) {
            ENTRIES.addLast(entry);
            while (ENTRIES.size() > MEMORY_LIMIT) {
                ENTRIES.removeFirst();
            }
            crashSnapshot = buildSnapshotText(CRASH_SNAPSHOT_LIMIT);
        }
        persistAsync(entry);
    }

    /** 崩溃时附写进 last-crash.txt：只读 volatile 字段，不等锁、不做 IO。 */
    static String snapshotForCrash() {
        return crashSnapshot;
    }

    /** 控制页诊断接口用的完整记录（内存 50 条）。 */
    static String fullJson(String deviceLabel) {
        try {
            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("generatedAt", System.currentTimeMillis());
            root.put("device", deviceJson(deviceLabel));
            root.put("crash", crashJson());
            JSONArray failures = new JSONArray();
            for (Entry entry : recent(MEMORY_LIMIT)) {
                failures.put(entry.toJson());
            }
            root.put("failures", failures);
            return root.toString();
        } catch (JSONException error) {
            return "{\"ok\":false,\"message\":\"诊断信息生成失败\"}";
        }
    }

    /** 发送用的纯文本报告：头部优先，超限截断尾部（I4 review 修正）。 */
    static String reportText(String deviceLabel, List<String> extraLines, String crashText) {
        StringBuilder text = new StringBuilder();
        text.append("nTv 诊断");
        if (deviceLabel != null && deviceLabel.trim().length() > 0) {
            text.append(" · ").append(deviceLabel.trim());
        }
        text.append('\n');
        text.append("版本 ").append(BuildConfig.VERSION_NAME)
                .append('(').append(BuildConfig.VERSION_CODE).append(')')
                .append(" · ").append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append('\n');
        text.append("时间 ").append(formatTime(System.currentTimeMillis())).append('\n');
        if (extraLines != null) {
            for (String line : extraLines) {
                if (line != null && line.trim().length() > 0) {
                    text.append(line.trim()).append('\n');
                }
            }
        }
        // 崩溃优先：整体按字节截断时，先保住异常类型与消息（review 修正的截断方向）。
        if (crashText != null && crashText.trim().length() > 0) {
            text.append("--- 崩溃记录 ---\n")
                    .append(truncateHead(crashText.trim(), CRASH_REPORT_BYTES)).append('\n');
        }
        List<Entry> recent = recent(SEND_SNAPSHOT_LIMIT);
        text.append("最近失败 ").append(recent.size()).append(" 条\n");
        int index = 1;
        for (Entry entry : recent) {
            text.append(index++).append(") ").append(entry.describe()).append('\n');
        }
        return truncateHead(text.toString(), REPORT_MAX_BYTES);
    }

    /** 崩溃文本（last-crash.txt）：头部优先保留。 */
    static String crashText() {
        File file = crashFile();
        if (file == null || !file.isFile()) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > CRASH_TEXT_LIMIT) {
                    output.write(buffer, 0, CRASH_TEXT_LIMIT - output.size());
                    break;
                }
                output.write(buffer, 0, count);
            }
        } catch (IOException error) {
            Log.w(TAG, "Unable to read crash record", error);
            return "";
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
        try {
            return new String(output.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return "";
        }
    }

    /**
     * 脱敏：只保留 {@code scheme://host/path}，query 与 fragment 一律打码。
     * 纯函数，供单测覆盖。
     */
    static String sanitizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        if (value.length() == 0) {
            return "";
        }
        int cut = value.length();
        int query = value.indexOf('?');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        boolean masked = cut < value.length();
        String base = value.substring(0, cut);
        if (base.length() > LINE_MAX_LENGTH) {
            base = base.substring(0, LINE_MAX_LENGTH);
            masked = true;
        }
        return masked ? base + "…" : base;
    }

    /** 头部优先截断（UTF-8 字节口径）：崩溃栈的价值在异常类型与消息，不在框架帧尾部。 */
    static String truncateHead(String text, int maxBytes) {
        if (text == null) {
            return "";
        }
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException error) {
            return text;
        }
        if (bytes.length <= maxBytes) {
            return text;
        }
        int limit = maxBytes - 3;
        if (limit < 0) {
            limit = 0;
        }
        // 不切断多字节字符：从上限处向前回退到字符边界。
        int end = limit;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        try {
            return new String(bytes, 0, end, "UTF-8") + "…";
        } catch (UnsupportedEncodingException error) {
            return text;
        }
    }

    private static List<Entry> recent(int limit) {
        List<Entry> result = new ArrayList<Entry>();
        synchronized (ENTRIES) {
            int size = ENTRIES.size();
            int skip = Math.max(0, size - limit);
            int index = 0;
            for (Entry entry : ENTRIES) {
                if (index++ < skip) {
                    continue;
                }
                result.add(entry);
            }
        }
        return result;
    }

    private static String buildSnapshotText(int limit) {
        StringBuilder text = new StringBuilder();
        for (Entry entry : recent(limit)) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(entry.describe());
        }
        return text.toString();
    }

    private static JSONObject deviceJson(String deviceLabel) throws JSONException {
        JSONObject device = new JSONObject();
        device.put("label", deviceLabel == null ? "" : deviceLabel);
        device.put("model", Build.MODEL);
        device.put("android", Build.VERSION.RELEASE);
        device.put("sdk", Build.VERSION.SDK_INT);
        device.put("abi", BuildConfig.CJS_PLUGIN_ABI);
        device.put("appVersion", BuildConfig.VERSION_NAME);
        device.put("appVersionCode", BuildConfig.VERSION_CODE);
        return device;
    }

    private static JSONObject crashJson() throws JSONException {
        JSONObject crash = new JSONObject();
        File file = crashFile();
        crash.put("present", file != null && file.isFile());
        if (file != null && file.isFile()) {
            crash.put("at", file.lastModified());
            crash.put("text", crashText());
        }
        return crash;
    }

    private static String formatTime(long at) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(at));
    }

    private static File crashFile() {
        Context current = context;
        return current == null ? null : new File(current.getFilesDir(), CRASH_FILE_NAME);
    }

    private static File diagnosticsFile() {
        Context current = context;
        return current == null ? null : new File(current.getFilesDir(), FILE_NAME);
    }

    private static synchronized ExecutorService writer() {
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "playback-diagnostics");
                    thread.setDaemon(true);
                    return thread;
                }
            });
        }
        return writer;
    }

    private static void persistAsync(final Entry entry) {
        final File file = diagnosticsFile();
        if (file == null) {
            return;
        }
        try {
            writer().execute(new Runnable() {
                @Override
                public void run() {
                    appendEntry(file, entry);
                }
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to queue diagnostics write", error);
        }
    }

    /** 单后台线程内串行执行：追加一行；超过上限时用内存内容压缩重写。 */
    private static void appendEntry(File file, Entry entry) {
        try {
            FileOutputStream output = new FileOutputStream(file, true);
            try {
                output.write((entry.toJson().toString() + "\n").getBytes("UTF-8"));
                output.getFD().sync();
            } finally {
                output.close();
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to persist diagnostics entry", error);
            return;
        }
        if (file.length() <= FILE_LIMIT_BYTES) {
            return;
        }
        rewrite(file);
    }

    private static void rewrite(File file) {
        List<Entry> keep = recent(MEMORY_LIMIT);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(temp);
            for (Entry entry : keep) {
                output.write((entry.toJson().toString() + "\n").getBytes("UTF-8"));
            }
            output.getFD().sync();
        } catch (Exception error) {
            Log.w(TAG, "Unable to compact diagnostics file", error);
            return;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (!temp.renameTo(file)) {
            if (!file.delete() || !temp.renameTo(file)) {
                Log.w(TAG, "Unable to replace diagnostics file");
            }
        }
    }

    private static void restoreAsync() {
        final File file = diagnosticsFile();
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            writer().execute(new Runnable() {
                @Override
                public void run() {
                    restore(file);
                }
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to queue diagnostics restore", error);
        }
    }

    private static void restore(File file) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1 && output.size() < FILE_LIMIT_BYTES * 2) {
                output.write(buffer, 0, count);
            }
        } catch (IOException error) {
            Log.w(TAG, "Unable to restore diagnostics", error);
            return;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
        String text;
        try {
            text = new String(output.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return;
        }
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - MEMORY_LIMIT);
        List<Entry> restored = new ArrayList<Entry>();
        for (int index = start; index < lines.length; index++) {
            Entry entry = Entry.parse(lines[index]);
            if (entry != null) {
                restored.add(entry);
            }
        }
        if (restored.isEmpty()) {
            return;
        }
        synchronized (ENTRIES) {
            if (!ENTRIES.isEmpty()) {
                // 运行时已有新记录：不覆盖，落盘副本仍在文件里。
                return;
            }
            for (Entry entry : restored) {
                ENTRIES.addLast(entry);
            }
            crashSnapshot = buildSnapshotText(CRASH_SNAPSHOT_LIMIT);
        }
    }

    /** 一条失败记录；序列化进文件与 JSON，也给崩溃快照复用。 */
    static final class Entry {
        final long at;
        final String stage;
        final String channel;
        final String line;
        final String reason;
        final int attempt;
        final long elapsedMs;

        Entry(long at, String stage, String channel, String line, String reason,
                int attempt, long elapsedMs) {
            this.at = at;
            this.stage = stage == null ? "" : stage;
            this.channel = channel == null ? "" : channel;
            this.line = line == null ? "" : line;
            this.reason = reason == null ? "" : reason;
            this.attempt = attempt;
            this.elapsedMs = elapsedMs;
        }

        static Entry parse(String line) {
            if (line == null || line.trim().length() == 0) {
                return null;
            }
            try {
                JSONObject object = new JSONObject(line);
                return new Entry(object.optLong("at", 0L),
                        object.optString("stage", ""),
                        object.optString("channel", ""),
                        object.optString("line", ""),
                        object.optString("reason", ""),
                        object.optInt("attempt", 0),
                        object.optLong("elapsedMs", 0L));
            } catch (JSONException error) {
                return null;
            }
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("at", at)
                    .put("stage", stage)
                    .put("channel", channel)
                    .put("line", line)
                    .put("reason", reason)
                    .put("attempt", attempt)
                    .put("elapsedMs", elapsedMs);
        }

        String describe() {
            StringBuilder text = new StringBuilder();
            text.append(formatTime(at)).append(' ');
            if (stage.length() > 0) {
                text.append('[').append(stage).append("] ");
            }
            if (channel.length() > 0) {
                text.append(channel);
            }
            if (attempt > 0) {
                text.append("(线路 ").append(attempt).append(')');
            }
            text.append(' ').append(reason);
            if (line.length() > 0) {
                text.append(" · ").append(line);
            }
            if (elapsedMs > 0L) {
                text.append(" · ").append(elapsedMs).append("ms");
            }
            return text.toString();
        }
    }
}
