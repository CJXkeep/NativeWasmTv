package xiao.bu.tv;

/**
 * 坏台终态策略：把「失败之后做什么」从 Activity 里抽成无状态纯函数，便于 JVM 单测覆盖。
 */
final class PlaybackFailurePolicy {
    /** 连续自动跳台的次数上限；达到后停在常驻终态，避免源大面积失效时的跳台风暴。 */
    static final int SKIP_CHANNEL_MAX_CONSECUTIVE = 2;

    /** 终态提示里的一键反馈入口（I4 A7）：长按 OK 把脱敏诊断发到作者接收端。 */
    static final String FEEDBACK_HINT = "长按 OK 反馈问题";

    private PlaybackFailurePolicy() {
    }

    /** 是否允许为了离开坏台而跳到下一个频道。 */
    static boolean canSkipChannel(boolean autoSwitchEnabled, int consecutiveSkips) {
        return autoSwitchEnabled && consecutiveSkips < SKIP_CHANNEL_MAX_CONSECUTIVE;
    }

    /** 坏台终态文案：只给家庭用户一句话和一个可执行动作，技术原因走 state 与日志。 */
    static String terminalStatus(boolean autoSwitchEnabled, int sourceCount) {
        String state;
        String action;
        if (!autoSwitchEnabled && sourceCount > 1) {
            state = "线路不可用";
            action = "←→ 换线路 / OK 打开列表";
        } else if (sourceCount <= 1) {
            state = "唯一线路不可用";
            action = "↑↓ 换台 / OK 打开列表";
        } else {
            state = "所有线路均不可用";
            action = "↑↓ 换台 / OK 打开列表";
        }
        return state + "，" + action + "（" + FEEDBACK_HINT + "）";
    }
}
