package io.airtap.preview;

/** Success copy is tied to a particular current grant, never a historical network response. */
final class ConnectionFeedback {
    private String approvedClaim, recoveringClaim;
    String reconcile(String message, String trustedClaim, String pendingClaim) {
        if (pendingClaim != null) recoveringClaim = pendingClaim;
        if (recoveringClaim != null && recoveringClaim.equals(trustedClaim)) {
            approvedClaim = trustedClaim; recoveringClaim = null;
            return "已记住你的 AI，以后可以直接使用。";
        }
        if (recoveringClaim != null && pendingClaim == null) {
            recoveringClaim = null;
            if (trustedClaim == null) return "这次连接已失效，请重新邀请自己的 AI。";
        }
        if (approvedClaim != null && !approvedClaim.equals(trustedClaim)) {
            approvedClaim = null;
            return "连接已解除，请重新邀请自己的 AI。";
        }
        return message;
    }
    void approved(String claim) { approvedClaim = claim; recoveringClaim = null; }
    void clear() { approvedClaim = null; recoveringClaim = null; }
}
