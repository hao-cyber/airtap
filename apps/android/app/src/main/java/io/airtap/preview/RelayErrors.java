package io.airtap.preview;

/** Never display a server-supplied sentence, URL or credential in an error. */
final class RelayErrors {
    static String message(int status, String code) {
        String detail = switch (code) {
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
