package io.airtap.preview;

/** Presentation of observed state. Holding a task is not proof that the task succeeded. */
final class OwnerStatus {
    private OwnerStatus() {}

    static boolean canStartTask(RecoveryStatus status) {
        return status == RecoveryStatus.CONNECTED || status == RecoveryStatus.SCREEN_PREPARABLE;
    }

    static String title(RecoveryStatus status, String activeAi) {
        return switch (status) {
            case CONNECTED -> activeAi == null ? "你的 AI 已连接" : activeAi + " 已接手任务";
            case SCREEN_PREPARABLE -> "已连接，任务到来时唤醒";
            case SYSTEM_AUTH_REQUIRED -> "系统要求手机或 SIM 卡验证";
            case SECURE_CONNECTION_REQUIRED -> "需要检查安全连接";
            case NOTIFICATIONS_REQUIRED -> "需要开启连接通知";
            case STORAGE_UNAVAILABLE -> "暂时无法读取原连接";
            case NOTIFICATION_START_RETRY -> "正在恢复连接通知";
            case NETWORK_RECONNECTING -> "正在恢复连接";
            case SCREEN_UNAVAILABLE -> "已连接，屏幕暂不可用";
            case WAITING_FOR_AI, VERIFYING_CONNECTION -> "正在核实原 AI 连接";
        };
    }

    static String detail(RecoveryStatus status) {
        return switch (status) {
            case CONNECTED -> "回到自己的 AI 对话交代任务。任务是否完成，以 AI 核对的实际结果为准。";
            case SCREEN_PREPARABLE -> "手机正在息屏或显示可退出的锁屏。AI 开始任务时会尝试唤醒，不用一直点亮屏幕。";
            case SYSTEM_AUTH_REQUIRED -> "原连接已保留。让 AI 先核对系统要求；确需你本人验证时，它会说明具体一步。Airtap 不会绕过验证。";
            case SECURE_CONNECTION_REQUIRED -> "原连接已保留。请检查 Airtap 和系统更新；不要关闭证书校验或重新配对。";
            case STORAGE_UNAVAILABLE -> "手机操作已暂停。请检查可用存储后重试，不要删除原连接或清除 App 数据。";
            case NOTIFICATIONS_REQUIRED -> "开启 Airtap 的系统通知后会自动继续，原连接仍在，不用重新配对。";
            case NOTIFICATION_START_RETRY -> "Airtap 正在自动重试。若持续没有恢复，可检查“远程使用准备”中的后台设置。";
            case NETWORK_RECONNECTING -> "Airtap 会自动重连，原 AI 连接保留。保持手机联网即可，不用再发邀请码。";
            case SCREEN_UNAVAILABLE -> "AI 会收到具体状态并尝试恢复。若仍不能使用，可检查“远程使用准备”，不用重新配对。";
            case WAITING_FOR_AI, VERIFYING_CONNECTION -> "连接核实后会自动继续，不需要再次核码。";
        };
    }
}
