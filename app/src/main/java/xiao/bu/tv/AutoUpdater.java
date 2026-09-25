package xiao.bu.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Checks the repository manifest, downloads a newer APK, and opens the system installer. */
final class AutoUpdater {
    private static final String TAG = "AutoUpdater";
    /** 自有发布闭环（2026-09-25 决定）：清单取自本项目自己的 Release，不再跟随上游。 */
    private static final String VERSION_URL = "https://github.com/CJXkeep/NativeWasmTv/"
            + "releases/latest/download/version.json";

    /** 清单地址：默认走正式 Release；仅当编译期注入测试开关时才改用本地清单（见 build.gradle）。 */
    private static String manifestUrl() {
        String override = BuildConfig.UPDATE_MANIFEST_OVERRIDE;
        return override == null || override.trim().length() == 0
                ? VERSION_URL : override.trim();
    }

    private static boolean hasManifestOverride() {
        String override = BuildConfig.UPDATE_MANIFEST_OVERRIDE;
        return override != null && override.trim().length() > 0;
    }
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_APK_BYTES = 256L * 1024L * 1024L;

    static final String STATE_IDLE = "idle";
    static final String STATE_CHECKING = "checking";
    static final String STATE_UP_TO_DATE = "upToDate";
    static final String STATE_AVAILABLE = "available";
    static final String STATE_ERROR = "error";

    /** I4 B6/B8：检查周期与静默下载状态（自动下载后不再有「发现新版本」确认框）。 */
    static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    static final String DOWNLOAD_IDLE = "idle";
    static final String DOWNLOAD_DOWNLOADING = "downloading";
    static final String DOWNLOAD_READY = "ready";
    static final String DOWNLOAD_FAILED = "failed";
    /** 用户点过「稍后」的版本与时间（I4 review P1）：跨重启生效，24 小时内不再提示。 */
    private static final String PREF_INSTALL_SNOOZE_VERSION = "update_install_snooze_version";
    private static final String PREF_INSTALL_SNOOZE_AT = "update_install_snooze_at";
    /** 更新说明只用于控制页展示（I4 review P2）：压成单行并限长，避免撑爆维护页。 */
    private static final int NOTES_MAX_CHARS = 300;

    /** I4 决策 4：检查结果的只读四态，供控制页轮询展示（不新增设置项）。 */
    private static volatile String checkState = STATE_IDLE;
    private static volatile String checkReason = "";
    private static volatile String remoteVersionName = "";
    private static volatile String remoteNotes = "";
    private static volatile int remoteVersionCode;
    private static volatile long lastCheckAt;
    private static volatile String checkSource = "";
    private static volatile String downloadState = DOWNLOAD_IDLE;
    private static volatile String downloadReason = "";
    private static volatile int downloadProgress;
    private static volatile int downloadedVersionCode;
    private volatile File downloadedApk;

    private final Activity activity;
    private volatile boolean destroyed;
    private boolean checking;
    private boolean installPromptShowing;
    private AlertDialog installPromptDialog;

    AutoUpdater(Activity activity) {
        this.activity = activity;
    }

