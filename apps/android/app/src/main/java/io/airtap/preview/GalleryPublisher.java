package io.airtap.preview;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;

/** Copies one exact workspace image into MediaStore. Called under FileScopes' IO lock and task fence. */
final class GalleryPublisher {
    static final Set<String> MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    static final String ALBUM = "Pictures/Airtap/";
    private static final Uri COLLECTION = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    private record Row(Uri uri, String name, String mime, long size, boolean pending) {}

    static JSONObject publish(Context c, String owner, FileTransferStore store, String path,
                              String version, String mime, Runnable fence) throws Exception {
        FileTransferStore.id(owner); FileTransferStore.hash(version); fence.run();
        if (!MIME_TYPES.contains(mime)) fail("media_type_unsupported");
        FileTransferStore.Entry source = store.stat(path, true);
        if (!source.kind().equals("file") || source.size() == 0) fail("file_type_unsupported");
        if (!source.version().equals(version)) fail("file_changed");
        // Inspect decoded headers rather than trusting a filename or caller-supplied MIME type.
        BitmapFactory.Options dimensions = new BitmapFactory.Options(); dimensions.inJustDecodeBounds = true;
        try (InputStream input = sourceStream(store, path, source, fence)) { BitmapFactory.decodeStream(input, null, dimensions); }
        if (!mime.equals(dimensions.outMimeType) || dimensions.outWidth < 1 || dimensions.outHeight < 1
                || dimensions.outWidth > 32768 || dimensions.outHeight > 32768) fail("media_type_mismatch");
        String key = FileTransferStore.digest((owner + "\n" + path + "\n" + version + "\n" + mime).getBytes(StandardCharsets.UTF_8));
        String name = "Airtap-" + key.substring(0, 32) + switch (mime) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; default -> ".webp"; };
        // Internal receipts contain only a derived key and phase, not original paths, bytes, or grants.
        SharedPreferences receipts = c.getSharedPreferences("gallery-publications", 0);
        String phase = receipts.getString(key, "");
        if (!Set.of("", "writing", "ready", "published").contains(phase)) fail("media_publication_unavailable");
        ContentResolver resolver = c.getContentResolver();
        Row row = find(c, name, fence);
        if (row == null && (phase.equals("ready") || phase.equals("published"))) fail("media_publication_removed");
        if (row != null && !row.pending()) {
            verify(resolver, row, source, mime, fence);
            save(receipts, key, "published");
            return result(source, key, row, dimensions, true);
        }
        if (phase.equals("published")) fail("media_publication_changed");
        save(receipts, key, "writing"); fence.run();
        try {
            if (row == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
                Uri created = resolver.insert(COLLECTION, values);
                if (created == null) fail("media_publication_failed");
                row = new Row(created, name, mime, 0, true);
            }
            // A crashed pending write is resumed into the SAME owned row, never a second photo.
            fence.run();
            try (OutputStream output = resolver.openOutputStream(row.uri(), "wt");
                 InputStream input = sourceStream(store, path, source, fence)) {
                if (output == null) fail("media_publication_failed");
                byte[] buffer = new byte[FileTransferStore.MAX_CHUNK]; int n;
                while ((n = input.read(buffer)) != -1) { fence.run(); output.write(buffer, 0, n); }
            }
            Row written = find(c, name, fence);
            if (written == null || !written.uri().equals(row.uri()) || !written.pending()) fail("media_publication_changed");
            verify(resolver, written, source, mime, fence);
            save(receipts, key, "ready"); fence.run();
            ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(row.uri(), done, null, null) != 1) fail("media_publication_failed");
            save(receipts, key, "published"); fence.run();
            Row published = find(c, name, fence);
            if (published == null || published.pending() || !published.uri().equals(row.uri())) fail("media_publication_changed");
            return result(source, key, published, dimensions, false);
        } catch (Exception error) {
            // Compensate only an unpublished row created by this operation. A completed user photo survives.
            try {
                Row remaining = find(c, name, () -> {});
                if (remaining != null && remaining.pending() && resolver.delete(remaining.uri(), null, null) == 1) save(receipts, key, "writing");
            } catch (Exception ignored) { /* Pending row remains invisible; same-key retry inspects it. */ }
            throw error;
        }
    }

    private static void save(SharedPreferences p, String key, String phase) {
        if (!p.edit().putString(key, phase).commit()) fail("file_storage_failure");
    }
    private static void fail(String code) { throw new FileTransferStore.Failure(code); }
    private static Row find(Context c, String name, Runnable fence) {
        fence.run();
        String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME};
        Bundle query = new Bundle();
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?");
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{name, ALBUM});
        query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = c.getContentResolver().query(COLLECTION, columns, query, null)) {
            if (cursor == null) { fail("media_publication_unavailable"); return null; }
            if (!cursor.moveToFirst()) return null;
            if (!c.getPackageName().equals(cursor.getString(5))) fail("media_publication_changed");
            Row result = new Row(ContentUris.withAppendedId(COLLECTION, cursor.getLong(0)), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(3), cursor.getInt(4) != 0);
            if (cursor.moveToNext()) fail("media_publication_conflict");
            return result;
        }
    }
    private static void verify(ContentResolver resolver, Row row, FileTransferStore.Entry file, String mime, Runnable fence) throws IOException {
        // Providers may leave SIZE at zero until IS_PENDING is cleared; actual byte count is always checked below.
        if (!row.mime().equals(mime) || !row.pending() && row.size() != file.size()) fail("media_publication_changed");
        try (InputStream input = resolver.openInputStream(row.uri())) {
            if (input == null || !FileTransferStore.digest(input, fence, file.size()).equals(file.sha256())) fail("media_publication_changed");
        }
        fence.run();
    }
    private static JSONObject result(FileTransferStore.Entry file, String key, Row row, BitmapFactory.Options size, boolean reused) throws Exception {
        return RelayApi.json("published", true, "publication_id", key, "reused", reused, "collection", "images",
                "album", ALBUM, "display_name", row.name(), "mime_type", row.mime(), "size", file.size(),
                "sha256", file.sha256(), "width", size.outWidth, "height", size.outHeight,
                "readback_verified", true, "picker_selection_verified", false);
    }
    private static InputStream sourceStream(FileTransferStore store, String path, FileTransferStore.Entry file, Runnable fence) {
        return new InputStream() {
            long offset; byte[] chunk = new byte[0]; int index;
            @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255; }
            @Override public int read(byte[] bytes, int start, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(start, length, bytes.length);
                if (length == 0) return 0; fence.run();
                if (index == chunk.length) {
                    if (offset == file.size()) return -1;
                    chunk = store.read(path, offset, (int)Math.min(FileTransferStore.MAX_CHUNK, file.size() - offset), file.version()).data();
                    offset += chunk.length; index = 0;
                    if (chunk.length == 0) throw new EOFException();
                }
                int count = Math.min(length, chunk.length - index);
                System.arraycopy(chunk, index, bytes, start, count); index += count; return count;
            }
        };
    }
}
