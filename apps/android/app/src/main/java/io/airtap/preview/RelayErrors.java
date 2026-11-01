package io.airtap.preview;

/** Never display a server-supplied sentence, URL or credential in an error. */
final class RelayErrors {
    /** These local state codes are not storage failures; unknown exceptions stay private. */
    static String invitationPrecondition(String code) {
        return switch (code == null ? "" : code) {
            case "client_upgrade_required" -> "已有 AI 还未完成首次任务接管。请让它使用支持多 AI 的接入工具，继续已授权的手机任务，再回来准备邀请。原连接保留。";
            case "grant_limit_reached" -> "已连接 4 位 AI。请先解除不再使用的一位，再准备新邀请；其他连接保留。";
            case "approval_pending" -> "一位 AI 的连接仍在核实。请先完成或取消当前邀请，再准备新邀请；已有连接保留。";
            default -> null;
        };
    }

    static String message(int status, String code) {
        String detail = switch (code) {
            case "client_upgrade_required", "grant_limit_reached" -> invitationPrecondition(code);
            case "invitation_pending" -> "服务端已有邀请等待处理。请刷新核对并完成或取消原邀请，再准备新邀请；已有 AI 连接保留。";
            case "stale_invitation" -> "这次邀请已失效或状态已更新。请刷新核对当前邀请，不要继续重试旧邀请；已有 AI 连接保留。";
            case "device_upgrade_required" -> "手机尚未完成多 AI 连接。请保持 Airtap 联网，稍后刷新；持续未连接时查看手机连接状态。无需重新配对。";
            case "registration_closed" -> "服务暂停新设备登记；已有身份会保留，请稍后由你重试";
            case "control_closed" -> "服务暂停控制；不会自动恢复手机授权";
            case "ingress_rate_limited" -> "服务入口暂时限流；请稍后由你重试。本机停止立即生效，不等待网络";
            case "capacity_reached" -> "服务设备名额已满；请联系服务提供者或选择自托管";
            case "registration_daily_limit", "ip_registration_daily_limit" -> "今日登记额度已用完；请稍后重试，不要反复更换设备身份";
            case "device_request_daily_limit", "device_command_daily_limit", "device_response_daily_limit" -> "本设备今日服务额度已用完；重新配对不会重置额度";
            case "device_not_found" -> "服务未找到此设备登记；本机身份仍保留，请核对服务地址";
            case "device_unenrolled" -> "服务中的设备已注销；旧会话不会恢复，需要你明确重新登记";
            case "enrollment_conflict" -> "设备登记身份冲突；已保留本机凭据，不会覆盖另一登记";
            case "enrollment_pending" -> "设备登记正在确认；已保留同一身份，稍后由你重试";
            default -> status == 410 ? "邀请、会话或旧版中继已到期/失效；请检查对应期限，旧授权不会恢复"
                    : status == 429 ? "服务限额已触发，请稍后由你重试" : "请求未完成；本机停止仍然有效";
        };
        return "中继返回 HTTP " + status + "：" + detail;
    }
}
