package io.airtap.preview;

/** Display defense only, not a claim that arbitrary private content can be automatically redacted. */
final class FeedbackText {
    private FeedbackText() {}

    static String display(String input, String deviceToken) {
        String value = input;
        if (deviceToken != null && !deviceToken.isEmpty()) value = value.replace(deviceToken, "[连接凭据已隐藏]");
        // The AI prepares the submitted content. Do not guess which ordinary bug details are secrets.
        return value;
    }

    static String status(String status) {
        switch (status) {
            case "new": return "已收到";
            case "investigating": return "处理中";
            case "needs_info": return "需要补充说明";
            case "resolved": return "已解决";
            case "closed": return "已关闭";
            default: return "状态待更新";
        }
    }
}
