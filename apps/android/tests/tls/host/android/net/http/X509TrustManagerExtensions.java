package android.net.http;
import java.security.cert.*;import java.util.*;import javax.net.ssl.*;
/** Host seam only: Android itself is exercised by the separate emulator probe. */
public final class X509TrustManagerExtensions {
 public interface VerifiedPath { List<X509Certificate> path(X509Certificate[] chain,String auth,String host)throws CertificateException; }
 private final VerifiedPath verifier;
 public X509TrustManagerExtensions(X509TrustManager manager){if(!(manager instanceof VerifiedPath))throw new IllegalArgumentException("host fixture needs verified-path provider");verifier=(VerifiedPath)manager;}
 public List<X509Certificate> checkServerTrusted(X509Certificate[] chain,String auth,String host)throws CertificateException{return verifier.path(chain,auth,host);}
 public boolean isSameTrustConfiguration(String a,String b){return a.equals(b);}
}
