package xiao.bu.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** I4 review P1：安装提示的打扰规则——点过「稍后」的同一版本 24 小时内不再提示。 */
public class UpdatePromptPolicyTest {
    private static final long HOUR_MS = 60L * 60L * 1000L;

    @Test
    public void promptsWhenUserNeverDismissed() {
        assertTrue(UpdatePromptPolicy.shouldPrompt(9, 0, 0L, 1_000L));
    }

    @Test
    public void staysQuietRightAfterLater() {
        long now = 1_000_000L;
        assertFalse(UpdatePromptPolicy.shouldPrompt(9, 9, now, now + HOUR_MS));
    }

    @Test
    public void promptsAgainAfterSnoozeWindow() {
        long at = 1_000_000L;
        assertTrue(UpdatePromptPolicy.shouldPrompt(9, 9, at, at + UpdatePromptPolicy.SNOOZE_MS));
    }

    @Test
    public void promptsWhenSnoozedVersionIsOlder() {
        // 上一个版本点过「稍后」，不应压掉新版本的提醒。
        assertTrue(UpdatePromptPolicy.shouldPrompt(10, 9, 1_000_000L, 1_000_001L));
    }

    @Test
    public void staysQuietWhenClockMovedBackwards() {
        long at = 5_000_000L;
        assertFalse(UpdatePromptPolicy.shouldPrompt(9, 9, at, at - HOUR_MS));
    }

    @Test
    public void neverPromptsWithoutDownloadedVersion() {
        assertFalse(UpdatePromptPolicy.shouldPrompt(0, 0, 0L, 1_000L));
    }
}
