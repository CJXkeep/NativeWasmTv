package xiao.bu.tv;

/**
 * 更新安装提示的打扰规则（I4 review P1）：纯函数，便于单测。
 *
 * <p>「稍后」是用户明确的拒绝——同一版本 {@link #SNOOZE_MS} 内不再提示；
 * 从未点过「稍后」（对话框自行消失、进程重启后首次检查等）则每次都提醒，
 * 保证「有更新待安装」这件事不会被静默吞掉。
 */
final class UpdatePromptPolicy {
    /** 点过「稍后」后不再提示的时长：一天最多问一次，且是用户自己拒绝的版本。 */
    static final long SNOOZE_MS = 24L * 60L * 60L * 1000L;

    private UpdatePromptPolicy() {
    }

    /**
     * @param versionCode   待安装的更新版本号
     * @param snoozeVersion 上次被「稍后」拒绝的版本号（0 表示从未拒绝过）
     * @param snoozeAt      上次「稍后」的时间戳（0 表示从未拒绝过）
     * @param now           当前时间戳
     * @return true = 可以弹安装提示
     */
    static boolean shouldPrompt(int versionCode, int snoozeVersion, long snoozeAt, long now) {
        if (versionCode <= 0) {
            return false;
        }
        if (snoozeVersion != versionCode || snoozeAt <= 0L) {
            return true;
        }
        // 时钟回拨（snoozeAt 在未来）无法判断经过了多久，按「静默」处理，避免反复打扰。
        return now - snoozeAt >= SNOOZE_MS;
    }
}
