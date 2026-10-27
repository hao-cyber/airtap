package io.airtap.preview;

/** Current detected settings, deliberately separate from completed remote-task evidence. */
final class RemoteReadiness {
    enum Network { AVAILABLE, UNCONFIRMED, OFFLINE, UNKNOWN }
    record Snapshot(Network network, boolean operation, boolean notifications,
                    Boolean unrestrictedBattery, Boolean secure, Boolean authenticationRequired) {
        String description() {
            String networkText = switch (network) {
                case AVAILABLE -> "网络：系统已确认可联网";
                case UNCONFIRMED -> "网络：已连接，互联网可用性尚未确认";
                case OFFLINE -> "网络：当前没有连接，请连接 Wi-Fi 或移动网络";
                case UNKNOWN -> "网络：暂时无法读取，请检查手机是否联网";
            };
            String battery = unrestrictedBattery == null ? "后台运行：无法读取，请查看系统设置"
                    : unrestrictedBattery ? "后台运行：系统电池优化已豁免" : "后台运行：仍受系统电池优化，请查看后台运行设置";
            String lock = authenticationRequired == null || secure == null ? "系统验证：暂时无法读取锁屏状态"
                    : authenticationRequired ? "系统验证：当前需要本人完成解锁验证"
                    : secure ? "系统验证：当前无需验证；后续锁屏仍可能要求密码或生物识别"
                    : "系统验证：未设置锁屏密码，可尝试自动唤醒";
            return networkText + "\n\n手机操作：" + (operation ? "已开启" : "未开启，请开启 Airtap 手机操作")
                    + "\n\n连接通知：" + (notifications ? "已允许" : "未允许，请打开 Airtap 通知")
                    + "\n\n" + battery + "\n\n" + lock
                    + "\n\n以上是当前设置检查，不代表息屏、断网恢复或无人值守任务已经通过。厂商的后台限制也可能另有开关。"
                    + "\n\n保持设备供电。准备好后，把任务告诉自己的 AI，让它检查连接、尝试息屏后操作并核实结果；无需重做已经开启的设置。";
        }
    }
}
