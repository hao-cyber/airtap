package io.airtap.preview;

import android.content.*;
import android.net.Uri;
import android.os.Handler;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Production scope methods run against host preference and shared URI permission fixtures. */
public final class MultiFileScopesTest {
    static final String A = "a".repeat(32), B = "b".repeat(32), C = "c".repeat(32), D = "d".repeat(32);
    static final Uri SHARED = Uri.parse("content://synthetic.documents/tree/shared");
    static final Uri OTHER = Uri.parse("content://synthetic.documents/tree/other");
    static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    static final int BOTH = READ | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    static int passed;

    interface Check { void run() throws Exception; }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static void test(String name, Check run) throws Exception {
        TrustedGrant.valid = Set.of(A, B, C, D); WorkspaceShares.revoked.clear();
        HostIo.reset(); run.run();
        check(FileScopes.quiescent() && inFlight() == 0 && Handler.pending() == 0 && ControlledExecutor.queued.isEmpty());
        passed++; System.out.println("PASS " + name);
    }
    static void refused(String code, Check run) throws Exception {
        try { run.run(); throw new AssertionError("accepted"); }
        catch (FileTransferStore.Failure failure) { check(code.equals(failure.code)); }
    }

    static final class Disk {
        final Map<String, Map<String, Object>> stores = new HashMap<>();
    }
    static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, value instanceof Set<?> set ? new HashSet<>(set) : value));
        return result;
    }
    static final class Prefs implements SharedPreferences {
        final Disk disk; final String name; Map<String, Object> memory;
        Predicate<Map<String, Object>> failOnce;
        Prefs(Disk d, String n) { disk = d; name = n; memory = copy(d.stores.getOrDefault(n, Map.of())); }
        public String getString(String key, String fallback) { return (String) memory.getOrDefault(key, fallback); }
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> fallback) { return new HashSet<>((Set<String>) memory.getOrDefault(key, fallback)); }
        public long getLong(String key, long fallback) { return (Long) memory.getOrDefault(key, fallback); }
        Map<String, Object> snapshot() { return copy(memory); }
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> changed = new HashMap<>(); final Set<String> removed = new HashSet<>();
                public Editor putString(String key, String value) { changed.put(key, value); removed.remove(key); return this; }
                public Editor putStringSet(String key, Set<String> value) { changed.put(key, new HashSet<>(value)); removed.remove(key); return this; }
                public Editor putLong(String key, long value) { changed.put(key, value); removed.remove(key); return this; }
                public Editor remove(String key) { changed.remove(key); removed.add(key); return this; }
                public boolean commit() {
                    Map<String, Object> next = copy(memory); removed.forEach(next::remove); next.putAll(copy(changed));
                    // Android commits update process memory before learning whether the disk write succeeded.
                    memory = next;
                    if (failOnce != null && failOnce.test(changed)) { failOnce = null; return false; }
                    disk.stores.put(name, copy(next)); return true;
                }
            };
        }
    }
    static final class Resolver extends ContentResolver {
        // A URI has one persisted permission shared by all scopes; it is not reference-counted per owner.
        final Map<Uri, Integer> held = new HashMap<>();
        final List<Uri> takes = new ArrayList<>(), releases = new ArrayList<>();
        boolean failTake;
        public void takePersistableUriPermission(Uri uri, int flags) {
            if (failTake) throw new SecurityException("synthetic take refusal");
            takes.add(uri); held.merge(uri, flags, (old, add) -> old | add);
        }
        public void releasePersistableUriPermission(Uri uri, int flags) {
            releases.add(uri); Integer before = held.get(uri);
            if (before == null) throw new SecurityException("synthetic permission already lost");
            int after = before & ~flags; if (after == 0) held.remove(uri); else held.put(uri, after);
        }
        public List<UriPermission> getPersistedUriPermissions() {
            List<UriPermission> result = new ArrayList<>(); held.forEach((uri, flags) -> result.add(new UriPermission(uri, flags))); return result;
        }
    }
    static final class Host extends Context {
        final Disk disk; final Resolver resolver; final Map<String, Prefs> stores = new HashMap<>();
        Host() { this(new Disk(), new Resolver()); }
        Host(Disk disk, Resolver resolver) { this.disk = disk; this.resolver = resolver; }
        public Prefs getSharedPreferences(String name, int mode) { return stores.computeIfAbsent(name, key -> new Prefs(disk, key)); }
        public Resolver getContentResolver() { return resolver; }
        Host restart() { return new Host(disk, resolver); }
        Prefs global() { return getSharedPreferences("file-scopes", 0); }
        Prefs scoped(String owner) { return getSharedPreferences("file-scope-" + owner, 0); }
    }
    static boolean active(Host host, String owner) { return FileScopes.chosenName(host, owner) != null; }
    static Set<String> registered(Host host) { return host.global().getStringSet("owners", Set.of()); }
    static void legacy(Host host, String owner, Uri uri) {
        check(host.global().edit().putString("owner", owner).putString("uri", uri.toString())
                .putString("root", "synthetic-legacy-root").putString("name", "legacy folder").putLong("epoch", 7).commit());
        host.resolver.takePersistableUriPermission(uri, BOTH);
    }
    static int inFlight() throws Exception {
        var field = FileScopes.class.getDeclaredField("IN_FLIGHT"); field.setAccessible(true); return ((AtomicInteger) field.get(null)).get();
    }
    static final class Reply implements CompanionUi.Reply {
        final List<String> errors = new ArrayList<>(); final List<JSONObject> results = new ArrayList<>();
        public void success(JSONObject result) { results.add(result); }
        public void error(String code) { errors.add(code); }
    }
    static JSONObject roots() { return new JSONObject().put("id", "synthetic-request").put("action", "file_roots"); }
    static void join(Thread worker) throws Exception { worker.join(2000); check(!worker.isAlive()); }

    public static void main(String[] args) throws Exception {
        test("same SAF URI persists independent A and B scope records across restart", () -> {
            Host host = new Host(); FileScopes.grant(host, B, SHARED, BOTH); FileScopes.grant(host, A, SHARED, BOTH);
            check(registered(host).equals(Set.of(A, B))); check(active(host, A) && active(host, B));
            check(!host.scoped(A).getString("root", "").equals(host.scoped(B).getString("root", "")));
            Host restarted = host.restart(); check(active(restarted, A) && active(restarted, B));
            check(host.resolver.held.get(SHARED) == BOTH && host.resolver.releases.isEmpty());
        });
        test("A owner-index disk failure cannot release B shared URI permission", () -> {
            Host host = new Host(); FileScopes.grant(host, B, SHARED, BOTH); Map<String, Object> before = host.scoped(B).snapshot();
            host.global().failOnce = changes -> changes.get("owners") instanceof Set<?> owners && owners.contains(A);
            refused("file_storage_failure", () -> FileScopes.grant(host, A, SHARED, BOTH));
            check(host.resolver.releases.isEmpty() && host.resolver.held.get(SHARED) == BOTH);
            check(host.scoped(B).snapshot().equals(before)); Host restarted = host.restart();
            check(!active(restarted, A) && active(restarted, B) && registered(restarted).equals(Set.of(B)));
        });
        test("A scope-record disk failure preserves B record and shared permission after restart", () -> {
            Host host = new Host(); FileScopes.grant(host, B, SHARED, BOTH); Map<String, Object> before = host.scoped(B).snapshot();
            host.scoped(A).failOnce = changes -> changes.containsKey("uri");
            refused("file_storage_failure", () -> FileScopes.grant(host, A, SHARED, BOTH));
            check(active(host, A)); // Failed disk commit still changed the in-process preference snapshot.
            check(host.resolver.releases.isEmpty() && host.resolver.held.get(SHARED) == BOTH);
            Host restarted = host.restart(); check(!active(restarted, A) && active(restarted, B));
            check(restarted.scoped(B).snapshot().equals(before)); FileScopes.retain(restarted, Set.of(B));
            check(registered(restarted).equals(Set.of(B)) && host.resolver.releases.isEmpty());
        });
        test("A failed grant preserves legacy B same-URI scope without owner index", () -> {
            Host host = new Host(); legacy(host, B, SHARED); Map<String, Object> before = host.global().snapshot();
            host.scoped(A).failOnce = changes -> changes.containsKey("uri");
            refused("file_storage_failure", () -> FileScopes.grant(host, A, SHARED, BOTH));
            Host restarted = host.restart(); check(active(restarted, B) && !active(restarted, A));
            check(restarted.global().getString("root", "").equals(before.get("root")));
            check(host.resolver.releases.isEmpty() && host.resolver.held.get(SHARED) == BOTH);
        });
        test("single revoke A keeps B shared permission record and epoch", () -> {
            Host host = new Host(); FileScopes.grant(host, A, SHARED, BOTH); FileScopes.grant(host, B, SHARED, BOTH);
            Map<String, Object> before = host.scoped(B).snapshot(); long epochA = FileScopes.scopeEpoch(host, A), epochB = FileScopes.scopeEpoch(host, B);
            WorkspaceShares.revoked.clear(); FileScopes.revoke(host, A);
            check(!active(host, A) && active(host, B) && registered(host).equals(Set.of(B)));
            check(FileScopes.scopeEpoch(host, A) == epochA + 1 && FileScopes.scopeEpoch(host, B) == epochB);
            check(host.scoped(B).snapshot().equals(before) && WorkspaceShares.revoked.equals(List.of(A)));
            check(host.resolver.releases.isEmpty() && host.resolver.held.get(SHARED) == BOTH);
            FileScopes.revoke(host, B); check(host.resolver.releases.equals(List.of(SHARED)) && host.resolver.held.isEmpty());
        });
        test("A folder replacement changes only A scope fence while B captured fence remains current", () -> {
            Host host = new Host(); FileScopes.grant(host, A, SHARED, BOTH); FileScopes.grant(host, B, SHARED, BOTH);
            long global = FileScopes.epoch(host), a = FileScopes.scopeEpoch(host, A), b = FileScopes.scopeEpoch(host, B);
            Map<String, Object> before = host.scoped(B).snapshot(); FileScopes.grant(host, A, OTHER, READ);
            check(FileScopes.scopeEpoch(host, A) != a && FileScopes.scopeEpoch(host, B) == b && FileScopes.epoch(host) == global);
            check(host.scoped(B).snapshot().equals(before)); check(host.resolver.held.get(SHARED) == BOTH && host.resolver.held.get(OTHER) == READ);
            Host restarted = host.restart(); check(FileScopes.scopeEpoch(restarted, B) == b && FileScopes.epoch(restarted) == global);
        });
        test("retaining two valid claims removes only invalid scopes and unshared URI authority", () -> {
            Host host = new Host(); FileScopes.grant(host, A, SHARED, BOTH); FileScopes.grant(host, B, SHARED, BOTH);
            FileScopes.grant(host, C, SHARED, BOTH); FileScopes.grant(host, D, OTHER, READ);
            Map<String, Object> a = host.scoped(A).snapshot(), b = host.scoped(B).snapshot(); WorkspaceShares.revoked.clear();
            FileScopes.retain(host, Set.of(A, B));
            check(registered(host).equals(Set.of(A, B)) && active(host, A) && active(host, B) && !active(host, C) && !active(host, D));
            check(host.scoped(A).snapshot().equals(a) && host.scoped(B).snapshot().equals(b));
            check(new HashSet<>(WorkspaceShares.revoked).equals(Set.of(C, D)));
            check(host.resolver.releases.equals(List.of(OTHER)) && host.resolver.held.get(SHARED) == BOTH);
            int calls = WorkspaceShares.revoked.size(); FileScopes.retain(host, Set.of(A, B)); check(WorkspaceShares.revoked.size() == calls);
        });
        test("retaining legacy B drops unrelated A without dropping legacy shared permission", () -> {
            Host host = new Host(); legacy(host, B, SHARED); FileScopes.grant(host, A, SHARED, BOTH);
            long b = FileScopes.scopeEpoch(host, B), global = FileScopes.epoch(host); FileScopes.retain(host, Set.of(B));
            check(!active(host, A) && active(host, B) && FileScopes.scopeEpoch(host, B) == b && FileScopes.epoch(host) == global);
            check(host.resolver.releases.isEmpty() && host.resolver.held.get(SHARED) == BOTH);
        });
        test("retaining no valid claims releases shared permission only after final owner", () -> {
            Host host = new Host(); FileScopes.grant(host, A, SHARED, BOTH); FileScopes.grant(host, B, SHARED, BOTH);
            FileScopes.retain(host, Set.of()); check(!active(host, A) && !active(host, B) && registered(host).isEmpty());
            check(host.resolver.releases.equals(List.of(SHARED)) && host.resolver.held.isEmpty());
        });
        test("full revoke invalidates all scopes while releasing each shared URI once", () -> {
            Host host = new Host(); FileScopes.grant(host, A, SHARED, BOTH); FileScopes.grant(host, B, SHARED, BOTH); FileScopes.grant(host, C, OTHER, READ);
            WorkspaceShares.revoked.clear(); FileScopes.revoke(host); check(registered(host).isEmpty() && host.resolver.held.isEmpty());
            check(Collections.frequency(host.resolver.releases, SHARED) == 1 && Collections.frequency(host.resolver.releases, OTHER) == 1);
            check(WorkspaceShares.revoked.get(0).equals("all") && new HashSet<>(WorkspaceShares.revoked).equals(Set.of("all", A, B, C)));
        });
        test("invalid claim and invalid tree requests leave existing scopes and permissions unchanged", () -> {
            Host host = new Host(); FileScopes.grant(host, B, SHARED, BOTH); Map<String, Object> before = host.scoped(B).snapshot(); TrustedGrant.valid = Set.of(B);
            refused("file_scope_unavailable", () -> FileScopes.grant(host, A, SHARED, BOTH));
            refused("file_scope_unavailable", () -> FileScopes.grant(host, B, Uri.parse("content://synthetic.documents/document/id"), BOTH));
            refused("file_scope_unavailable", () -> FileScopes.grant(host, B, OTHER, Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            check(host.scoped(B).snapshot().equals(before) && host.resolver.releases.isEmpty() && host.resolver.takes.size() == 1);
        });
        test("failed A revoke disk commit reports failure and never releases B shared permission", () -> {
            Host host = new Host(); FileScopes.grant(host, A, SHARED, BOTH); FileScopes.grant(host, B, SHARED, BOTH);
            host.scoped(A).failOnce = changes -> true;
            refused("file_storage_failure", () -> FileScopes.revoke(host, A));
            check(host.resolver.releases.isEmpty() && host.resolver.held.get(SHARED) == BOTH && active(host, B));
            Host restarted = host.restart(); check(active(restarted, A) && active(restarted, B));
            FileScopes.revoke(restarted, A); check(active(restarted, B) && host.resolver.releases.isEmpty());
        });
        test("already lost synthetic permission does not prevent local stale-scope cleanup", () -> {
            Host host = new Host(); FileScopes.grant(host, A, OTHER, READ); host.resolver.held.clear();
            FileScopes.revoke(host, A); check(!active(host, A) && registered(host).isEmpty());
            check(host.resolver.releases.equals(List.of(OTHER)));
        });
        test("12000ms request timeout keeps real in-flight count until blocked provider returns", () -> {
            Host host = new Host(); Reply reply = new Reply(); HostIo.block = true;
            FileScopes.execute(host, A, roots(), () -> true, () -> {}, reply);
            check(inFlight() == 1 && !FileScopes.quiescent() && Handler.lastDelay == 12000);
            Thread worker = ControlledExecutor.startNext();
            try {
                check(HostIo.entered.await(2, TimeUnit.SECONDS)); Handler.fireTimeouts();
                check(reply.errors.equals(List.of("file_provider_timeout")) && reply.results.isEmpty());
                check(HostIo.signal.isCanceled() && inFlight() == 1 && !FileScopes.quiescent());
            } finally { HostIo.release.countDown(); join(worker); }
            check(inFlight() == 0 && FileScopes.quiescent() && reply.errors.size() == 1 && reply.results.isEmpty());
        });
        test("queued old command loses authority before handoff and production fence blocks provider effect", () -> {
            Host host = new Host(); Reply reply = new Reply(); AtomicBoolean authority = new AtomicBoolean(true);
            Runnable checkAuthority = () -> { if (!authority.get()) throw new ScopedUi.Refused("permission_denied"); };
            FileScopes.execute(host, A, roots(), authority::get, checkAuthority, reply);
            check(inFlight() == 1 && HostIo.effects == 0); authority.set(false);
            join(ControlledExecutor.startNext());
            check(HostIo.effects == 0 && reply.errors.equals(List.of("permission_denied")) && reply.results.isEmpty());
        });
        test("A epoch change rejects queued A while queued B retains its own captured scope fence", () -> {
            Host host = new Host(); FileScopes.grant(host, A, SHARED, BOTH); FileScopes.grant(host, B, SHARED, BOTH);
            Reply a = new Reply(), b = new Reply();
            FileScopes.execute(host, A, roots(), () -> true, () -> {}, a);
            FileScopes.execute(host, B, roots(), () -> true, () -> {}, b);
            FileScopes.revoke(host, A); check(inFlight() == 2); join(ControlledExecutor.startNext());
            check(inFlight() == 1 && HostIo.effects == 0 && a.errors.equals(List.of("file_scope_changed")));
            join(ControlledExecutor.startNext()); check(HostIo.effects == 1 && b.results.size() == 1 && b.errors.isEmpty());
            check(B.equals(b.results.get(0).get("owner")));
        });
        test("timeout before executor starts blocks late provider effect and settles count only after execution", () -> {
            Host host = new Host(); Reply reply = new Reply();
            FileScopes.execute(host, A, roots(), () -> true, () -> {}, reply); Handler.fireTimeouts();
            check(inFlight() == 1 && !FileScopes.quiescent() && reply.errors.equals(List.of("file_provider_timeout")));
            join(ControlledExecutor.startNext()); check(HostIo.effects == 0 && reply.errors.size() == 1 && reply.results.isEmpty());
        });
        test("executor rejection removes timer and decrements real in-flight count exactly once", () -> {
            Host host = new Host(); Reply reply = new Reply(); ControlledExecutor.reject = true;
            try { FileScopes.execute(host, A, roots(), () -> true, () -> {}, reply); }
            finally { ControlledExecutor.reject = false; }
            check(reply.errors.equals(List.of("file_busy")) && reply.results.isEmpty() && HostIo.effects == 0);
            Handler.fireTimeouts(); check(reply.errors.size() == 1);
        });
        test("quiescent also refuses a held real workspace lock when in-flight count is zero", () -> {
            var field = FileScopes.class.getDeclaredField("WORKSPACE_IO"); field.setAccessible(true);
            var lock = (java.util.concurrent.locks.ReentrantLock) field.get(null); lock.lock();
            try { check(inFlight() == 0 && !FileScopes.quiescent()); } finally { lock.unlock(); }
        });
        System.out.println("TOTAL " + passed);
    }
}
