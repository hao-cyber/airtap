package io.airtap.preview;

/** Owner-facing state only. No credentials, transport diagnostics or side effects. */
enum RecoveryStatus {
    WAITING_FOR_AI(false), VERIFYING_CONNECTION(false), NETWORK_RECONNECTING(false), SECURE_CONNECTION_REQUIRED(true),
    NOTIFICATIONS_REQUIRED(true), NOTIFICATION_START_RETRY(false), CONNECTED(false),
    SCREEN_PREPARABLE(false), SYSTEM_AUTH_REQUIRED(true), SCREEN_UNAVAILABLE(false);

    final boolean needsOwnerAction;
    RecoveryStatus(boolean needsOwnerAction) { this.needsOwnerAction = needsOwnerAction; }
}
