package io.airtap.preview;

/** One-shot local picker consent. A resumed owner screen invalidates all late picker results. */
final class FileGrantAttempt {
    private int request = -1;
    private String owner;
    void begin(int request, String owner) { this.request = request; this.owner = owner; }
    String consume(int request) {
        if (request != this.request) return null;
        String value = owner; cancel(); return value;
    }
    boolean matches(int request) { return this.request == request; }
    void cancel() { owner = null; request = -1; }
}
