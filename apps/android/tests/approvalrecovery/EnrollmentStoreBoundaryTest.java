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
        public boolean engineContainsAlias(String alias){return true;}
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
    public static void main(String[] args) throws Exception {
        Provider provider=new Provider("SyntheticAndroidKeyStore","1.0","synthetic host keystore"){};
        provider.put("KeyStore.AndroidKeyStore",MemoryKeyStore.class.getName());Security.addProvider(provider);
        boundary("trusted-ai",16384);boundary("enrollment",4096);
        System.out.println("TOTAL 2");
    }
}
