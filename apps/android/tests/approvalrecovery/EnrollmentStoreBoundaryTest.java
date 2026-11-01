package io.airtap.preview;

import android.content.Context;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.Enumeration;
import org.json.JSONObject;

/** Real production encrypted IO and JCA AES-GCM with synthetic keystore/platform adapters. */
public final class EnrollmentStoreBoundaryTest {
    public static final class MemoryKeyStore extends KeyStoreSpi {
        static boolean available = true;
        private static final Key KEY=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        public Key engineGetKey(String alias,char[] password){return KEY;}
        public Certificate[] engineGetCertificateChain(String alias){return null;}
        public Certificate engineGetCertificate(String alias){return null;}
        public Date engineGetCreationDate(String alias){return new Date(0);}
        public void engineSetKeyEntry(String alias,Key key,char[] password,Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetKeyEntry(String alias,byte[] key,Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetCertificateEntry(String alias,Certificate certificate){throw new UnsupportedOperationException();}
        public void engineDeleteEntry(String alias){throw new UnsupportedOperationException();}
        public Enumeration<String> engineAliases(){return java.util.Collections.enumeration(java.util.List.of("synthetic"));}
        public boolean engineContainsAlias(String alias){return available;}
        public int engineSize(){return 1;}
        public boolean engineIsKeyEntry(String alias){return true;}
        public boolean engineIsCertificateEntry(String alias){return false;}
        public String engineGetCertificateAlias(Certificate certificate){return null;}
        public void engineStore(OutputStream stream,char[] password){}
        public void engineLoad(InputStream stream,char[] password){}
    }
    private static void require(boolean condition){if(!condition)throw new AssertionError();}
    private static JSONObject record(int bytes){return new JSONObject("{\"data\":\""+"x".repeat(bytes-11)+"\"}");}
    private static void boundary(String purpose,int limit) throws Exception {
        Context context=new Context();EnrollmentStore store=new EnrollmentStore(context,purpose);
        var file=context.getFilesDir().toPath().resolve(purpose+".aesgcm");
        JSONObject allowed=record(limit-29);store.saveRecord(allowed);
        require(Files.size(file)==limit);require(allowed.toString().equals(store.readRecord().toString()));
        byte[] saved=Files.readAllBytes(file);
        try{store.saveRecord(record(limit-28));throw new AssertionError("oversized write accepted");}catch(IllegalStateException expected){require("record_too_large".equals(expected.getMessage()));}
        require(java.util.Arrays.equals(saved,Files.readAllBytes(file)));
        Files.write(file,new byte[limit+1]);try{store.readRecord();throw new AssertionError("oversized read accepted");}catch(IllegalStateException expected){}
        Files.write(file,new byte[28]);try{store.readRecord();throw new AssertionError("truncated envelope accepted");}catch(IllegalStateException expected){}
        System.out.println("PASS "+purpose+" exact encrypted boundary, oversized read/write refusal, existing bytes preserved, truncated refusal");
    }
    private static java.util.Map<String,String> files(Context context) throws Exception {
        var result = new java.util.TreeMap<String,String>();
        try (var paths = Files.list(context.getFilesDir().toPath())) {
            for (var file : paths.toList()) result.put(file.getFileName().toString(), java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        }
        return result;
    }
    private static void diagnosticReads() throws Exception {
        Context context = new Context(); EnrollmentStore store = new EnrollmentStore(context,"trusted-ai");
        var file = context.getFilesDir().toPath().resolve("trusted-ai.aesgcm");
        int atomicReads = android.util.AtomicFile.reads;
        MemoryKeyStore.available = false;
        require(store.readRecordForDiagnostics() == null && files(context).isEmpty());
        require(!MemoryKeyStore.available);
        System.out.println("PASS absent diagnostic record creates neither file nor key");
        MemoryKeyStore.available = true; JSONObject allowed = record(200); store.saveRecord(allowed);
        var before = files(context);
        require(allowed.toString().equals(store.readRecordForDiagnostics().toString()));
        require(before.equals(files(context)) && android.util.AtomicFile.reads == atomicReads);
        System.out.println("PASS diagnostic decrypt preserves committed bytes and bypasses AtomicFile recovery");
        for (String suffix : new String[]{".new",".bak"}) {
            var sibling = file.resolveSibling(file.getFileName()+suffix); Files.writeString(sibling,"synthetic incomplete write"); before = files(context);
            try { store.readRecordForDiagnostics(); throw new AssertionError("incomplete atomic write accepted"); } catch (IllegalStateException expected) {}
            require(before.equals(files(context)) && android.util.AtomicFile.reads == atomicReads); Files.delete(sibling);
        }
        MemoryKeyStore.available = false; before = files(context);
        try { store.readRecordForDiagnostics(); throw new AssertionError("missing key accepted"); } catch (IllegalStateException expected) {}
        require(!MemoryKeyStore.available && before.equals(files(context))); MemoryKeyStore.available = true;
        System.out.println("PASS incomplete write and unavailable key refuse without repair, deletion or replacement");
        for (int length : new int[]{16385,28}) {
            Files.write(file,new byte[length]); before = files(context);
            try { store.readRecordForDiagnostics(); throw new AssertionError("invalid envelope accepted"); } catch (IllegalStateException expected) {}
            require(before.equals(files(context)) && android.util.AtomicFile.reads == atomicReads);
        }
        System.out.println("PASS diagnostic encrypted envelope size limits remain enforced without writes");
    }
    public static void main(String[] args) throws Exception {
        Provider provider=new Provider("SyntheticAndroidKeyStore","1.0","synthetic host keystore"){};
        provider.put("KeyStore.AndroidKeyStore",MemoryKeyStore.class.getName());Security.addProvider(provider);
        boundary("trusted-ai",16384);boundary("enrollment",4096);
        diagnosticReads();
        System.out.println("TOTAL 6");
    }
}
