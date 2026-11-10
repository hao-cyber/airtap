package io.airtap.preview;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Destructive recovery decisions require an explicit v4 response, never an HTTP status alone. */
final class RelayFailure {
    private static final String WS = "[\\x20\\t\\r\\n]*";
    private static final String ERROR = "\"error\"" + WS + ":" + WS + "\"([a-z][a-z0-9_]{0,79})\"";
    private static final String VERSION = "\"protocol_version\"" + WS + ":" + WS + "4";
    private static final Pattern ERROR_FIRST = Pattern.compile(WS + "\\{" + WS + ERROR + WS + "," + WS + VERSION + WS + "\\}" + WS);
    private static final Pattern VERSION_FIRST = Pattern.compile(WS + "\\{" + WS + VERSION + WS + "," + WS + ERROR + WS + "\\}" + WS);
    private static final Pattern JSON_TYPE = Pattern.compile("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", Pattern.CASE_INSENSITIVE);

    private RelayFailure() { }

    /** Current service errors have exactly these two fields. Unknown envelopes remain unknown. */
    static JSONObject errorBody(String contentType, String text) {
        if (contentType == null || !JSON_TYPE.matcher(contentType.trim()).matches() || text == null
                || text.getBytes(StandardCharsets.UTF_8).length > 8192) return null;
        Matcher match = ERROR_FIRST.matcher(text);
        if (!match.matches()) { match = VERSION_FIRST.matcher(text); if (!match.matches()) return null; }
        return RelayApi.json("protocol_version", 4, "error", match.group(1));
    }

    private static String code(JSONObject body) {
        try {
            ServiceProtocol.version(body);
            Object value = body.opt("error");
            return value instanceof String ? (String) value : "";
        } catch (RuntimeException invalid) { return ""; }
    }

    /** Fixed terminal errors of device-token endpoints; controller/lease errors are unrelated. */
    static boolean deviceTerminal(int status, JSONObject body) {
        String code = code(body);
        return status == 401 && "unauthorized".equals(code)
                || status == 410 && "device_unenrolled".equals(code);
    }

    static boolean approvalRejected(int status, JSONObject body, boolean multi) {
        if (deviceTerminal(status, body)) return true;
        String code = code(body);
        return status == 400 && "invalid_approval".equals(code)
                || multi && status == 403 && ("delegator_revoked".equals(code) || "permission_denied".equals(code))
                || multi && status == 409 && "approval_delegation_conflict".equals(code)
                || status == 409 && ("stale_claim".equals(code)
                    || (multi ? "approval_scope_conflict" : "claim_not_pending").equals(code));
    }

    static boolean migrationRejected(int status, JSONObject body) {
        String code = code(body);
        return status == 404 && "not_found".equals(code)
                || status == 409 && ("stale_claim".equals(code) || "unsupported_state_schema".equals(code));
    }

    /** False means a well-formed state has removed/replaced/restricted this grant; malformed is unknown. */
    static boolean singleApproved(JSONObject body, String claim) throws Exception {
        ServiceProtocol.version(body);
        if (body.has("mode")) throw new IllegalArgumentException();
        Object state = body.opt("state");
        if (!(state instanceof String)) throw new IllegalArgumentException();
        if (java.util.Set.of("idle", "waiting", "pending", "revoked").contains(state)) return false;
        if (!"approved".equals(state)) throw new IllegalArgumentException();
        boolean persistent = ServiceProtocol.bool(body, "persistent"), all = ServiceProtocol.bool(body, "all_apps"),
                control = ServiceProtocol.bool(body, "allow_control"), tap = ServiceProtocol.bool(body, "allow_tap");
        if (!body.has("session_expires_at")) throw new IllegalArgumentException();
        if (!body.isNull("session_expires_at") && ServiceProtocol.integer(body, "session_expires_at") <= 0) throw new IllegalArgumentException();
        Object candidate = body.getJSONObject("candidate").opt("claim_id");
        if (!(candidate instanceof String) || !((String) candidate).matches("[a-f0-9]{32}")) throw new IllegalArgumentException();
        JSONArray packages = body.getJSONArray("allowed_packages");
        if (packages.length() > 256) throw new IllegalArgumentException();
        for (int i = 0; i < packages.length(); i++) {
            Object value = packages.get(i);
            if (!(value instanceof String) || !((String) value).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException();
        }
        return persistent && all && control && !tap && body.isNull("session_expires_at")
                && packages.length() == 0 && claim.equals(candidate);
    }
}
