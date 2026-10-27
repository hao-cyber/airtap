package io.airtap.preview;

import org.json.JSONObject;

/** Strict v4 response checks. Enrollment has no expiry and never implies a control grant. */
final class ServiceProtocol {
    static void version(JSONObject body) {
        exactInteger(body, "protocol_version", 4);
    }

    static String discovery(JSONObject body) {
        version(body);
        if (!"public".equals(body.opt("enrollment_mode"))) throw new IllegalArgumentException();
        explicitNull(body, "enrollment_expires_at");
        exactInteger(body, "pairing_ttl_seconds", 300);
        explicitNull(body, "session_ttl_seconds");
        explicitNull(body, "command_budget");
        Object id = body.opt("service_id");
        Object label = body.opt("label");
        if (!(id instanceof String) || !((String) id).matches("[a-f0-9]{32}")
                || !(label instanceof String)) throw new IllegalArgumentException();
        String name = (String) label;
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 80
                || name.codePoints().anyMatch(ServiceProtocol::unsafeCharacter)) throw new IllegalArgumentException();
        bool(body, "registration_open");
        bool(body, "control_open");
        return name;
    }

    static void enrollment(JSONObject body, String localId) {
        version(body);
        if (!localId.equals(body.opt("device_id"))
                || !("/d/" + localId).equals(body.opt("device_base_path"))) throw new IllegalArgumentException();
        explicitNull(body, "enrollment_expires_at");
        bool(body, "created");
    }

    static long invitation(JSONObject body, long now) {
        long expiry = invitationShape(body);
        if (expiry <= now || expiry > now + 330) throw new IllegalArgumentException();
        return expiry;
    }

    static long invitationShape(JSONObject body) {
        version(body); explicitNull(body, "expires_at");
        long expiry = integer(body, "pairing_expires_at"); Object ticket = body.opt("ticket");
        if (!(ticket instanceof String) || !((String) ticket).matches("[a-f0-9]{32}") || expiry <= 0) throw new IllegalArgumentException();
        return expiry;
    }

    static boolean unenrolled(JSONObject body) {
        try {
            version(body);
            return bool(body, "ok") && "unenrolled".equals(body.opt("state"));
        } catch (RuntimeException ignored) { return false; }
    }

    static boolean bool(JSONObject body, String name) {
        Object value = body.opt(name);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException();
        return (Boolean) value;
    }

    static long integer(JSONObject body, String name) {
        Object value = body.opt(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }

    private static void exactInteger(JSONObject body, String name, long expected) {
        if (integer(body, name) != expected) throw new IllegalArgumentException();
    }

    private static void explicitNull(JSONObject body, String name) {
        if (!body.has(name) || !body.isNull(name)) throw new IllegalArgumentException();
    }

    private static boolean unsafeCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                || type == Character.UNASSIGNED || type == Character.PRIVATE_USE;
    }
}
