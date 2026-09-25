package xiao.bu.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 诊断发送通道的纯逻辑回归（I4 E1）：签名、请求体、候选与成功判定。
 *
 * <p>这些用例锁住的是「发得出去、失败看得见」：
 * 钉钉即使业务失败也返回 HTTP 200，所以 {@code errcode} 判定必须有测试兜底。
 */
public final class DiagnosticsSenderTest {
    private static final String DINGTALK_URL =
            "https://oapi.dingtalk.com/robot/send?access_token=test";

    @Test
    public void base64MatchesStandardVectors() {
        assertEquals("TWFu", DiagnosticsSender.base64Encode(bytes("Man")));
        assertEquals("TQ==", DiagnosticsSender.base64Encode(bytes("M")));
        assertEquals("TWE=", DiagnosticsSender.base64Encode(bytes("Ma")));
        assertEquals("", DiagnosticsSender.base64Encode(new byte[0]));
    }

    @Test
    public void hmacSha256MatchesKnownVector() throws Exception {
        byte[] digest = DiagnosticsSender.hmacSha256(
                bytes("key"), bytes("The quick brown fox jumps over the lazy dog"));
        assertEquals("f7bc83f430538424b13298e6aa6fb143"
                + "ef4d59a14946175997479dbc2d1a3cd8", toHex(digest));
    }

    @Test
    public void appendsSignedQueryOnlyWhenSecretIsPresent() {
        String plain = DiagnosticsSender.dingTalkUrl(DINGTALK_URL, "", 1700000000000L);
        assertEquals(DINGTALK_URL, plain);

        String signed = DiagnosticsSender.dingTalkUrl(DINGTALK_URL, "SECabc", 1700000000000L);
        assertTrue(signed.startsWith(DINGTALK_URL + "&timestamp=1700000000000&sign="));
        // sign 必须是 URL 编码后的 Base64：不应残留未转义字符。
        String sign = signed.substring(signed.indexOf("sign=") + "sign=".length());
        assertFalse(sign.contains("+"));
        assertFalse(sign.contains("/"));
        assertFalse(sign.contains("="));
        assertFalse(sign.contains(" "));
    }

    @Test
    public void signatureIsDeterministicAndTimeSensitive() {
        String first = DiagnosticsSender.dingTalkUrl(DINGTALK_URL, "SECabc", 1700000000000L);
        String again = DiagnosticsSender.dingTalkUrl(DINGTALK_URL, "SECabc", 1700000000000L);
        String later = DiagnosticsSender.dingTalkUrl(DINGTALK_URL, "SECabc", 1700000000001L);
        assertEquals(first, again);
        assertNotEquals(first, later);
    }

    @Test
    public void keepsAtMostTwoCandidatesAndDropsBlanks() {
        String[] two = DiagnosticsSender.candidates(" https://a ; https://b ; https://c ");
        assertEquals(2, two.length);
        assertEquals("https://a", two[0]);
        assertEquals("https://b", two[1]);

        String[] single = DiagnosticsSender.candidates("https://only;https://only");
        assertEquals(1, single.length);

        assertEquals(0, DiagnosticsSender.candidates("  ;  ").length);
        assertEquals(0, DiagnosticsSender.candidates(null).length);
    }

    @Test
    public void dingtalkBodyCarriesTheKeywordAndGenericBodyDoesNot() throws Exception {
        String dingtalk = DiagnosticsSender.requestBody(DINGTALK_URL, "报告内容", "nTv");
        assertTrue(dingtalk.contains("\"msgtype\":\"text\""));
        assertTrue(dingtalk.indexOf("nTv") >= 0);

        String already = DiagnosticsSender.requestBody(DINGTALK_URL, "nTv 报告内容", "nTv");
        assertEquals(already.indexOf("nTv"), already.lastIndexOf("nTv"));

        String generic = DiagnosticsSender.requestBody("https://example.com/hook", "报告内容", "nTv");
        assertFalse(generic.contains("msgtype"));
        assertTrue(generic.contains("报告内容"));
    }

    @Test
    public void treatsDingtalkBusinessErrorsAsFailures() {
        assertEquals("", DiagnosticsSender.responseError(DINGTALK_URL, "{\"errcode\":0}"));
        assertTrue(DiagnosticsSender.responseError(DINGTALK_URL, "{\"errcode\":310000}")
                .contains("安全设置"));
        assertTrue(DiagnosticsSender.responseError(DINGTALK_URL,
                "{\"errcode\":300001,\"errmsg\":\"token is not exist\"}")
                .contains("300001"));
        assertTrue(DiagnosticsSender.responseError(DINGTALK_URL, "").length() > 0);
        assertTrue(DiagnosticsSender.responseError(DINGTALK_URL, "not json").length() > 0);
        // 通用通道只看 HTTP 状态，响应体不参与判定。
        assertEquals("", DiagnosticsSender.responseError("https://example.com/hook", "not json"));
    }

    @Test
    public void rateLimitsThreeSendsPerWindowThenRecovers() {
        DiagnosticsSender.resetSendHistory();
        long now = 1700000000000L;
        assertTrue(DiagnosticsSender.allowsSend(now));
        assertTrue(DiagnosticsSender.allowsSend(now + 1000L));
        assertTrue(DiagnosticsSender.allowsSend(now + 2000L));
        assertFalse(DiagnosticsSender.allowsSend(now + 3000L));

        // 窗口滑出后恢复。
        assertTrue(DiagnosticsSender.allowsSend(now + DiagnosticsSender.SEND_WINDOW_MS + 1L));
        DiagnosticsSender.resetSendHistory();
    }

    private static byte[] bytes(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) {
            text.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return text.toString();
    }
}
