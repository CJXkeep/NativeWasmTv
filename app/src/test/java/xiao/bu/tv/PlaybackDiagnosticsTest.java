package xiao.bu.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 诊断数据层的纯逻辑回归（I4 E1）：
 * 脱敏规则与「头部优先」截断方向——后者是 review 修正过的地方，必须有测试钉住。
 */
public final class PlaybackDiagnosticsTest {

    @Test
    public void dropsQueryAndFragmentButKeepsHostAndPath() {
        assertEquals("http://host/path…",
                PlaybackDiagnostics.sanitizeUrl("http://host/path?token=secret&x=1"));
        assertEquals("http://host/path…",
                PlaybackDiagnostics.sanitizeUrl("http://host/path#fragment"));
        assertEquals("http://host/path", PlaybackDiagnostics.sanitizeUrl("http://host/path"));
        assertEquals("webview://cctv/live…",
                PlaybackDiagnostics.sanitizeUrl("webview://cctv/live?pid=1"));
    }

    @Test
    public void handlesEmptyAndOverlongAddresses() {
        assertEquals("", PlaybackDiagnostics.sanitizeUrl(null));
        assertEquals("", PlaybackDiagnostics.sanitizeUrl("   "));
        StringBuilder longUrl = new StringBuilder("http://host/");
        while (longUrl.length() < 400) {
            longUrl.append('a');
        }
        String sanitized = PlaybackDiagnostics.sanitizeUrl(longUrl.toString());
        assertTrue(sanitized.endsWith("…"));
        assertEquals(PlaybackDiagnostics.LINE_MAX_LENGTH + 1, sanitized.length());
    }

    @Test
    public void truncationKeepsTheHeadNotTheTail() {
        // 崩溃栈的价值在异常类型与消息（头部），框架帧尾部应被丢弃。
        String text = "java.lang.RuntimeException: 关键原因\n" + repeat('尾', 4000);
        String truncated = PlaybackDiagnostics.truncateHead(text, 256);
        assertTrue(truncated.startsWith("java.lang.RuntimeException: 关键原因"));
        assertTrue(truncated.endsWith("…"));
        assertTrue(text.startsWith(truncated.substring(0, truncated.length() - 1)));
    }

    @Test
    public void truncationNeverSplitsAMultiByteCharacter() {
        String text = repeat('中', 500);
        String truncated = PlaybackDiagnostics.truncateHead(text, 101);
        assertTrue(truncated.endsWith("…"));
        // 不允许出现替换字符（U+FFFD），否则说明切断了 UTF-8 多字节序列。
        assertTrue(truncated.indexOf('\uFFFD') < 0);
    }

    @Test
    public void shortTextIsReturnedUnchanged() {
        assertEquals("短文本", PlaybackDiagnostics.truncateHead("短文本", 1024));
        assertEquals("", PlaybackDiagnostics.truncateHead(null, 1024));
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(value);
        }
        return text.toString();
    }
}
