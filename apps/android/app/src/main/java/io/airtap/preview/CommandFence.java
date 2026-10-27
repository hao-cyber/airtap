package io.airtap.preview;

import android.os.Bundle;
import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.BooleanSupplier;

/** Final transport/command check after potentially blocking Android queries. */
final class CommandFence {
    static void requireCurrent(BooleanSupplier current) {
        if (!current.getAsBoolean()) throw new ScopedUi.Refused("permission_denied");
    }
    static boolean node(BooleanSupplier current, AccessibilityNodeInfo node, int action, Bundle arguments) {
        requireCurrent(current);
        return node.performAction(action, arguments);
    }
    static boolean global(BooleanSupplier current, AccessibilityService service, int action) {
        requireCurrent(current);
        return service.performGlobalAction(action);
    }
    static void activity(BooleanSupplier current, AccessibilityService service, Intent intent) {
        requireCurrent(current);
        service.startActivity(intent);
    }
    private CommandFence() {}
}
