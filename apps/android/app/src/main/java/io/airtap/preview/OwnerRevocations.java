package io.airtap.preview;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONObject;

/** Durable owner revoke intents; never stores a device token or creates a grant. */
final class OwnerRevocations {
    private static final Object LOCK = new Object();
    private static final String ALL = "*";
    private static final long MAX_GENERATION = 9007199254740991L;
    private static Attempt inFlight;
    private static final class Attempt {
        final String identity, target, id;
        final Long expectedGeneration;
        boolean completed;
        Attempt(String identity, String target, String id) {
            this.identity = identity; this.target = target; this.id = id;
            expectedGeneration = generationOf(id);
        }
    }
    private OwnerRevocations() { }
    private static String identity(EnrollmentStore.Config config) throws Exception {
        if (config == null || !config.publicService() || !config.enrolled()) throw new IllegalArgumentException("enrollment_required");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(("airtap-owner-revocations-v1\n" + config.url + "\n" + config.token).getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
    }
    private static String target(String claim) {
        if (claim == null) return ALL;
        if (!claim.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("invalid_claim");
        return claim;
    }
    private static SharedPreferences state(Context context) { return context.getSharedPreferences("owner-revocations", 0); }
    private static void generation(long value) { if (value < 0 || value > MAX_GENERATION) throw new IllegalArgumentException("invalid_generation"); }
    private static Long generationOf(String intent) {
        int separator = intent.indexOf(':');
        return separator < 0 || unknownGeneration(intent) ? null : Long.valueOf(intent.substring(separator + 1));
    }
    private static boolean multiIntent(String intent) { return intent.indexOf(':') >= 0; }
    private static boolean unknownGeneration(String intent) { return intent.endsWith(":?"); }
    private static Map<String, String> read(SharedPreferences state, String identity) {
        String encoded = state.getString(identity, ""); Map<String, String> result = new TreeMap<>();
        if (encoded.isEmpty()) return result;
        boolean v2 = encoded.startsWith("v2|");
        if (encoded.length() > 32768 || (!v2 && !encoded.startsWith("v1|"))) throw new IllegalStateException("revocation_record_invalid");
        for (String entry : encoded.substring(3).split(",", -1)) {
            String[] parts = entry.split(":", -1);
            if ((parts.length != 2 && parts.length != 3) || (!parts[0].equals(ALL) && !parts[0].matches("[a-f0-9]{32}"))
                    || !parts[1].matches("[a-f0-9]{32}")) throw new IllegalStateException("revocation_record_invalid");
            String intent = parts[1];
            if (parts.length == 3) {
                if (!v2 || !ALL.equals(parts[0]) || (!parts[2].equals("?") && !parts[2].matches("0|[1-9][0-9]{0,15}"))) throw new IllegalStateException("revocation_record_invalid");
                if (!parts[2].equals("?")) generation(Long.parseLong(parts[2]));
                intent += ":" + parts[2];
            }
            if (result.put(parts[0], intent) != null) throw new IllegalStateException("revocation_record_invalid");
        }
        if (result.containsKey(ALL) && result.size() != 1) throw new IllegalStateException("revocation_record_invalid");
        return result;
    }
    private static void write(SharedPreferences state, String identity, Map<String, String> pending) {
        String old = state.getString(identity, ""); StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            if (encoded.length() != 0) encoded.append(',');
            encoded.append(entry.getKey()).append(':').append(entry.getValue());
        }
        if (encoded.length() > 32765) throw new IllegalStateException("revocation_record_full");
        SharedPreferences.Editor edit = state.edit();
        if (pending.isEmpty()) edit.remove(identity); else edit.putString(identity, "v2|" + encoded);
        if (!edit.commit()) {
            // A failed commit may already have changed memory. Keep the old durable barrier visible.
            SharedPreferences.Editor restore = state.edit();
            if (old.isEmpty()) restore.remove(identity); else restore.putString(identity, old);
            restore.commit();
            throw new IllegalStateException("revocation_intent_not_persisted");
        }
    }
    static void queue(Context context, EnrollmentStore.Config config, String claim) throws Exception {
        synchronized (LOCK) {
            String key = identity(config), wanted = target(claim); SharedPreferences state = state(context);
            Map<String, String> pending = read(state, key);
            if (wanted.equals(ALL)) {
                String id = pending.get(ALL);
                if (id != null && multiIntent(id)) throw new IllegalStateException("revocation_generation_required");
                pending.clear(); pending.put(ALL, id == null ? EnrollmentStore.token().substring(0, 32) : id);
            } else if (!pending.containsKey(ALL) && !pending.containsKey(wanted)) pending.put(wanted, EnrollmentStore.token().substring(0, 32));
            write(state, key, pending);
        }
    }
    /** Multi all-stop retries must retain the exact remote generation verified by the owner UI. */
    static void queueAll(Context context, EnrollmentStore.Config config, long expectedGeneration) throws Exception {
        synchronized (LOCK) {
            generation(expectedGeneration);
            String key = identity(config); SharedPreferences state = state(context); Map<String, String> pending = read(state, key);
            String id = pending.get(ALL);
            if (id != null && !Long.valueOf(expectedGeneration).equals(generationOf(id))) throw new IllegalStateException("revocation_generation_mismatch");
            pending.clear(); pending.put(ALL, id == null ? EnrollmentStore.token().substring(0, 32) + ":" + expectedGeneration : id);
            write(state, key, pending);
        }
    }
    /** Blocks new authority immediately while offline; flush resolves this same intent's generation once. */
    static void queueAllUnknown(Context context, EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) {
            String key = identity(config); SharedPreferences state = state(context); Map<String, String> pending = read(state, key);
            String id = pending.get(ALL);
            if (id != null && !multiIntent(id)) throw new IllegalStateException("revocation_generation_required");
            pending.clear(); pending.put(ALL, id == null ? EnrollmentStore.token().substring(0, 32) + ":?" : id);
            write(state, key, pending);
        }
    }
    static boolean allPending(Context context, EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) { return read(state(context), identity(config)).containsKey(ALL); }
    }
    static Set<String> claimsPending(Context context, EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) { Set<String> claims = new LinkedHashSet<>(read(state(context), identity(config)).keySet()); claims.remove(ALL); return claims; }
    }
    static void confirmed(Context context, EnrollmentStore.Config config, String claim) throws Exception {
        synchronized (LOCK) {
            String key = identity(config), wanted = target(claim); SharedPreferences state = state(context);
            Map<String, String> pending = read(state, key);
            if (!pending.containsKey(wanted)) return;
            if (wanted.equals(ALL) && multiIntent(pending.get(ALL))) throw new IllegalStateException("generation_confirmation_required");
            if (wanted.equals(ALL)) pending.clear(); else pending.remove(wanted);
            write(state, key, pending);
        }
    }
    private static void confirmed(Context context, Attempt run) {
        SharedPreferences state = state(context); Map<String, String> pending = read(state, run.identity);
        if (!run.id.equals(pending.get(run.target))) return;
        if (run.target.equals(ALL)) pending.clear(); else pending.remove(run.target);
        write(state, run.identity, pending);
    }
    private static void fixGeneration(Context context, Attempt run, long generation) {
        SharedPreferences state = state(context); Map<String, String> pending = read(state, run.identity);
        if (!unknownGeneration(run.id) || !run.id.equals(pending.get(ALL))) return;
        pending.put(ALL, run.id.substring(0, run.id.indexOf(':')) + ":" + generation);
        write(state, run.identity, pending);
    }
    /** Sends at most one request. Completion means the attempt ended, not that it succeeded. */
    static void flush(Context context, EnrollmentStore.Config config, Runnable completed) throws Exception {
        Context app = context.getApplicationContext(); final Attempt run;
        synchronized (LOCK) {
            String key = identity(config);
            EnrollmentStore.Config current = new EnrollmentStore(app).read();
            if (current == null || !key.equals(identity(current))) throw new IllegalStateException("stale_enrollment");
            Map<String, String> pending = read(state(app), key);
            if (pending.isEmpty() || inFlight != null) run = null;
            else {
                Map.Entry<String, String> next = pending.entrySet().iterator().next();
                run = new Attempt(key, next.getKey(), next.getValue());
                inFlight = run;
            }
        }
        if (run == null) { if (completed != null) completed.run(); return; }
        try {
            boolean all = ALL.equals(run.target);
            boolean resolveGeneration = all && unknownGeneration(run.id);
            JSONObject request = all ? (run.expectedGeneration == null ? null : RelayApi.json("expected_generation", run.expectedGeneration)) : RelayApi.json("claim_id", run.target);
            RelayApi.requestStatus(config.url, resolveGeneration ? "/v1/device/grants" : all ? "/v1/device/revoke" : "/v1/device/grants/revoke", resolveGeneration ? "GET" : "POST", config.token,
                    request, (body, status, error) -> {
                synchronized (LOCK) {
                    if (run.completed) return;
                    run.completed = true;
                    try {
                        EnrollmentStore.Config current = new EnrollmentStore(app).read();
                        if (inFlight == run && current != null && run.identity.equals(identity(current)) && error == null && status == 200) {
                            ServiceProtocol.version(body);
                            if (resolveGeneration) {
                                if (!"multi".equals(body.opt("mode"))) throw new IllegalArgumentException("multi_mode_required");
                                long observed = ServiceProtocol.integer(body, "generation"); generation(observed);
                                // Save before any POST. Later flushes never query or substitute a newer generation.
                                fixGeneration(app, run, observed);
                            } else {
                                boolean acknowledged = ServiceProtocol.bool(body, "ok");
                                if (run.expectedGeneration != null) {
                                    long observed = ServiceProtocol.integer(body, "generation"); generation(observed);
                                    acknowledged &= observed > run.expectedGeneration;
                                }
                                // A later generation confirms only this old intent, not that later grants were stopped.
                                if (acknowledged) confirmed(app, run);
                            }
                        }
                    } catch (Exception failure) { /* Unknown outcome or failed durable clearing retains the original intent. */ }
                    finally { if (inFlight == run) inFlight = null; }
                }
                if (completed != null) completed.run();
            });
        } catch (RuntimeException failure) {
            synchronized (LOCK) { run.completed = true; if (inFlight == run) inFlight = null; }
            if (completed != null) completed.run();
            throw failure;
        }
    }
}
