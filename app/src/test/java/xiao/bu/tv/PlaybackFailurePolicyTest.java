package xiao.bu.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 坏台终态策略的回归用例：连跳上限与终态文案。 */
public final class PlaybackFailurePolicyTest {

    @Test
    public void allowsSkippingUntilTheConsecutiveLimit() {
        assertTrue(PlaybackFailurePolicy.canSkipChannel(true, 0));
        assertTrue(PlaybackFailurePolicy.canSkipChannel(true, 1));
        assertFalse(PlaybackFailurePolicy.canSkipChannel(true, 2));
        assertFalse(PlaybackFailurePolicy.canSkipChannel(true, 3));
    }

    @Test
    public void neverSkipsWhenAutomaticSwitchingIsOff() {
        assertFalse(PlaybackFailurePolicy.canSkipChannel(false, 0));
    }

    @Test
    public void namesTheActionAUserCanStillTake() {
        // 终态文案带一键反馈入口（I4 A7）：长按 OK 是家人唯一需要做的新动作。
        String hint = "（" + PlaybackFailurePolicy.FEEDBACK_HINT + "）";
        assertEquals("唯一线路不可用，↑↓ 换台 / OK 打开列表" + hint,
                PlaybackFailurePolicy.terminalStatus(true, 1));
        assertEquals("所有线路均不可用，↑↓ 换台 / OK 打开列表" + hint,
                PlaybackFailurePolicy.terminalStatus(true, 3));
        assertEquals("线路不可用，←→ 换线路 / OK 打开列表" + hint,
                PlaybackFailurePolicy.terminalStatus(false, 3));
        assertEquals("唯一线路不可用，↑↓ 换台 / OK 打开列表" + hint,
                PlaybackFailurePolicy.terminalStatus(false, 1));
    }
}
