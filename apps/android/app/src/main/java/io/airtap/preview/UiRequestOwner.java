package io.airtap.preview;

/** Main-thread-only ownership for one Activity request; cancellation is not remote rollback. */
final class UiRequestOwner {
    static final class Token {
        private final int epoch;
        private Runnable cancel;
        private Token(int epoch) { this.epoch = epoch; }
    }

    private Token active;
    private boolean closed;

    Token begin(int epoch) {
        if (closed) throw new IllegalStateException("owner_closed");
        invalidate();
        active = new Token(epoch);
        return active;
    }

    void attach(Token token, Runnable cancel) {
        if (active == token && !closed) token.cancel = cancel;
        else cancel(cancel); // Completion/invalidation can precede attachment.
    }

    boolean finish(Token token, int currentEpoch) {
        if (active != token || closed) return false;
        active = null;
        token.cancel = null;
        return token.epoch == currentEpoch;
    }

    boolean busy(int currentEpoch) {
        if (active != null && active.epoch != currentEpoch) invalidate();
        return active != null;
    }

    boolean closed() { return closed; }

    void invalidate() {
        Token previous = active;
        active = null; // A cancellation callback cannot release a newer owner.
        if (previous != null) {
            Runnable cancel = previous.cancel;
            previous.cancel = null;
            if (cancel != null) cancel(cancel);
        }
    }

    void close() {
        closed = true;
        invalidate();
    }

    private static void cancel(Runnable cancel) {
        try { cancel.run(); }
        catch (RuntimeException ignored) { /* Ownership is already invalid, even if local cancellation fails. */ }
    }
}
