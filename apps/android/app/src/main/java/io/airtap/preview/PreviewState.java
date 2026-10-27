package io.airtap.preview;

/** Process-only pairing/approval state. Enrollment token alone cannot resume a grant. */
final class PreviewState {
    static EnrollmentStore.Config config;
    static String ticket;
    static long pairingExpiry;
    static String claim;
    static String candidate;
    static String verification;
    static long approvedExpiry;
    static boolean persistent;
    static boolean allowTap;
    static boolean allowControl;
    static java.util.List<String> allowedPackages = java.util.Collections.emptyList();
    static boolean grantAvailable;
    static int epoch;
    static String message = "先登记手机，再为个人 AI 创建一次性配对邀请。";

    static void clearPairing() {
        persistent = false;
        ticket = null;
        pairingExpiry = 0;
        claim = null;
        candidate = null;
        verification = null;
        approvedExpiry = 0;
        allowTap = false;
        allowControl = false;
        allowedPackages = java.util.Collections.emptyList();
        grantAvailable = false;
    }

    static void stop(String message) {
        epoch++;
        grantAvailable = false;
        PreviewService service = PreviewService.instance;
        if (service != null) service.stop(message);
        PreviewState.message = message;
    }
}