    void checkForUpdates() {
        if (checking || destroyed) {
            return;
        }
        checking = true;
        setCheckState(STATE_CHECKING, "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final UpdateInfo update = loadUpdateInfo();
                    lastCheckAt = System.currentTimeMillis();
                    if (update.versionCode <= BuildConfig.VERSION_CODE || destroyed) {
                        setCheckState(STATE_UP_TO_DATE, "");
                        return;
                    }
                    remoteVersionName = update.versionName;
                    remoteNotes = readableNotes(update.releaseNotes);
                    remoteVersionCode = update.versionCode;
                    setCheckState(STATE_AVAILABLE, "");
                    Log.i(TAG, "Update available: " + update.versionName + ", asset="
                            + BuildConfig.UPDATE_APK_ASSET);
                    // I4 B8：发现新版本即后台静默下载，下载完成后再提示安装（全程一次打扰）。
                    startAutoDownload(update);
                } catch (Exception error) {
                    // 启动检查仍然静默（离线不打扰），但状态对控制页可见。
                    lastCheckAt = System.currentTimeMillis();
                    setCheckState(STATE_ERROR, readableMessage(error));
                    Log.w(TAG, "Update check failed", error);
                } finally {
                    checking = false;
                    // 本线程实际使用的地址：避免被 CJS 插件等其它链路的记录覆盖。
                    checkSource = GithubProxy.lastUsedInThread();
                }
            }
        }, "update-check").start();
    }

    /**
     * I4 B6：距上次检查超过 intervalMs 才真正检查。
     * 首次启动（lastCheckAt == 0）必然检查，保持原有「启动即检查」的行为。
     */
    void checkForUpdatesIfDue(long intervalMs) {
        long now = System.currentTimeMillis();
        if (lastCheckAt > 0L && now - lastCheckAt < intervalMs) {
            Log.i(TAG, "Update check skipped: interval not reached");
            return;
        }
        checkForUpdates();
    }

    /**
     * I4 B8：发现新版本即后台静默下载，校验通过后再提示安装——
     * 全程只打扰用户一次，不再弹「发现新版本」确认框（2026-09-25 拍板）。
     */
    private void startAutoDownload(final UpdateInfo update) {
        if (downloadedVersionCode == update.versionCode) {
            if (DOWNLOAD_DOWNLOADING.equals(downloadState)) {
                return;
            }
            if (DOWNLOAD_READY.equals(downloadState) && downloadedApk != null
                    && downloadedApk.isFile()) {
                final File cached = downloadedApk;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showInstallPrompt(cached);
                    }
                });
                return;
            }
        }
        downloadedVersionCode = update.versionCode;
        downloadUpdate(update);
    }

    /** I4 B8：静默下载完成后的安装提示；安装动作仍需 TV 端系统确认，无法静默安装。 */
    private void showInstallPrompt(final File apk) {
        if (destroyed || activity.isFinishing() || installPromptShowing) {
            return;
        }
        // I4 review P1：用户点过「稍后」的同一版本，24 小时内不再提示（跨进程重启同样生效）。
        SharedPreferences preferences = preferences();
        if (!UpdatePromptPolicy.shouldPrompt(downloadedVersionCode,
                preferences.getInt(PREF_INSTALL_SNOOZE_VERSION, 0),
                preferences.getLong(PREF_INSTALL_SNOOZE_AT, 0L),
                System.currentTimeMillis())) {
            Log.i(TAG, "Install prompt snoozed version=" + downloadedVersionCode);
            return;
        }
        installPromptShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_ready_title, remoteVersionName))
                .setMessage(R.string.update_ready_message)
                .setPositiveButton(R.string.update_install_now,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int which) {
                                installPromptShowing = false;
                                install(apk);
                            }
                        })
                .setNegativeButton(R.string.update_later,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int which) {
                                installPromptShowing = false;
                                snoozeInstallPrompt();
                            }
                        })
                .create();
        installPromptDialog = dialog;
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                installPromptShowing = false;
                snoozeInstallPrompt();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                installPromptShowing = false;
                if (installPromptDialog == dialogInterface) {
                    installPromptDialog = null;
                }
            }
        });
        dialog.show();
    }

    private SharedPreferences preferences() {
        return activity.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE);
    }

    /** 用户拒绝本次安装（点「稍后」或按返回）：同一版本 24 小时内不再提示。 */
    private void snoozeInstallPrompt() {
        preferences().edit()
                .putInt(PREF_INSTALL_SNOOZE_VERSION, downloadedVersionCode)
                .putLong(PREF_INSTALL_SNOOZE_AT, System.currentTimeMillis())
                .apply();
    }

    /**
     * 更新说明（清单里的 releaseNotes）只用于控制页展示（I4 review P2）：
     * 压成单行、合并连续空白并限长，避免长文本把维护页撑爆。
     */
    private static String readableNotes(String notes) {
        if (notes == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(NOTES_MAX_CHARS + 1);
        boolean pendingSpace = false;
        boolean truncated = false;
        for (int index = 0; index < notes.length(); index++) {
            char value = notes.charAt(index);
            if (value == '\n' || value == '\r' || value == '\t' || value == ' ') {
                pendingSpace = builder.length() > 0;
                continue;
            }
            if (builder.length() >= NOTES_MAX_CHARS) {
                truncated = true;
                break;
            }
            if (pendingSpace) {
                builder.append(' ');
                pendingSpace = false;
            }
            builder.append(value);
        }
        if (truncated) {
            builder.append('…');
        }
        return builder.toString();
    }

    /** 检查状态（I4 B1/B2）：控制页只读展示，轮询 /api/state 的 update 块。 */
    static String statusJson() {
        try {
            return new JSONObject()
                    .put("state", checkState)
                    .put("reason", checkReason)
                    .put("remoteVersion", remoteVersionName)
                    .put("remoteNotes", remoteNotes)
                    .put("remoteVersionCode", remoteVersionCode)
                    .put("currentVersion", BuildConfig.VERSION_NAME)
                    .put("currentVersionCode", BuildConfig.VERSION_CODE)
                    .put("lastCheckAt", lastCheckAt)
                    .put("source", checkSource)
                    .put("downloadState", downloadState)
                    .put("downloadReason", downloadReason)
                    .put("downloadProgress", downloadProgress)
                    .put("downloadedVersionCode", downloadedVersionCode)
                    .put("checkIntervalMs", CHECK_INTERVAL_MS)
                    .toString();
        } catch (JSONException error) {
            return "{}";
        }
    }

    static String state() {
        return checkState;
    }

    private static void setCheckState(String value, String reason) {
        checkState = value;
        checkReason = reason == null ? "" : reason;
    }

    private static void setDownloadState(String value, String reason) {
        downloadState = value;
        downloadReason = reason == null ? "" : reason;
    }

    void destroy() {
        destroyed = true;
        if (installPromptDialog != null) {
            installPromptDialog.dismiss();
            installPromptDialog = null;
        }
    }

    private UpdateInfo loadUpdateInfo() throws IOException, JSONException {
        // 清单同样走候选回退（I4 决策 5）：小文件也要能在首选加速域挂掉时拿到。
        HttpURLConnection connection = openConnection(
                manifestUrl() + "?_=" + System.currentTimeMillis(), true);
        String json;
        try {
            requireSuccessful(connection);
            InputStream input = new BufferedInputStream(connection.getInputStream());
            try {
                json = readUtf8(input, MAX_MANIFEST_BYTES);
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }

        JSONObject object = new JSONObject(json);
        int versionCode = object.getInt("versionCode");
        String versionName = object.getString("versionName").trim();
        String apkUrl = object.optString(BuildConfig.UPDATE_APK_URL_FIELD, "").trim();
        String sha256 = object.optString(BuildConfig.UPDATE_SHA256_FIELD, "")
                .trim().toLowerCase(Locale.US);
        String releaseNotes = object.optString("releaseNotes", "").trim();
        if (versionCode < 1 || versionName.length() == 0) {
            throw new JSONException("Invalid version metadata");
        }
        if (apkUrl.length() == 0) {
            throw new JSONException("Missing APK URL for " + BuildConfig.UPDATE_APK_ASSET);
        }
        if (!apkUrl.endsWith("/" + BuildConfig.UPDATE_APK_ASSET)) {
            throw new JSONException("Wrong APK URL for " + BuildConfig.UPDATE_APK_ASSET);
        }
        apkUrl = validatedApkUrl(apkUrl);
        if (sha256.length() > 0 && !sha256.matches("[0-9a-f]{64}")) {
            throw new JSONException("Invalid APK SHA-256");
        }
        return new UpdateInfo(versionCode, versionName, apkUrl, sha256, releaseNotes);
    }

    /** I4 B8：后台静默下载更新包（无进度框、不遮挡播放），完成后进入安装提示。 */
    private void downloadUpdate(final UpdateInfo update) {
        setDownloadState(DOWNLOAD_DOWNLOADING, "");
        downloadProgress = 0;

        new Thread(new Runnable() {
            @Override
            public void run() {
                File partial = null;
                try {
                    File directory = ApkFileProvider.updateDirectory(activity);
                    if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
                        throw new IOException("无法创建更新目录");
                    }
                    File apk = new File(directory, BuildConfig.UPDATE_APK_ASSET
                            .replace(".apk", "-" + update.versionCode + ".apk"));
                    if (apk.isFile() && verifySha256(apk, update.sha256) && isApk(apk)) {
                        finishDownload(apk);
                        return;
                    }
                    partial = new File(directory, apk.getName() + ".part");
                    if (partial.exists() && !partial.delete()) {
                        throw new IOException("无法清理旧的临时文件");
                    }
                    download(update.apkUrl, partial);
                    if (!verifySha256(partial, update.sha256)) {
                        throw new IOException("APK 完整性校验失败");
                    }
                    if (!isApk(partial)) {
                        throw new IOException("下载内容不是有效的 APK");
                    }
                    if (apk.exists() && !apk.delete()) {
                        throw new IOException("无法替换旧的更新文件");
                    }
                    if (!partial.renameTo(apk)) {
                        copyFile(partial, apk);
                        if (!partial.delete()) {
                            Log.w(TAG, "Unable to remove partial APK " + partial);
                        }
                    }
                    finishDownload(apk);
                } catch (final Exception error) {
                    Log.e(TAG, "Update download failed", error);
                    if (partial != null && partial.exists() && !partial.delete()) {
                        Log.w(TAG, "Unable to remove failed partial APK " + partial);
                    }
                    // I4 B9：失败原因进状态块（控制页可见）；自动下载不弹 Toast、不打扰观看。
                    setDownloadState(DOWNLOAD_FAILED, readableMessage(error));
                }
            }
        }, "update-download").start();
    }

    private void download(String url, File destination) throws IOException {
        HttpURLConnection connection = openConnection(url, false);
        try {
            requireSuccessful(connection);
            final long length = contentLength(connection);
            if (length > MAX_APK_BYTES) {
                throw new IOException("APK 文件过大");
            }
            InputStream input = new BufferedInputStream(connection.getInputStream());
            FileOutputStream output = new FileOutputStream(destination);
            try {
                byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int lastProgress = -1;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (destroyed) {
                        throw new IOException("下载已取消");
                    }
                    total += count;
                    if (total > MAX_APK_BYTES) {
                        throw new IOException("APK 文件过大");
                    }
                    output.write(buffer, 0, count);
                    if (length > 0L) {
                        final int progress = (int) Math.min(100L, total * 100L / length);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            downloadProgress = progress;
                        }
                    }
                }
                output.getFD().sync();
            } finally {
                try {
                    output.close();
                } finally {
                    input.close();
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void finishDownload(final File apk) {
        downloadedApk = apk;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                setDownloadState(DOWNLOAD_READY, "");
                // I4 B8：静默下载完成 → 提示安装（安装动作仍需 TV 端系统确认）
                showInstallPrompt(apk);
            }
        });
    }

    private void install(final File apk) {
        launchInstaller(apk);
    }

    private void launchInstaller(File apk) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = ApkFileProvider.uriForFile(activity, apk);
            } else {
                apk.setReadable(true, false);
                uri = Uri.fromFile(apk);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            activity.startActivity(intent);
        } catch (Exception error) {
            Log.e(TAG, "Unable to launch package installer", error);
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 清单与 APK 都走加速候选回退（I4 决策 5）：首选加速域失败会依次尝试下一个候选，
     * 最后兜底直连 GitHub。
     */
    private static HttpURLConnection openConnection(String url, final boolean manifest)
            throws IOException {
        return GithubProxy.open(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                new GithubProxy.HeaderSetter() {
                    @Override
                    public void apply(HttpURLConnection connection) {
                        connection.setRequestProperty("Accept-Encoding", "identity");
                        connection.setRequestProperty("User-Agent", "nTv/" + BuildConfig.VERSION_NAME
                                + " Android/" + Build.VERSION.RELEASE);
                        if (manifest) {
                            connection.setRequestProperty("Accept", "application/json");
                            connection.setRequestProperty("Cache-Control", "no-cache");
                        }
                    }
                });
    }

    private static void requireSuccessful(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status);
        }
    }

    private static long contentLength(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    /**
     * 只接受 HTTPS 的 GitHub / Gitee 地址；返回原始地址，
     * 加速与否交给 {@link GithubProxy#open} 的候选回退决定（I4 决策 5/6）。
     */
    private static String validatedApkUrl(String url) throws JSONException {
        if (hasManifestOverride()) {
            // 测试通道：本地清单允许指向任意地址（仅编译期开关生效时，发布包默认关闭）。
            return url;
        }
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.US);
            boolean github = "github.com".equals(host)
                    || "raw.githubusercontent.com".equals(host);
            boolean gitee = "gitee.com".equals(host) || host.endsWith(".gitee.com");
            if (!"https".equalsIgnoreCase(parsed.getProtocol()) || (!github && !gitee)) {
                throw new JSONException("APK URL must be an HTTPS GitHub/Gitee URL");
            }
            return url;
        } catch (IOException error) {
            throw new JSONException("Invalid APK URL");
        }
    }

    private static String readUtf8(InputStream input, int limit) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[2048];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (text.length() + count > limit) {
                throw new IOException("版本文件过大");
            }
            text.append(buffer, 0, count);
        }
        return text.toString();
    }

    private static boolean verifySha256(File file, String expected)
            throws IOException, NoSuchAlgorithmException {
        if (expected.length() == 0) {
            Log.w(TAG, "Update manifest has no SHA-256; APK authenticity is not pinned");
            return true;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new BufferedInputStream(new FileInputStream(file));
        try {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        byte[] hash = digest.digest();
        StringBuilder actual = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            actual.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return expected.equals(actual.toString());
    }

    private static boolean isApk(File file) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            return zip.getEntry("AndroidManifest.xml") != null;
        } catch (IOException error) {
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        InputStream input = new BufferedInputStream(new FileInputStream(source));
        FileOutputStream output = new FileOutputStream(destination);
        try {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } finally {
            try {
                output.close();
            } finally {
                input.close();
            }
        }
    }

    private static String readableMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String releaseNotes;

        UpdateInfo(int versionCode, String versionName, String apkUrl,
                String sha256, String releaseNotes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.releaseNotes = releaseNotes;
        }
    }
}
