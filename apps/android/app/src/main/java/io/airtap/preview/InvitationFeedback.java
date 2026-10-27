package io.airtap.preview;

import org.json.JSONObject;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Explain strict invitation rejection without trusting remote text or relaxing its validity window. */
final class InvitationFeedback {
    record ServerTime(long earliest, long latest) {}
    static final class Rejected extends IllegalArgumentException {
        final String code, userMessage;
        Rejected(String code) { super(code); this.code = code; this.userMessage = message(code); }
    }
    static ServerTime serverTime(String httpDate, long requestMillis, long callbackDelayMillis) {
        // Date comes from the configured HTTPS response. Bound transport/queue delay before inferring skew.
        if (httpDate == null || httpDate.length() > 80 || requestMillis < 0 || requestMillis > 20000
                || callbackDelayMillis < 0 || callbackDelayMillis > 300000) return null;
        try {
            long epoch = ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
            if (epoch <= 0 || epoch > 9007199254740991L) return null;
            return new ServerTime(epoch + callbackDelayMillis / 1000,
                    epoch + (requestMillis + callbackDelayMillis + 999) / 1000 + 1);
        } catch (RuntimeException malformed) { return null; }
    }
    static long validate(JSONObject body, long now, ServerTime serverTime) {
        final long expiry;
        try { expiry = ServiceProtocol.invitationShape(body); }
        catch (RuntimeException invalid) { throw new Rejected("invalid_invitation_response"); }
        // Exact existing acceptance rule: no adjustment of the device clock or ticket expiry.
        if (expiry > now && expiry <= now + 330) return expiry;
        if (serverTime != null) {
            if (expiry <= serverTime.earliest) throw new Rejected("invitation_expired");
            if (expiry > serverTime.latest + 330) throw new Rejected("invalid_invitation_response");
            boolean serverValid = expiry > serverTime.latest && expiry <= serverTime.earliest + 330;
            boolean clocksDiffer = serverTime.earliest - now > 30 || now - serverTime.latest > 30;
            if (serverValid && clocksDiffer) throw new Rejected("device_service_clock_mismatch");
        }
        // An expiry alone cannot distinguish a slow/fast phone from an old or malformed response.
        throw new Rejected("invitation_time_unconfirmed");
    }
    static String message(String code) {
        return switch (code) {
            case "invitation_artifacts_unavailable" -> "服务尚未提供有效的接入文件校验信息，请更新服务或反馈问题。原手机身份已保留，无需重新登记。";
            case "device_service_clock_mismatch" -> "手机与服务的时间相差较大，邀请暂未接受。请在手机“日期和时间”中开启自动设置，确认时间正确后重新生成邀请；自托管用户也请检查服务时间。";
            case "invitation_expired" -> "这份邀请已过期，请重新生成邀请，再发给自己的 AI。";
            case "invitation_time_unconfirmed" -> "无法确认这份邀请仍在有效时间内。请检查手机日期和时间，再重新生成邀请。";
            default -> "邀请响应格式或有效期不符合要求。请重新生成；若仍失败，请检查 App 与服务版本或反馈问题。";
        };
    }
}
