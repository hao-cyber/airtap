package io.airtap.preview;

/** Main-thread request ownership. AI grants deliberately do not gate owner report management. */
final class FeedbackRequestGate {
    static final class Ticket {
        final long epoch;
        final String url, token;
        Ticket(long epoch, String url, String token) { this.epoch = epoch; this.url = url; this.token = token; }
    }
    private long epoch;
    private String url, token;
    private boolean visible;

    void resume(String url, String token) {
        pause();
        this.url = url; this.token = token;
        visible = url != null && token != null;
    }
    void pause() { ++epoch; visible = false; url = null; token = null; }
    Ticket begin() {
        if (!visible) throw new IllegalStateException();
        return new Ticket(++epoch, url, token);
    }
    boolean accepts(Ticket request, String currentUrl, String currentToken) {
        return visible && request != null && request.epoch == epoch
                && request.url.equals(url) && request.token.equals(token)
                && request.url.equals(currentUrl) && request.token.equals(currentToken);
    }
}
