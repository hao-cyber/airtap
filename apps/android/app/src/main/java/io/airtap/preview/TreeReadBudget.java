package io.airtap.preview;

/** Public tree byte budget; a popped but omitted final node still makes the tree incomplete. */
final class TreeReadBudget {
    static final int MAX_BYTES = 56000;
    private int bytes;
    private boolean omitted;
    boolean include(int serializedBytes) {
        if (serializedBytes < 0) throw new IllegalArgumentException("negative tree item size");
        if (serializedBytes > MAX_BYTES - bytes) { omitted = true; return false; }
        bytes += serializedBytes;
        return true;
    }
    boolean truncated(boolean nodesPending) { return omitted || nodesPending; }
}
