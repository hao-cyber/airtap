package io.airtap.preview;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Immutable artifact expectations obtained with this ticket over the phone's verified TLS path. */
record InvitationArtifacts(String identity, String identityFingerprint, String ticket, long expiry,
                          String cliSha256, String guideSha256) {
    InvitationArtifacts {
        if (identity == null || identity.isEmpty() || !hex(identityFingerprint, 64)
                || !hex(ticket, 32) || expiry <= 0 || !hex(cliSha256, 64) || !hex(guideSha256, 64))
            throw new IllegalArgumentException("invalid_invitation_artifacts");
    }
    private static boolean hex(String value, int length) {
        return value != null && value.matches("[a-f0-9]{" + length + "}");
    }
    static String fingerprint(String token) {
        if (!hex(token, 64)) throw new IllegalArgumentException();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest) value.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    static InvitationArtifacts fromResponse(String identity, String token, String ticket, long expiry, JSONObject body) {
        Object cli = body.opt("cli_sha256"), guide = body.opt("agent_sha256");
        if (!(cli instanceof String) || !(guide instanceof String))
            throw new InvitationFeedback.Rejected("invitation_artifacts_unavailable");
        try { return new InvitationArtifacts(identity, fingerprint(token), ticket, expiry, (String)cli, (String)guide); }
        catch (RuntimeException invalid) { throw new InvitationFeedback.Rejected("invitation_artifacts_unavailable"); }
    }
    boolean matches(String currentIdentity, String token, String currentTicket, long currentExpiry, long now) {
        return identity.equals(currentIdentity) && identityFingerprint.equals(fingerprint(token))
                && ticket.equals(currentTicket) && expiry == currentExpiry && expiry > now && expiry <= now + 330;
    }
    String verificationInstructions() {
        return "先下载下面的接入说明为原始字节，按本邀请中的 AIRTAP_GUIDE_SHA256 核对 SHA-256，一致后才阅读并使用说明；"
                + "若使用 Python CLI，同样先按 AIRTAP_CLI_SHA256 核对原始文件，一致后才执行。不要把下载响应里的摘要当作本邀请摘要的替代。"
                + "摘要缺失或不一致时保留原连接、停止使用该下载内容并说明原因，不关闭 TLS 验证。\n"
                + "AIRTAP_GUIDE_SHA256=" + guideSha256 + "\nAIRTAP_CLI_SHA256=" + cliSha256;
    }
}
