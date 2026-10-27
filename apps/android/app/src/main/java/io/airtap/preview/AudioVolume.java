package io.airtap.preview;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit system streams, with native readback. Never selects an active media session. */
final class AudioVolume {
    private static final Map<String, Integer> STREAMS = new LinkedHashMap<>();
    static {
        STREAMS.put("music", AudioManager.STREAM_MUSIC);
        STREAMS.put("alarm", AudioManager.STREAM_ALARM);
        STREAMS.put("ring", AudioManager.STREAM_RING);
        STREAMS.put("notification", AudioManager.STREAM_NOTIFICATION);
        STREAMS.put("system", AudioManager.STREAM_SYSTEM);
        STREAMS.put("voice_call", AudioManager.STREAM_VOICE_CALL);
    }
    private record State(int min, int max, int current, boolean muted) {
        JSONObject json() { return RelayApi.json("min", min, "max", max, "current", current, "muted", muted); }
    }
    static void validate(JSONObject q) throws Exception {
        String action = q.getString("action");
        java.util.Set<String> fields = new java.util.HashSet<>(java.util.List.of("id", "action", "stream"));
        if (action.equals("set_volume")) {
            fields.add("level"); Object raw = q.get("level");
            if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 0 || ((Number) raw).longValue() > Integer.MAX_VALUE)
                throw new ScopedUi.Refused("invalid_volume_level");
        } else if (action.equals("adjust_volume")) {
            fields.add("direction"); direction(q.getString("direction"));
        } else throw new ScopedUi.Refused("invalid_request");
        if (q.length() != fields.size()) throw new ScopedUi.Refused("invalid_request");
        for (String key : fields) if (!q.has(key)) throw new ScopedUi.Refused("invalid_request");
        if (!STREAMS.containsKey(q.getString("stream"))) throw new ScopedUi.Refused("invalid_audio_stream");
    }
    private static int direction(String direction) {
        return switch (direction) {
            case "raise" -> AudioManager.ADJUST_RAISE;
            case "lower" -> AudioManager.ADJUST_LOWER;
            case "mute" -> AudioManager.ADJUST_MUTE;
            case "unmute" -> AudioManager.ADJUST_UNMUTE;
            default -> throw new ScopedUi.Refused("invalid_volume_direction");
        };
    }
    private static AudioManager manager(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) throw new ScopedUi.Refused("audio_unavailable");
        return audio;
    }
    private static boolean permission(Context context) {
        return context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    private static State state(AudioManager audio, int stream) {
        try {
            State state = new State(audio.getStreamMinVolume(stream), audio.getStreamMaxVolume(stream),
                    audio.getStreamVolume(stream), audio.isStreamMute(stream));
            if (state.min < 0 || state.max < state.min || state.current < 0 || state.current > state.max)
                throw new ScopedUi.Refused("audio_state_unavailable");
            return state;
        } catch (SecurityException denied) { throw new ScopedUi.Refused("audio_permission_or_policy_denied"); }
        catch (IllegalArgumentException unavailable) { throw new ScopedUi.Refused("audio_stream_unavailable"); }
        catch (RuntimeException unavailable) { throw new ScopedUi.Refused("audio_state_unavailable"); }
    }
    static JSONObject describe(Context context) throws Exception {
        AudioManager audio = manager(context); boolean fixed = audio.isVolumeFixed(), permitted = permission(context);
        JSONArray streams = new JSONArray();
        for (Map.Entry<String, Integer> entry : STREAMS.entrySet()) {
            JSONObject row = RelayApi.json("stream", entry.getKey());
            try {
                State state = state(audio, entry.getValue());
                row.put("available", true).put("volume", state.json()).put("control", fixed ? "fixed" : !permitted ? "permission_required" : "available_with_platform_policy");
            } catch (ScopedUi.Refused unavailable) { row.put("available", false).put("error", unavailable.code); }
            streams.put(row);
        }
        return RelayApi.json("fixed", fixed, "modify_audio_settings_granted", permitted, "streams", streams,
                "streams_may_be_linked", true, "control_scope", "system_streams", "media_session_control", false);
    }
    static JSONObject perform(Context context, JSONObject q, Runnable guard) throws Exception {
        validate(q); AudioManager audio = manager(context);
        if (!permission(context)) throw new ScopedUi.Refused("audio_permission_required");
        String name = q.getString("stream"), action = q.getString("action"); int stream = STREAMS.get(name);
        State before = state(audio, stream);
        int level = action.equals("set_volume") ? ((Number) q.get("level")).intValue() : -1;
        if (level != -1 && (level < before.min || level > before.max)) throw new ScopedUi.Refused("volume_out_of_range");
        guard.run();
        if (audio.isVolumeFixed()) throw new ScopedUi.Refused("volume_fixed");
        // No SHOW_UI, PLAY_SOUND, ALLOW_RINGER_MODES, route selection or DND policy changes.
        try {
            if (action.equals("set_volume")) audio.setStreamVolume(stream, level, 0);
            else audio.adjustStreamVolume(stream, direction(q.getString("direction")), 0);
        } catch (SecurityException denied) { throw new ScopedUi.Refused("audio_permission_or_policy_denied"); }
        catch (IllegalArgumentException unavailable) { throw new ScopedUi.Refused("audio_stream_unavailable"); }
        catch (RuntimeException unavailable) { throw new ScopedUi.Refused("audio_action_outcome_unknown"); }
        JSONObject result = RelayApi.json("accepted", true, "action", action, "stream", name, "before", before.json(), "requires_observation", false);
        // A successful void API call is not proof that Android applied the requested value.
        try {
            State after = state(audio, stream);
            String change = action.equals("adjust_volume") ? q.getString("direction") : "set";
            boolean matched = switch (change) {
                case "set" -> after.current == level;
                case "mute" -> after.muted;
                case "unmute" -> !after.muted;
                case "raise" -> after.current > before.current || (before.current == before.max && after.current == after.max);
                default -> after.current < before.current || (before.current == before.min && after.current == after.min);
            };
            result.put("after", after.json()).put("changed", !after.equals(before)).put("target_observed", matched)
                    .put("verification", "native_readback").put("audio_state", describe(context));
        } catch (RuntimeException unavailable) {
            result.put("verification", "readback_unavailable").put("retry_action", false);
        }
        return result;
    }
}
