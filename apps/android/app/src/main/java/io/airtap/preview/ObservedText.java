package io.airtap.preview;

import org.json.JSONObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded display text with explicit original UTF-16 length; never split a surrogate pair. */
final class ObservedText {
    static String clip(CharSequence value, int maximum) {
        if (value == null) return "";
        String raw = value.toString(); int end = Math.min(Math.max(0, maximum), raw.length());
        if (end > 0 && end < raw.length() && Character.isHighSurrogate(raw.charAt(end - 1))
                && Character.isLowSurrogate(raw.charAt(end))) end--;
        return raw.substring(0, end);
    }
    static void put(JSONObject target, String name, CharSequence value, boolean masked) throws Exception {
        if (masked) { target.put(name, ""); return; }
        String raw = value == null ? "" : value.toString(), visible = clip(raw, 200);
        target.put(name, visible).put(name + "_length_utf16", raw.length())
                .put(name + "_truncated", visible.length() < raw.length());
    }
    static final int MAX_CHUNK_UTF16 = 4096;
    /** Offsets are exact UTF-16 boundaries. The caller binds this value to the original snapshot identity. */
    static JSONObject chunk(CharSequence value, int offset, int length) throws Exception {
        String raw = value == null ? "" : value.toString();
        if (offset < 0 || offset > raw.length() || length < 2 || length > MAX_CHUNK_UTF16)
            throw new ScopedUi.Refused("invalid_text_range");
        if (offset > 0 && offset < raw.length() && Character.isHighSurrogate(raw.charAt(offset - 1)) && Character.isLowSurrogate(raw.charAt(offset)))
            throw new ScopedUi.Refused("invalid_text_boundary");
        int end = (int)Math.min(raw.length(), (long)offset + length);
        if (end > offset && end < raw.length() && Character.isHighSurrogate(raw.charAt(end - 1)) && Character.isLowSurrogate(raw.charAt(end))) end--;
        return new JSONObject().put("text", raw.substring(offset, end)).put("offset_utf16", offset).put("length_utf16", end - offset)
                .put("total_length_utf16", raw.length()).put("next_offset_utf16", end < raw.length() ? end : JSONObject.NULL)
                .put("eof", end == raw.length()).put("truncated", end < raw.length()).put("offset_unit", "utf16");
    }
    /** Snapshot-local only: never include this digest in a response, log, or persisted record. */
    static String localFingerprint(CharSequence value, boolean masked) {
        if (masked) return ""; // Do not inspect, measure, or hash sensitive content.
        String raw = value == null ? "" : value.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[4096]; int used = 0;
            // Hash exact UTF-16 code units, including unpaired surrogates; encoders may replace them.
            for (int i = 0; i < raw.length(); i++) {
                char unit = raw.charAt(i); chunk[used++] = (byte)(unit >>> 8); chunk[used++] = (byte)unit;
                if (used == chunk.length) { digest.update(chunk); used = 0; }
            }
            if (used > 0) digest.update(chunk, 0, used);
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest.digest()) {
                result.append(Character.forDigit((item >>> 4) & 15, 16));
                result.append(Character.forDigit(item & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("snapshot_digest_unavailable", unavailable);
        }
    }
    private ObservedText() {}
}
