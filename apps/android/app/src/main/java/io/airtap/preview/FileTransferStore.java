package io.airtap.preview;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Private per-pair workspace. No Android APIs; tests exercise the real filesystem and crash journal. */
final class FileTransferStore {
    static final int MAX_FILE = 8 * 1024 * 1024, MAX_CHUNK = 4096, MAX_ENTRIES = 4096;
    static final int MAX_UPLOADS = 4, MAX_RECORDS = 128;
    static final long TTL = 24 * 60 * 60 * 1000L;
    record Entry(String path, String name, String kind, long size, long modified, String version, String sha256) {}
    record Transfer(String id, String path, String state, long offset, long size, String hash, long expires, Entry file) {}
    record Chunk(byte[] data, long next, long size, String version) {}
    static final class Failure extends RuntimeException {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    private final Path workspace, transfers;
    private final Runnable fence;
    private final java.util.function.LongSupplier clock;
    interface DirectorySync { void sync(Path path) throws IOException; }
    private final DirectorySync directorySync;
    FileTransferStore(Path base, Runnable fence) throws IOException { this(base, fence, System::currentTimeMillis); }
    FileTransferStore(Path base, Runnable fence, java.util.function.LongSupplier clock) throws IOException {
        this(base, fence, clock, path -> { try (var dir = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ)) { dir.force(true); } });
    }
    FileTransferStore(Path base, Runnable fence, java.util.function.LongSupplier clock, DirectorySync directorySync) throws IOException {
        this.fence = fence; this.clock = clock; this.directorySync = directorySync; fence.run();
        privateDirectory(base.getParent()); privateDirectory(base); workspace = base.resolve("workspace"); transfers = base.resolve("transfers");
        privateDirectory(workspace); privateDirectory(transfers); directorySync.sync(base.getParent()); directorySync.sync(base); cleanup();
    }
    private static void privateDirectory(Path p) throws IOException {
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) fail("file_scope_unavailable");
        } else Files.createDirectories(p);
    }
    static void fail(String code) { throw new Failure(code); }
    static void validPath(String path, boolean allowRoot) {
        if (path == null || path.getBytes(StandardCharsets.UTF_8).length > 1024 || (!allowRoot && path.isEmpty())) fail("invalid_request");
        if (path.isEmpty()) return;
        String[] parts = path.split("/", -1);
        if (parts.length > 32) fail("invalid_request");
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..") || p.codePointCount(0, p.length()) > 255) fail("invalid_request");
            p.codePoints().forEach(c -> { if (c == '\\' || c == ':' || Character.isISOControl(c) || (c >= 0xd800 && c <= 0xdfff)) fail("invalid_request"); });
        }
    }
    static void id(String id) { if (id == null || !id.matches("[a-f0-9]{32}")) fail("invalid_request"); }
    static void hash(String h) { if (h == null || !h.matches("[a-f0-9]{64}")) fail("invalid_request"); }
    private Path path(String relative, boolean root) throws IOException {
        validPath(relative, root); Path p = workspace;
        for (String segment : relative.isEmpty() ? new String[0] : relative.split("/")) {
            p = p.resolve(segment);
            if (Files.isSymbolicLink(p)) fail("file_symlink_refused");
        }
        if (!p.normalize().startsWith(workspace)) fail("invalid_request");
        return p;
    }
    private static String hex(byte[] value) { return java.util.HexFormat.of().formatHex(value); }
    static String digest(byte[] value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static String version(String identity, long size, long modified) {
        return digest((identity + "\n" + size + "\n" + modified).getBytes(StandardCharsets.UTF_8));
    }
    static String digest(InputStream stream, Runnable fence) throws IOException { return digest(stream, fence, -1); }
    static String digest(InputStream stream, Runnable fence, long expected) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] data = new byte[65536]; int n; long size = 0;
            while ((n = stream.read(data)) != -1) { fence.run(); size += n; if (size > MAX_FILE) fail("file_too_large"); hash.update(data, 0, n); }
            fence.run(); if (expected >= 0 && expected != size) fail("file_changed"); return hex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private BasicFileAttributes attrs(Path p) throws IOException {
        fence.run(); if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) fail("file_not_found");
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() && !a.isDirectory()) fail("file_type_unsupported"); return a;
    }
    Entry stat(String relative, boolean withHash) throws IOException {
        Path p = path(relative, true); BasicFileAttributes a = attrs(p);
        String v = version(String.valueOf(a.fileKey()), a.size(), a.lastModifiedTime().toMillis()); String h = null;
        if (withHash && a.isRegularFile()) {
            if (a.size() > MAX_FILE) fail("file_too_large");
            try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) { h = digest(in, fence, a.size()); }
            BasicFileAttributes after = attrs(p);
            if (!v.equals(version(String.valueOf(after.fileKey()), after.size(), after.lastModifiedTime().toMillis()))) fail("file_changed");
        }
        return new Entry(relative, relative.isEmpty() ? "Airtap 工作目录" : p.getFileName().toString(), a.isDirectory() ? "directory" : "file",
                a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toMillis(), v, h);
    }
    List<Entry> list(String relative) throws IOException {
        Path p = path(relative, true); if (!attrs(p).isDirectory()) fail("file_not_directory");
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(p)) {
            for (Path child : children) {
                fence.run(); if (entries.size() == MAX_ENTRIES) fail("file_directory_too_large");
                String name = child.getFileName().toString(); validPath(name, false);
                entries.add(stat(relative.isEmpty() ? name : relative + "/" + name, false));
            }
        }
        entries.sort(Comparator.comparing(Entry::name)); return entries;
    }
    Chunk read(String relative, long offset, int length, String expected) throws IOException {
        Path p = path(relative, false); Entry before = stat(relative, false);
        if (!before.kind.equals("file")) fail("file_type_unsupported");
        if (!before.version.equals(expected)) fail("file_changed");
        if (before.size > MAX_FILE) fail("file_too_large");
        if (offset < 0 || offset > before.size || length < 1 || length > MAX_CHUNK) fail("invalid_request");
        byte[] data = new byte[(int)Math.min(length, before.size - offset)]; fence.run();
        try (var in = Files.newByteChannel(p, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            in.position(offset); java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(data);
            while (b.hasRemaining()) { fence.run(); if (in.read(b) < 0) fail("file_changed"); }
        }
        if (!stat(relative, false).version.equals(expected)) fail("file_changed"); fence.run();
        return new Chunk(data, offset + data.length, before.size, expected);
    }
    private Path meta(String id) { id(id); return transfers.resolve(id + ".properties"); }
    private Path part(String id) { id(id); return transfers.resolve(id + ".part"); }
    private Properties load(String id) throws IOException {
        Path m = meta(id); if (!Files.isRegularFile(m, LinkOption.NOFOLLOW_LINKS)) fail("file_transfer_not_found");
        if (Files.size(m) > 16384) fail("file_storage_failure");
        Properties p = new Properties(); try (InputStream in = Files.newInputStream(m, LinkOption.NOFOLLOW_LINKS)) { p.load(in); }
        if (Long.parseLong(p.getProperty("expires")) <= clock.getAsLong()) { remove(id); fail("file_transfer_not_found"); }
        validPath(p.getProperty("path"), false); hash(p.getProperty("hash")); return p;
    }
    private void save(String id, Properties p) throws IOException {
        Path temp = transfers.resolve(id + ".pending");
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) { p.store(out, null); out.getFD().sync(); }
        Files.move(temp, meta(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        directorySync.sync(transfers);
    }
    private void remove(String id) throws IOException {
        Files.deleteIfExists(part(id)); Files.deleteIfExists(meta(id)); Files.deleteIfExists(transfers.resolve(id + ".pending"));
    }
    private void cleanup() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(transfers)) {
            for (Path p : files) {
                fence.run(); String n = p.getFileName().toString();
                if (n.matches("[a-f0-9]{32}\\.properties")) {
                    try { load(n.substring(0, 32)); } catch (Failure f) { if (!f.code.equals("file_transfer_not_found")) throw f; }
                } else if (n.matches("[a-f0-9]{32}\\.(part|pending)") && !Files.exists(meta(n.substring(0, 32)), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(p);
            }
        }
    }
    Transfer begin(String relative, String id, long size, String hash) throws IOException {
        validPath(relative, false); id(id); hash(hash);
        if (size < 0 || size > MAX_FILE) fail("invalid_request");
        if (Files.exists(meta(id), LinkOption.NOFOLLOW_LINKS)) {
            Properties old = load(id);
            if (!relative.equals(old.getProperty("path")) || size != Long.parseLong(old.getProperty("size")) || !hash.equals(old.getProperty("hash"))) fail("file_transfer_conflict");
            return status(id);
        }
        Path target = path(relative, false);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) fail("file_exists");
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) fail("file_parent_not_found");
        int active = 0, records = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(transfers, "*.properties")) {
            for (Path p : files) { Properties record = load(p.getFileName().toString().substring(0, 32)); records++;
                if (Set.of("receiving", "committing").contains(record.getProperty("state"))) active++; }
        }
        if (active >= MAX_UPLOADS || records >= MAX_RECORDS) fail("file_transfer_limit");
        // Android disallows the NIO getFileStore mount lookup. File queries the owned path directly.
        if (workspace.toFile().getUsableSpace() < size + 1048576L) fail("file_storage_full");
        Properties p = new Properties(); p.setProperty("path", relative); p.setProperty("size", Long.toString(size));
        p.setProperty("hash", hash); p.setProperty("state", "receiving"); p.setProperty("expires", Long.toString(clock.getAsLong() + TTL));
        fence.run(); Files.createFile(part(id)); directorySync.sync(transfers); save(id, p); return status(id);
    }
    Transfer status(String id) throws IOException {
        fence.run(); Properties p = load(id);
        if ("committing".equals(p.getProperty("state"))) recoverCommit(id, p);
        if ("receiving".equals(p.getProperty("state")) && !Files.exists(part(id), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(path(p.getProperty("path"), false), LinkOption.NOFOLLOW_LINKS)) fail("file_commit_unknown");
            fence.run(); Files.createFile(part(id)); directorySync.sync(transfers);
        }
        String state = p.getProperty("state"); long size = Long.parseLong(p.getProperty("size")); Entry file = null;
        long offset = "finished".equals(state) ? size : (Files.isRegularFile(part(id), LinkOption.NOFOLLOW_LINKS) ? Files.size(part(id)) : 0);
        if ("finished".equals(state)) file = new Entry(p.getProperty("path"), path(p.getProperty("path"), false).getFileName().toString(), "file", size,
                Long.parseLong(p.getProperty("modified")), p.getProperty("version"), p.getProperty("hash"));
        return new Transfer(id, p.getProperty("path"), state, offset, size, p.getProperty("hash"), Long.parseLong(p.getProperty("expires")), file);
    }
    Transfer chunk(String id, long offset, byte[] data, String hash) throws IOException {
        hash(hash); if (data.length < 1 || data.length > MAX_CHUNK || offset < 0 || offset > MAX_FILE) fail("invalid_request");
        if (!digest(data).equals(hash)) fail("file_hash_mismatch");
        Properties p = load(id); if (!"receiving".equals(p.getProperty("state"))) fail("file_transfer_not_receiving");
        long size = Long.parseLong(p.getProperty("size")); if (offset + data.length > size) fail("file_offset_conflict");
        if (Files.exists(path(p.getProperty("path"), false), LinkOption.NOFOLLOW_LINKS)) fail("file_exists");
        Path partial = part(id); if (Files.isSymbolicLink(partial)) fail("file_storage_failure");
        fence.run();
        // A crashed write may leave a prefix of a chunk. Validate that prefix before appending only the missing tail.
        try (RandomAccessFile out = new RandomAccessFile(partial.toFile(), "rw")) {
            long current = out.length(); if (offset > current) fail("file_offset_conflict");
            int overlap = (int)Math.min(data.length, current - offset); out.seek(offset);
            for (int i = 0; i < overlap; i++) if (out.read() != (data[i] & 255)) fail("file_offset_conflict");
            fence.run(); if (overlap < data.length) { out.seek(offset + overlap); out.write(data, overlap, data.length - overlap); out.getFD().sync(); }
        }
        return status(id);
    }
    private void recoverCommit(String id, Properties p) throws IOException {
        Path target = path(p.getProperty("path"), false), partial = part(id);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { p.setProperty("state", "receiving"); save(id, p); return; }
        // A rename consumes the staging name. Recover only the inode persisted before publication.
        Object targetKey = attrs(target).fileKey();
        boolean sameStaging = Files.exists(partial, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(target, partial);
        if (!sameStaging && (targetKey == null || !targetKey.toString().equals(p.getProperty("commit_identity")))) fail("file_commit_unknown");
        Entry result = stat(p.getProperty("path"), true);
        if (result.size != Long.parseLong(p.getProperty("size")) || !p.getProperty("hash").equals(result.sha256)) fail("file_commit_unknown");
        p.setProperty("state", "finished"); p.setProperty("modified", Long.toString(result.modified)); p.setProperty("version", result.version);
        directorySync.sync(target.getParent()); save(id, p); Files.deleteIfExists(partial); directorySync.sync(transfers);
    }
    Transfer finish(String id) throws IOException {
        Transfer current = status(id); if (current.state.equals("finished")) return current;
        if (!current.state.equals("receiving")) fail("file_transfer_not_receiving");
        if (current.offset != current.size) fail("file_incomplete");
        String actual; try (InputStream in = Files.newInputStream(part(id), LinkOption.NOFOLLOW_LINKS)) { actual = digest(in, fence); }
        if (!actual.equals(current.hash)) fail("file_hash_mismatch");
        Path target = path(current.path, false); if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) fail("file_exists");
        Object identity = attrs(part(id)).fileKey();
        if (identity == null) fail("file_atomic_publish_unavailable");
        Properties p = load(id); p.setProperty("state", "committing"); p.setProperty("commit_identity", identity.toString()); save(id, p);
        // Android app sandboxes disallow hard links. This private workspace has no external writers;
        // FileScopes holds its global IO lock across this no-overwrite check and atomic rename.
        fence.run();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { p.setProperty("state", "receiving"); save(id, p); fail("file_exists"); }
        try { Files.move(part(id), target, StandardCopyOption.ATOMIC_MOVE); }
        catch (FileAlreadyExistsException conflict) { p.setProperty("state", "receiving"); save(id, p); fail("file_exists"); }
        catch (AtomicMoveNotSupportedException unsupported) { p.setProperty("state", "receiving"); save(id, p); fail("file_atomic_publish_unavailable"); }
        catch (UnsupportedOperationException unsupported) { p.setProperty("state", "receiving"); save(id, p); fail("file_atomic_publish_unavailable"); }
        recoverCommit(id, p); return status(id);
    }
    int activeCount() throws IOException {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(transfers, "*.properties")) {
            for (Path file : files) if (Set.of("receiving", "committing").contains(load(file.getFileName().toString().substring(0, 32)).getProperty("state"))) count++;
        }
        return count;
    }
    boolean known(String id) { return Files.exists(meta(id), LinkOption.NOFOLLOW_LINKS); }
    Path committedPath(String id) throws IOException {
        Transfer t = status(id);
        if (!t.state.equals("finished")) fail("file_incomplete");
        Entry actual = stat(t.path, true);
        if (actual.size != t.size || !t.hash.equals(actual.sha256)) fail("file_changed");
        return path(t.path, false);
    }
    void discardCommitted(String id) throws IOException {
        Transfer t = status(id);
        if (t.state.equals("finished")) { fence.run(); Files.deleteIfExists(path(t.path, false)); directorySync.sync(workspace); }
    }
    Transfer abort(String id) throws IOException {
        Transfer t = status(id); if (t.state.equals("finished") || t.state.equals("aborted")) return t;
        Properties p = load(id); fence.run(); p.setProperty("state", "aborted"); save(id, p); Files.deleteIfExists(part(id)); return status(id);
    }
}
