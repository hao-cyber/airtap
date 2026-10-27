package io.airtap.preview;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit per-pair file scopes. SAF grants and storage paths never cross the wire. */
final class FileScopes {
    static final Set<String> ACTIONS = Set.of("file_share", "file_roots", "file_list", "file_stat", "file_read", "file_upload_begin", "file_upload_status", "file_upload_chunk", "file_upload_finish", "file_upload_abort");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
        Thread t = new Thread(r, "airtap-files"); t.setDaemon(true); return t;
    });
    private static final java.util.concurrent.locks.ReentrantLock WORKSPACE_IO = new java.util.concurrent.locks.ReentrantLock();
    private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS};
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("file-scopes", 0); }
    static long epoch(Context c) { return prefs(c).getLong("epoch",0); }
    static boolean pickerActive(Context c) { return prefs(c).getBoolean("picker", false); }
    static void picker(Context c, boolean active) {
        if (!prefs(c).edit().putBoolean("picker", active).commit()) throw new FileTransferStore.Failure("file_storage_failure");
    }
    static String owner(Context c) throws Exception {
        EnrollmentStore.Config config = new EnrollmentStore(c).read();
        JSONObject grant = new TrustedGrant(c).read(config);
        return grant == null ? null : grant.getString("claim");
    }
    static String chosenName(Context c, String owner) {
        SharedPreferences p = prefs(c); return owner != null && owner.equals(p.getString("owner", "")) ? p.getString("name", "已选文件夹") : null;
    }
    static void revoke(Context c) {
        WorkspaceShares.revokeAll(c);
        SharedPreferences p = prefs(c); String previous = p.getString("uri", ""); long epoch = p.getLong("epoch", 0) + 1;
        if (!p.edit().clear().putLong("epoch", epoch).commit()) throw new FileTransferStore.Failure("file_storage_failure");
        if (!previous.isEmpty()) try { c.getContentResolver().releasePersistableUriPermission(Uri.parse(previous), permissionFlags(c, Uri.parse(previous))); }
        catch (SecurityException ignored) { /* Already revoked by the system. */ }
    }
    static void grant(Context c, String owner, Uri uri, int flags) throws Exception {
        FileTransferStore.id(owner);
        if (uri == null || !DocumentsContract.isTreeUri(uri) || !"content".equals(uri.getScheme())
                || (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 || !owner.equals(owner(c))) throw new FileTransferStore.Failure("file_scope_unavailable");
        // Never retain authority selected under an earlier pairing or a different folder.
        revoke(c); int retainedFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        c.getContentResolver().takePersistableUriPermission(uri, retainedFlags);
        try {
        // The owner callback must not block on a cloud DocumentsProvider. Query names only on the file IO executor.
        String name = "系统选定的文件夹";
        SharedPreferences p = prefs(c);
        if (!p.edit().putString("uri", uri.toString()).putString("root", "saf_" + UUID.randomUUID().toString().replace("-", ""))
                .putString("owner", owner).putString("name", name).putLong("epoch", p.getLong("epoch", 0) + 1).commit()) {
            c.getContentResolver().releasePersistableUriPermission(uri, retainedFlags);
            throw new FileTransferStore.Failure("file_storage_failure");
        }
        } catch (Exception failure) {
            try { c.getContentResolver().releasePersistableUriPermission(uri, retainedFlags); } catch (SecurityException ignored) { }
            throw failure;
        }
    }
    private static void exact(JSONObject q, String... names) {
        Set<String> fields = new HashSet<>(Arrays.asList(names)); fields.add("id"); fields.add("action");
        if (q.length() != fields.size()) FileTransferStore.fail("invalid_request");
        for (String k : fields) if (!q.has(k)) FileTransferStore.fail("invalid_request");
    }
    private static String text(JSONObject q, String key) throws Exception {
        Object v = q.get(key); if (!(v instanceof String)) FileTransferStore.fail("invalid_request"); return (String)v;
    }
    private static long integer(JSONObject q, String key, long min, long max) throws Exception {
        Object v = q.get(key);
        if (!(v instanceof Integer || v instanceof Long) || ((Number)v).longValue() < min || ((Number)v).longValue() > max) FileTransferStore.fail("invalid_request");
        return ((Number)v).longValue();
    }
    static void validate(JSONObject q) throws Exception {
        String a = text(q, "action");
        switch (a) {
            case "file_share":
                exact(q,"root","path","version","mime_type","target_package");
                if(!"workspace".equals(text(q,"root")))FileTransferStore.fail("file_scope_unavailable");
                FileTransferStore.hash(text(q,"version"));
                if(text(q,"mime_type").length()>127||!text(q,"mime_type").matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"))FileTransferStore.fail("invalid_request");
                String target=text(q,"target_package");if(!target.isEmpty()&&!AppCatalog.allowed(target))FileTransferStore.fail("protected_package");
                break;
            case "file_roots": exact(q); break;
            case "file_list": exact(q, "root", "path", "offset", "limit"); integer(q, "offset", 0, FileTransferStore.MAX_ENTRIES); integer(q, "limit", 1, 64); break;
            case "file_stat": exact(q, "root", "path"); break;
            case "file_read": exact(q, "root", "path", "offset", "length", "version"); integer(q, "offset", 0, FileTransferStore.MAX_FILE); integer(q, "length", 1, FileTransferStore.MAX_CHUNK); FileTransferStore.hash(text(q, "version")); break;
            case "file_upload_begin": exact(q, "root", "path", "transfer_id", "size", "sha256"); integer(q, "size", 0, FileTransferStore.MAX_FILE); FileTransferStore.hash(text(q, "sha256")); break;
            case "file_upload_chunk": {
                exact(q, "transfer_id", "offset", "data_base64", "sha256"); integer(q, "offset", 0, FileTransferStore.MAX_FILE); FileTransferStore.hash(text(q, "sha256"));
                String data = text(q, "data_base64"); if (data.length() < 4 || data.length() > 5464 || !data.matches("[A-Za-z0-9+/]+={0,2}")) FileTransferStore.fail("invalid_request");
                byte[] decoded = Base64.getDecoder().decode(data);
                if (decoded.length < 1 || decoded.length > FileTransferStore.MAX_CHUNK || !Base64.getEncoder().encodeToString(decoded).equals(data)) FileTransferStore.fail("invalid_request");
                break;
            }
            case "file_upload_status": case "file_upload_finish": case "file_upload_abort": exact(q, "transfer_id"); break;
            default: FileTransferStore.fail("unsupported_action");
        }
        if (q.has("root") && !text(q, "root").matches("(?:workspace|saf_[a-f0-9]{32})")) FileTransferStore.fail("invalid_request");
        if (q.has("path")) FileTransferStore.validPath(text(q, "path"), a.equals("file_list") || a.equals("file_stat"));
        if (q.has("transfer_id")) FileTransferStore.id(text(q, "transfer_id"));
    }
    static void onMain(Runnable check) {
        if (Looper.myLooper() == Looper.getMainLooper()) { check.run(); return; }
        FutureTask<Void> task = new FutureTask<>(() -> { check.run(); return null; }); MAIN.post(task);
        try { task.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException) throw (RuntimeException)failure.getCause();
            FileTransferStore.fail("permission_denied");
        } catch (Exception failure) { task.cancel(false); FileTransferStore.fail("permission_denied"); }
    }
    static void execute(Context context, JSONObject q, Runnable checkOnMain, CompanionUi.Reply reply) throws Exception {
        validate(q); checkOnMain.run(); String owner = owner(context);
        if (owner == null) throw new ScopedUi.Refused("permission_denied");
        Context c = context.getApplicationContext(); long epoch = prefs(c).getLong("epoch", 0);
        AtomicBoolean done = new AtomicBoolean(); CancellationSignal signal = new CancellationSignal();
        Runnable fence = () -> onMain(() -> {
            if (done.get()) throw new ScopedUi.Refused("permission_denied");
            checkOnMain.run();
            if (prefs(c).getLong("epoch", 0) != epoch) throw new ScopedUi.Refused("file_scope_changed");
        });
        Runnable timeout = () -> { if (done.compareAndSet(false, true)) { signal.cancel(); reply.error("file_provider_timeout"); } };
        MAIN.postDelayed(timeout, 12000);
        try {
            IO.execute(() -> {
                JSONObject result = null; String error = null;
                boolean locked = false;
                try {
                    if (!(locked = WORKSPACE_IO.tryLock())) throw new FileTransferStore.Failure("file_busy");
                    result = run(c, owner, q, fence, signal); fence.run();
                }
                catch (FileTransferStore.Failure f) { error = f.code; }
                catch (ScopedUi.Refused f) { error = f.code; }
                catch (SecurityException f) { error = "file_scope_revoked"; }
                catch (FileNotFoundException f) { error = "file_not_found"; }
                catch (Exception f) { error = "file_storage_or_provider_failure"; }
                finally { if (locked) WORKSPACE_IO.unlock(); }
                JSONObject finalResult = result; String finalError = error;
                MAIN.post(() -> { MAIN.removeCallbacks(timeout); if (done.compareAndSet(false, true)) {
                    if (prefs(c).getLong("epoch", 0) != epoch) reply.error("file_scope_changed");
                    else if (finalError == null) reply.success(finalResult); else reply.error(finalError);
                } });
            });
        } catch (RejectedExecutionException busy) { MAIN.removeCallbacks(timeout); done.set(true); reply.error("file_busy"); }
    }
    static String recoveryHint(String code) {
        return switch(code) {
            case "file_private_storage_budget" -> "Run file_roots to inspect the all-pairing private storage budget. The owner can open Airtap > 设置 > 文件访问 > 清理工作文件 to remove current and old private workspace files after saving anything needed. Use 清理文件… > 回收过期和旧连接暂存 to reclaim expired/former-pairing SAF payloads, while preserving external document identities and current unexpired uploads. Small identity journals remain. Re-pairing does not free space; do not create duplicate transfers.";
            case "file_storage_full" -> "The device lacks free space for existing reservations and this upload. Keep the transfer ID, free space using normal owner-authorized steps, then inspect file_upload_status before resuming. Do not assume an uncertain transfer failed or start duplicates.";
            default -> null;
        };
    }
    interface OwnerStorageReply { void complete(PrivateFileStorage.Usage usage, String error); }
    static void ownerStorage(Context context, boolean clear, OwnerStorageReply reply) {
        Context c = context.getApplicationContext();
        if (clear) {
            WorkspaceShares.revokeAll(c);
            SharedPreferences p = prefs(c);
            // Invalidate in-flight file commands on main before scheduling deletion. Never wait for IO here.
            if (!p.edit().putLong("epoch", p.getLong("epoch", 0) + 1).commit()) { reply.complete(null, "file_storage_failure"); return; }
        }
        try { IO.execute(() -> {
            boolean locked=false; PrivateFileStorage.Usage usage=null; String error=null;
            try {
                if (!(locked=WORKSPACE_IO.tryLock())) throw new FileTransferStore.Failure("file_busy");
                PrivateFileStorage storage=new PrivateFileStorage(c.getFilesDir().toPath().resolve("scoped-files"), () -> {}, directorySync());
                usage=clear?storage.clearWorkspaces():storage.ownerUsage();
            } catch (FileTransferStore.Failure failure) { error=failure.code; }
            catch (Exception failure) { error="file_storage_failure"; }
            finally { if(locked)WORKSPACE_IO.unlock(); }
            PrivateFileStorage.Usage result=usage; String problem=error;
            MAIN.post(() -> reply.complete(result,problem));
        }); } catch (RejectedExecutionException busy) { reply.complete(null,"file_busy"); }
    }
    interface SharedReader { FileTransferStore.Chunk read(long offset,int length)throws IOException; }
    static SharedReader sharedReader(Context c,WorkspaceShares.Ticket ticket,Runnable fence)throws IOException {
        SharedFileIo.acquire(WORKSPACE_IO,fence,Looper.myLooper()!=Looper.getMainLooper());
        final FileTransferStore store;
        try{
            fence.run();store=new FileTransferStore(c.getFilesDir().toPath().resolve("scoped-files").resolve(ticket.owner),fence,System::currentTimeMillis,directorySync());
        }finally{WORKSPACE_IO.unlock();}
        return (offset,length)->{
            SharedFileIo.acquire(WORKSPACE_IO,fence,Looper.myLooper()!=Looper.getMainLooper());
            try{fence.run();return store.read(ticket.path,offset,length,ticket.version);}finally{WORKSPACE_IO.unlock();}
        };
    }
    static void ownerReclaimStaging(Context context,OwnerStorageReply reply) {
        Context c=context.getApplicationContext();final String owner;
        try{owner=owner(c);}catch(Exception e){reply.complete(null,"file_storage_failure");return;}
        FileMaintenanceFence maintenance=new FileMaintenanceFence(new FileMaintenanceFence.Identity(owner,epoch(c),PreviewState.epoch),()->{
            try{return new FileMaintenanceFence.Identity(owner(c),epoch(c),PreviewState.epoch);}catch(Exception e){throw new FileTransferStore.Failure("file_scope_changed");}
        });
        Runnable fence=()->onMain(maintenance);
        try{IO.execute(()->{
            boolean locked=false;PrivateFileStorage.Usage result=null;String error=null;
            try{
                if(!(locked=WORKSPACE_IO.tryLock()))throw new FileTransferStore.Failure("file_busy");
                fence.run();result=new PrivateFileStorage(c.getFilesDir().toPath().resolve("scoped-files"),fence,directorySync()).reclaimSaf(owner,System.currentTimeMillis(),true);
            }catch(FileTransferStore.Failure e){error=e.code;}catch(Exception e){error="file_storage_failure";}finally{if(locked)WORKSPACE_IO.unlock();}
            PrivateFileStorage.Usage value=result;String problem=error;MAIN.post(()->reply.complete(value,problem));
        });}catch(RejectedExecutionException e){reply.complete(null,"file_busy");}
    }
    private static JSONObject entry(String root, FileTransferStore.Entry e) throws Exception {
        return RelayApi.json("root", root, "path", e.path(), "name", e.name(), "kind", e.kind(), "size", e.size(),
                "modified_ms", e.modified(), "version", e.version(), "sha256", e.sha256() == null ? JSONObject.NULL : e.sha256());
    }
    private static JSONObject transfer(FileTransferStore.Transfer t) throws Exception {
        JSONObject result = RelayApi.json("transfer_id", t.id(), "root", "workspace", "path", t.path(), "state", t.state(),
                "offset", t.offset(), "size", t.size(), "sha256", t.hash(), "expires_at", t.expires(), "publication_atomic", true);
        if (t.file() != null) result.put("file", entry("workspace", t.file())); return result;
    }
    private static JSONObject run(Context c, String owner, JSONObject q, Runnable fence, CancellationSignal signal) throws Exception {
        fence.run(); String a = q.getString("action"), root = q.optString("root", "workspace");
        java.nio.file.Path privateRoot = c.getFilesDir().toPath().resolve("scoped-files");
        java.nio.file.Path base = privateRoot.resolve(owner);
        PrivateFileStorage storage = new PrivateFileStorage(privateRoot, fence, directorySync());
        storage.reclaimSaf(owner,System.currentTimeMillis(),false);
        FileTransferStore store = root.equals("workspace") || a.startsWith("file_upload_") ? new FileTransferStore(base, fence, System::currentTimeMillis, directorySync()) : null;
        if (a.equals("file_roots")) {
            JSONArray roots = new JSONArray().put(RelayApi.json("root", "workspace", "name", "Airtap 工作目录", "kind", "workspace", "readable", true,
                    "writable", true, "publication_atomic", true, "operations", new JSONArray(List.of("list", "stat", "read", "upload", "share"))));
            SharedPreferences p = prefs(c);
            if (owner.equals(p.getString("owner", ""))) {
                boolean allowed = hasPermission(c, Uri.parse(p.getString("uri", "")));
                boolean writable = allowed && (permissionFlags(c, Uri.parse(p.getString("uri", ""))) & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
                if (writable) {
                    Saf selected = new Saf(c, owner, p.getString("root", ""), fence, signal);
                    writable = (selected.resolve("").flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
                }
                roots.put(RelayApi.json("root", p.getString("root", ""), "name", p.getString("name", "已选文件夹"), "kind", "saf", "readable", allowed,
                        "writable", writable, "publication_atomic", false, "operations", new JSONArray(writable ? List.of("list", "stat", "read", "upload") : allowed ? List.of("list", "stat", "read") : List.of()),
                        "status", allowed ? "ready" : "permission_revoked"));
            }
            PrivateFileStorage.Usage usage = storage.usage(true);
            return RelayApi.json("roots", roots, "limits", RelayApi.json("private_storage_budget_bytes", PrivateFileStorage.BUDGET,
                    "private_storage_used_bytes", usage.usedBytes(), "private_storage_reserved_bytes", usage.reservedBytes(), "private_storage_budget_scope", "all_pairings",
                    "free_space_reserve_bytes", PrivateFileStorage.FREE_RESERVE, "max_file_bytes", FileTransferStore.MAX_FILE,
                    "max_chunk_bytes", FileTransferStore.MAX_CHUNK, "max_list_entries", 64, "max_directory_entries", FileTransferStore.MAX_ENTRIES,
                    "max_uploads", FileTransferStore.MAX_UPLOADS, "max_transfer_records_per_storage", FileTransferStore.MAX_RECORDS, "upload_ttl_ms", FileTransferStore.TTL));
        }
        if (a.startsWith("file_upload_")) {
            String id = q.getString("transfer_id");
            SafPublicationStore publications = new SafPublicationStore(base.resolve("saf"), fence, directorySync());
            if (a.equals("file_upload_begin") && !store.known(id) && !publications.known(id)
                    && store.activeCount() + publications.activeCount() >= FileTransferStore.MAX_UPLOADS) throw new FileTransferStore.Failure("file_transfer_limit");
            if (a.equals("file_upload_begin") && !store.known(id) && !publications.known(id))
                storage.checkNew(q.getLong("size"), c.getFilesDir().getUsableSpace());
            else if (a.equals("file_upload_chunk")) storage.check(0, c.getFilesDir().getUsableSpace());
            boolean saf = a.equals("file_upload_begin") ? !root.equals("workspace") : publications.known(id);
            if (saf) {
                if (store.known(id)) throw new FileTransferStore.Failure("file_transfer_conflict");
                SafPublicationStore.Provider provider = provider(c, owner, fence, signal);
                SafPublicationStore.Result result = switch (a) {
                    case "file_upload_begin" -> publications.begin(root, q.getString("path"), id, q.getLong("size"), q.getString("sha256"), provider);
                    case "file_upload_status" -> publications.status(id);
                    case "file_upload_chunk" -> publications.chunk(id, q.getLong("offset"), Base64.getDecoder().decode(q.getString("data_base64")), q.getString("sha256"));
                    case "file_upload_finish" -> publications.finish(id, provider);
                    case "file_upload_abort" -> publications.abort(id, provider);
                    default -> throw new FileTransferStore.Failure("unsupported_action");
                };
                return publication(result);
            }
            if (publications.known(id)) throw new FileTransferStore.Failure("file_transfer_conflict");
            return transfer(switch (a) {
                case "file_upload_begin" -> store.begin(q.getString("path"), id, q.getLong("size"), q.getString("sha256"));
                case "file_upload_status" -> store.status(id);
                case "file_upload_chunk" -> store.chunk(id, q.getLong("offset"), Base64.getDecoder().decode(q.getString("data_base64")), q.getString("sha256"));
                case "file_upload_finish" -> store.finish(id);
                case "file_upload_abort" -> store.abort(id);
                default -> throw new FileTransferStore.Failure("unsupported_action");
            });
        }
        if(a.equals("file_share")) {
            FileTransferStore.Entry file=store.stat(q.getString("path"),false);
            if(!file.kind().equals("file")||file.size()>FileTransferStore.MAX_FILE)throw new FileTransferStore.Failure("file_type_unsupported");
            if(!file.version().equals(q.getString("version")))throw new FileTransferStore.Failure("file_changed");
            JSONObject[] result=new JSONObject[1];
            onMain(()->{try{result[0]=WorkspaceShares.open(c,owner,q.getString("path"),q.getString("mime_type"),q.getString("target_package"),file,fence);}catch(RuntimeException e){throw e;}catch(Exception e){throw new FileTransferStore.Failure("file_share_target_unavailable");}});
            return result[0];
        }
        String path = q.getString("path"); Saf saf = root.equals("workspace") ? null : new Saf(c, owner, root, fence, signal);
        if (a.equals("file_stat")) return entry(root, saf == null ? store.stat(path, true) : saf.stat(path, true));
        if (a.equals("file_list")) {
            List<FileTransferStore.Entry> entries = saf == null ? store.list(path) : saf.list(path);
            int offset = q.getInt("offset"), end = Math.min(entries.size(), offset + q.getInt("limit"));
            if (offset > entries.size()) throw new FileTransferStore.Failure("file_offset_conflict");
            JSONArray items = new JSONArray(); int bytes = 0, used = offset;
            for (int i = offset; i < end; i++) {
                JSONObject item = entry(root, entries.get(i)); int count = item.toString().getBytes(StandardCharsets.UTF_8).length;
                if (bytes + count > 48000) break;
                items.put(item); bytes += count; used++;
            }
            end = used;
            return RelayApi.json("root", root, "path", path, "entries", items, "next_offset", end < entries.size() ? end : JSONObject.NULL, "truncated", end < entries.size());
        }
        long offset = q.getLong("offset"); String expected = q.getString("version");
        FileTransferStore.Chunk chunk = saf == null ? store.read(path, offset, q.getInt("length"), expected) : saf.read(path, offset, q.getInt("length"), expected);
        return RelayApi.json("root", root, "path", path, "offset", offset, "next_offset", chunk.next(), "data_base64", Base64.getEncoder().encodeToString(chunk.data()),
                "sha256", FileTransferStore.digest(chunk.data()), "eof", chunk.next() == chunk.size(), "size", chunk.size(), "version", chunk.version());
    }
    private static FileTransferStore.DirectorySync directorySync() {
        return path -> {
            java.io.FileDescriptor fd = null;
            try {
                fd = android.system.Os.open(path.toString(), android.system.OsConstants.O_RDONLY, 0);
                if (!android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode)) throw new IOException("not_directory");
                android.system.Os.fsync(fd);
            } catch (android.system.ErrnoException error) { throw new IOException("directory_sync_failed"); }
            finally { if (fd != null) try { android.system.Os.close(fd); } catch (android.system.ErrnoException ignored) { } }
        };
    }
    private static JSONObject publication(SafPublicationStore.Result t) throws Exception {
        JSONObject result = RelayApi.json("transfer_id", t.id(), "root", t.root(), "path", t.path(), "state", t.state(),
                "offset", t.offset(), "size", t.size(), "sha256", t.hash(), "expires_at", t.expires(), "publication_atomic", false);
        if (t.state().equals("publishing")||t.state().equals("expired")) result.put("cleanup_required", t.cleanupRequired());
        if (t.file() != null) result.put("file", entry(t.root(), t.file()));
        if (t.state().equals("commit_unknown")) result.put("next_step", "Creation may have reached the selected folder. Check that folder before starting another upload; this transfer will not create or delete a guessed document.");
        else if(t.state().equals("expired")) result.put("next_step","Private staging expired or was reclaimed. This transfer cannot resume or recreate a provider document. Its recorded identity is retained; inspect the folder and, if cleanup_required, abort may remove only its known created document under the current folder permission.");
        else if (t.cleanupRequired()) result.put("next_step", "The provider changed the requested filename. Use file_upload_abort with this transfer_id to remove only the recorded document, then choose another name.");
        return result;
    }
    private static SafPublicationStore.Provider provider(Context c, String owner, Runnable fence, CancellationSignal signal) {
        return new SafPublicationStore.Provider() {
            private Saf saf(String root) { return new Saf(c, owner, root, fence, signal); }
            public String prepare(String root, String path) throws Exception { return saf(root).prepare(path); }
            public void checkDestination(String root, String path, String parent) throws Exception {
                if (!saf(root).prepare(path).equals(parent)) throw new FileTransferStore.Failure("file_changed");
            }
            public String create(String root, String path, String parent) throws Exception {
                Saf s = saf(root); if (!s.prepare(path).equals(parent)) throw new FileTransferStore.Failure("file_changed");
                String name = path.substring(path.lastIndexOf('/') + 1); fence.run();
                String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(name);
                String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.ROOT));
                Uri created = DocumentsContract.createDocument(c.getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(s.tree, parent),
                        mime == null ? "application/octet-stream" : mime, name);
                if (created == null || !s.tree.getAuthority().equals(created.getAuthority())) throw new FileTransferStore.Failure("file_commit_unknown");
                String reference = DocumentsContract.getDocumentId(created);
                if (reference.length() > 2048) throw new FileTransferStore.Failure("file_commit_unknown");
                return reference;
            }
            public void write(String root, String path, String reference, java.nio.file.Path sealed, long size) throws Exception {
                Saf s = saf(root); s.requireWrite(); Saf.Document doc = s.document(reference);
                if (!doc.name.equals(path.substring(path.lastIndexOf('/') + 1))) throw new FileTransferStore.Failure("file_name_conflict");
                if ((doc.flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) == 0) throw new FileTransferStore.Failure("file_provider_write_unavailable");
                fence.run();
                try (android.content.res.AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(s.uri(doc), "wt", signal)) {
                    if (fd == null) throw new FileTransferStore.Failure("file_provider_unavailable");
                    try (InputStream in = java.nio.file.Files.newInputStream(sealed, java.nio.file.LinkOption.NOFOLLOW_LINKS); OutputStream out = fd.createOutputStream()) {
                        byte[] data = new byte[4096]; int n; long count = 0;
                        while ((n = in.read(data)) != -1) { fence.run(); out.write(data, 0, n); count += n; }
                        fence.run(); out.flush(); if (count != size) throw new FileTransferStore.Failure("file_changed");
                    }
                }
            }
            public FileTransferStore.Entry verify(String root, String path, String reference, long size, String hash) throws Exception {
                Saf s = saf(root); Saf.Document doc = s.resolve(path);
                if (!reference.equals(doc.id)) throw new FileTransferStore.Failure("file_changed");
                FileTransferStore.Entry actual = s.stat(path, true);
                if (actual.size() != size || !hash.equals(actual.sha256())) throw new FileTransferStore.Failure("file_hash_mismatch"); return actual;
            }
            public void deleteCreated(String root, String reference) throws Exception {
                Saf s = saf(root); s.requireWrite(); Saf.Document doc;
                try { doc = s.document(reference); } catch (FileTransferStore.Failure missing) { if (missing.code.equals("file_not_found")) return; throw missing; }
                if ((doc.flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) throw new FileTransferStore.Failure("file_provider_delete_unavailable");
                fence.run(); if (!DocumentsContract.deleteDocument(c.getContentResolver(), s.uri(doc))) throw new FileTransferStore.Failure("file_cleanup_failed");
            }
        };
    }
    private static int permissionFlags(Context c, Uri tree) {
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) if (tree.equals(p.getUri()))
            return (p.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0) | (p.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
        return 0;
    }
    private static boolean hasPermission(Context c, Uri tree) { return (permissionFlags(c, tree) & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0; }
    private static final class Saf {
        final Context c; final Uri tree; final Runnable fence; final CancellationSignal signal;
        record Document(String id, String name, String mime, long size, long modified, long flags) {}
        Saf(Context c, String owner, String root, Runnable fence, CancellationSignal signal) {
            this.c = c; this.fence = fence; this.signal = signal; SharedPreferences p = prefs(c);
            if (!owner.equals(p.getString("owner", "")) || !root.equals(p.getString("root", ""))) throw new FileTransferStore.Failure("file_scope_unavailable");
            tree = Uri.parse(p.getString("uri", "")); if (!hasPermission(c, tree)) throw new FileTransferStore.Failure("file_scope_revoked");
        }
        private void requireWrite() {
            if ((permissionFlags(c, tree) & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new FileTransferStore.Failure("file_scope_read_only");
        }
        private String prepare(String path) throws Exception {
            requireWrite(); int split = path.lastIndexOf('/'); Document parent = resolve(split < 0 ? "" : path.substring(0, split));
            if ((parent.flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) throw new FileTransferStore.Failure("file_provider_write_unavailable");
            String name = path.substring(split + 1);
            for (Document child : children(parent)) if (child.name.equals(name)) throw new FileTransferStore.Failure("file_exists");
            return parent.id;
        }
        private Document from(Cursor cur) {
            return new Document(cur.getString(0), cur.getString(1), cur.getString(2), cur.isNull(3) ? -1 : cur.getLong(3), cur.isNull(4) ? 0 : cur.getLong(4), cur.getLong(5));
        }
        private Uri uri(Document doc) { return DocumentsContract.buildDocumentUriUsingTree(tree, doc.id); }
        private Document document(String id) throws Exception {
            fence.run();
            try (Cursor cur = c.getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, id), COLUMNS, null, null, null, signal)) {
                if (cur == null || !cur.moveToFirst()) throw new FileTransferStore.Failure("file_not_found"); return from(cur);
            }
        }
        private List<Document> children(Document directory) throws Exception {
            if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(directory.mime)) throw new FileTransferStore.Failure("file_not_directory");
            List<Document> output = new ArrayList<>(); fence.run();
            try (Cursor cur = c.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory.id), COLUMNS, null, null, null, signal)) {
                if (cur == null) throw new FileTransferStore.Failure("file_provider_unavailable");
                Set<String> names = new HashSet<>();
                while (cur.moveToNext()) {
                    fence.run(); if (output.size() >= FileTransferStore.MAX_ENTRIES) throw new FileTransferStore.Failure("file_directory_too_large");
                    Document doc = from(cur); FileTransferStore.validPath(doc.name, false);
                    if (doc.name.contains("/") || !names.add(doc.name)) throw new FileTransferStore.Failure("file_ambiguous_name");
                    output.add(doc);
                }
            }
            return output;
        }
        private Document resolve(String path) throws Exception {
            FileTransferStore.validPath(path, true); Document doc = document(DocumentsContract.getTreeDocumentId(tree));
            if (path.isEmpty()) return doc;
            for (String name : path.split("/")) {
                Document found = null; for (Document child : children(doc)) if (child.name.equals(name)) found = child;
                if (found == null) throw new FileTransferStore.Failure("file_not_found"); doc = found;
            }
            return doc;
        }
        private FileTransferStore.Entry metadata(String path, Document d, String hash) {
            boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(d.mime);
            return new FileTransferStore.Entry(path, d.name, dir ? "directory" : "file", dir ? 0 : d.size, d.modified,
                    FileTransferStore.version(d.id, d.size, d.modified), hash);
        }
        private InputStream open(Document d) throws IOException {
            fence.run(); android.content.res.AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(uri(d), "r", signal);
            if (fd == null) throw new FileTransferStore.Failure("file_provider_unavailable");
            try { return fd.createInputStream(); } catch (IOException e) { fd.close(); throw e; }
        }
        private void readable(Document d) {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(d.mime)) throw new FileTransferStore.Failure("file_type_unsupported");
            if (d.size < 0) throw new FileTransferStore.Failure("file_size_unavailable");
            if (d.size > FileTransferStore.MAX_FILE) throw new FileTransferStore.Failure("file_too_large");
        }
        FileTransferStore.Entry stat(String path, boolean hash) throws Exception {
            Document d = resolve(path); String digest = null;
            if (hash && !DocumentsContract.Document.MIME_TYPE_DIR.equals(d.mime)) {
                readable(d); try (InputStream in = open(d)) { digest = FileTransferStore.digest(in, fence, d.size); }
                if (!metadata(path, d, null).version().equals(metadata(path, resolve(path), null).version())) throw new FileTransferStore.Failure("file_changed");
            }
            fence.run(); return metadata(path, d, digest);
        }
        List<FileTransferStore.Entry> list(String path) throws Exception {
            List<FileTransferStore.Entry> entries = new ArrayList<>();
            for (Document d : children(resolve(path))) entries.add(metadata(path.isEmpty() ? d.name : path + "/" + d.name, d, null));
            entries.sort(Comparator.comparing(FileTransferStore.Entry::name)); return entries;
        }
        FileTransferStore.Chunk read(String path, long offset, int length, String expected) throws Exception {
            Document d = resolve(path); readable(d); if (offset > d.size) throw new FileTransferStore.Failure("invalid_request");
            if (!metadata(path, d, null).version().equals(expected)) throw new FileTransferStore.Failure("file_changed");
            byte[] bytes = new byte[(int)Math.min(length, d.size - offset)];
            try (InputStream in = open(d)) {
                long skipped = 0; byte[] discard = new byte[65536];
                while (skipped < offset) { fence.run(); int n = in.read(discard, 0, (int)Math.min(discard.length, offset - skipped)); if (n < 0) throw new FileTransferStore.Failure("file_changed"); skipped += n; }
                int total = 0; while (total < bytes.length) { fence.run(); int n = in.read(bytes, total, bytes.length - total); if (n < 0) throw new FileTransferStore.Failure("file_changed"); total += n; }
            }
            if (!metadata(path, resolve(path), null).version().equals(expected)) throw new FileTransferStore.Failure("file_changed");
            fence.run(); return new FileTransferStore.Chunk(bytes, offset + bytes.length, d.size, expected);
        }
    }
}
