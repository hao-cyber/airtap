package io.airtap.preview;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted device enrollment and separately named, explicitly approved trust records. */
final class EnrollmentStore {
    private static final String ALIAS = "airtap-preview-enrollment-v1";
    private final AtomicFile file;
    private final String purpose;

    static final class Config {
        final String url;
        final String token;
        final long expiresAt;
        final String deviceId;
        final boolean registered;
        Config(String url, String token, long expiresAt) {
            this.url = RelayAddress.requireRoot(url);
            if (!token.matches("[0-9a-f]{64}") || expiresAt < 0) throw new IllegalArgumentException();
            this.token = token;
            this.expiresAt = expiresAt;
            this.deviceId = null;
            this.registered = expiresAt > 0;
        }
        Config(String origin, String deviceId, String token, boolean registered) {
            this.url = RelayAddress.deviceBase(origin, deviceId);
            if (!token.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            this.token = token;
            this.deviceId = deviceId;
            this.registered = registered;
            this.expiresAt = 0; // Public enrollment has no expiry; never a control grant.
        }
        // Enrollment identity survives an operator's explicit renewal of the same relay.
        // Every new pairing still requires a foreground user action and server authorization.
        boolean enrolled() { return registered; }
        boolean publicService() { return deviceId != null; }
        String origin() { return RelayAddress.root(url); }
    }

    EnrollmentStore(Context context) {
        this(context, "enrollment");
    }

    EnrollmentStore(Context context, String purpose) {
        if (!purpose.equals("enrollment") && !purpose.equals("trusted-ai")) throw new IllegalArgumentException();
        this.purpose = purpose;
        file = new AtomicFile(new File(context.getFilesDir(), purpose + ".aesgcm"));
    }

    Config read() throws Exception {
        JSONObject record = readRecord();
        return record == null ? null : decode(record);
    }

    JSONObject readRecord() throws Exception {
        if (!file.getBaseFile().exists()) return null;
        if (file.getBaseFile().length() > 4096) throw new IllegalStateException();
        byte[] data = file.readFully();
        if (data.length < 29 || data.length > 4096 || data[0] != 1) throw new IllegalStateException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = java.util.Arrays.copyOfRange(data, 1, 13);
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD((purpose.equals("enrollment") ? "airtap-preview-enrollment-v1" : "airtap-trusted-ai-v1").getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(data, 13, data.length - 13);
        try {
            return new JSONObject(new String(plain, StandardCharsets.UTF_8));
        } finally { java.util.Arrays.fill(plain, (byte) 0); }
    }

    void save(Config config) throws Exception {
        saveRecord(encode(config));
    }

    void saveRecord(JSONObject json) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD((purpose.equals("enrollment") ? "airtap-preview-enrollment-v1" : "airtap-trusted-ai-v1").getBytes(StandardCharsets.UTF_8));
        byte[] plain = json.toString().getBytes(StandardCharsets.UTF_8);
        byte[] sealed;
        try { sealed = cipher.doFinal(plain); }
        finally { java.util.Arrays.fill(plain, (byte) 0); }
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(1);
            stream.write(cipher.getIV());
            stream.write(sealed);
            file.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) file.failWrite(stream);
            throw failure;
        }
    }

    static Config decode(JSONObject json) throws Exception {
        if (json.has("protocol_version")) {
            if (ServiceProtocol.integer(json, "protocol_version") != 3
                    || !json.has("enrollment_expires_at") || !json.isNull("enrollment_expires_at")) throw new IllegalArgumentException();
            return new Config(json.getString("origin"), json.getString("device_id"),
                    json.getString("device_token"), ServiceProtocol.bool(json, "registered"));
        }
        return new Config(json.getString("url"), json.getString("device_token"), json.getLong("expires_at"));
    }

    static JSONObject encode(Config config) throws Exception {
        return config.publicService()
                ? new JSONObject().put("protocol_version", 3).put("origin", config.origin())
                    .put("device_id", config.deviceId).put("device_token", config.token)
                    .put("registered", config.registered).put("enrollment_expires_at", JSONObject.NULL)
                : new JSONObject().put("url", config.url).put("device_token", config.token).put("expires_at", config.expiresAt);
    }

    void forget() throws java.io.IOException {
        // Delete only this app's encrypted enrollment record. Keep the Android Keystore key
        // and every certificate intact; a future enrollment uses a new device token and IV.
        file.delete();
        if (file.getBaseFile().exists()) throw new java.io.IOException("Enrollment record remains");
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder output = new StringBuilder(64);
        for (byte value : bytes) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }
}
