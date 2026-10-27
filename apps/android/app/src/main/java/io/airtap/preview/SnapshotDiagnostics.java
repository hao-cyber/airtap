package io.airtap.preview;

import java.util.Locale;

/** Enum-only formatting boundary: never accepts application, node or exception strings. */
final class SnapshotDiagnostics {
    enum Stage {
        EVENT, READ_SCOPE, READ_FIRST_SCAN, READ_SECOND_SCAN, READ_FINAL_SCOPE, READ_PUBLISH,
        ACT_SCOPE, ACT_CURRENT, ACT_FIRST_SCAN, ACT_SECOND_SCAN, ACT_DIRTY_VERIFY,
        ACT_TARGET, ACT_ACTION_CHECK, ACT_FINAL_SCOPE, ACT_FINAL_CHECK, ACT_DISPATCH
    }
    static String format(Stage stage, SnapshotGuard.Reason reason, SnapshotGuard.Reason previous, long elapsed) {
        return "snapshot elapsed_ms=" + elapsed + " stage=" + stage.name().toLowerCase(Locale.ROOT)
                + " reason=" + reason.name().toLowerCase(Locale.ROOT)
                + " last_invalidation=" + previous.name().toLowerCase(Locale.ROOT);
    }
    private SnapshotDiagnostics() { }
}
