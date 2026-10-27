package io.airtap.preview;

import org.json.JSONObject;

/** Read-only recovery decisions. An uncertain invitation is never replayed automatically. */
final class SetupRecovery {
    static final class Backoff {
        private int failures;
        long nextDelay() { return failures < 4 ? 1000L << failures++ : -1; }
        void reset() { failures = 0; }
    }
    record Pairing(String state, String claim, String code, String name, long expires) {}
    static boolean transientFailure(int status) { return status == 0 || status == 408 || status == 429 || status >= 500; }
    static Pairing pairing(JSONObject body) {
        ServiceProtocol.version(body);
        Object phase = body.opt("state");
        if (!(phase instanceof String)) throw new IllegalArgumentException();
        String state = (String) phase;
        if (!java.util.Set.of("idle", "waiting", "pending", "approved", "revoked").contains(state)) throw new IllegalArgumentException();
        long expiry = 0;
        if (state.equals("waiting") || state.equals("pending")) {
            expiry = ServiceProtocol.integer(body, "pairing_expires_at");
            if (expiry <= 0) throw new IllegalArgumentException();
        }
        if (state.equals("pending") || state.equals("approved")) {
            JSONObject candidate = body.optJSONObject("candidate");
            if (candidate == null) throw new IllegalArgumentException();
            Object claim = candidate.opt("claim_id"), code = candidate.opt("verification_code"), name = candidate.opt("name");
            if (!(claim instanceof String) || !((String) claim).matches("[a-f0-9]{32}")
                    || !(code instanceof String) || !((String) code).matches("[0-9]{6}")
                    || !(name instanceof String) || ((String) name).isEmpty() || ((String) name).codePointCount(0, ((String) name).length()) > 80
                    || ((String) name).codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            return new Pairing(state, (String) claim, (String) code, (String) name, expiry);
        }
        return new Pairing(state, null, null, null, expiry);
    }
}
