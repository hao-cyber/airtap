package io.airtap.preview;

import android.net.http.X509TrustManagerExtensions;
import java.net.Socket;
import java.io.IOException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import okhttp3.OkHttpClient;

/** App-local additive TLS policy; Android trust and OkHttp hostname checks remain required. */
public final class TlsPolicy {
    static final String USER_MESSAGE = "无法验证服务的安全连接，请检查 Airtap 或系统更新";
    private TlsPolicy() {}
    static boolean isSecurityFailure(Throwable failure) {
        // Inspect only types, never remote text, hostnames, tokens or certificate descriptions.
        for (int i = 0; failure != null && i < 12; i++, failure = failure.getCause()) {
            if (failure instanceof PolicyUnavailableException || failure instanceof SSLHandshakeException
                    || failure instanceof SSLPeerUnverifiedException || failure instanceof CertificateException) return true;
        }
        return false;
    }
    private static final class PolicyUnavailableException extends IOException {
        PolicyUnavailableException() { super("Airtap TLS policy unavailable"); }
    }
    static OkHttpClient.Builder newClientBuilder() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            TrustManager[] managers = factory.getTrustManagers();
            if (managers.length != 1 || !(managers[0] instanceof X509ExtendedTrustManager))
                throw new IllegalStateException("Android extended TLS verifier unavailable");
            DenylistTrustManager manager = new DenylistTrustManager((X509ExtendedTrustManager) managers[0]);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{manager}, null);
            // Do not replace the default hostname verifier, trust anchors, redirects or retry policy.
            return new OkHttpClient.Builder().sslSocketFactory(context.getSocketFactory(), manager);
        } catch (Exception failure) {
            // Keep the local controls usable on unsupported platforms; never send a request.
            return unavailableClientBuilder();
        }
    }

    static OkHttpClient.Builder unavailableClientBuilder() {
        // No TLS or cleartext connection spec means OkHttp does not initialize its default TLS
        // verifier. The application interceptor also refuses HTTP and WebSocket before any I/O.
        return new OkHttpClient.Builder().connectionSpecs(List.of()).addInterceptor(chain -> {
            throw new PolicyUnavailableException();
        });
    }

    /** Public host-aware methods are used reflectively by Android/OkHttp; see TLS R8 rules. */
    public static final class DenylistTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager system;
        private final X509TrustManagerExtensions extensions;
        DenylistTrustManager(X509ExtendedTrustManager system) {
            this.system = system;
            extensions = new X509TrustManagerExtensions(system);
        }
        private static String host(SSLSession session) throws CertificateException {
            if (session == null || session.getPeerHost() == null || session.getPeerHost().isEmpty())
                throw new CertificateException("TLS hostname context required");
            return session.getPeerHost();
        }
        public List<X509Certificate> checkServerTrusted(X509Certificate[] chain, String authType, String hostname) throws CertificateException {
            if (hostname == null || hostname.isEmpty()) throw new CertificateException("TLS hostname context required");
            List<X509Certificate> verified = extensions.checkServerTrusted(chain, authType, hostname);
            // The verified path includes anchors and cached intermediates absent from the peer's list.
            CompromisedKeys.check(verified);
            CompromisedKeys.check(chain == null ? null : Arrays.asList(chain));
            return verified;
        }
        public boolean isSameTrustConfiguration(String first, String second) {
            return extensions.isSameTrustConfiguration(first, second);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            // Preserve platform socket context, including its CT/OCSP and network-security checks.
            system.checkServerTrusted(chain, authType, socket);
            if (!(socket instanceof SSLSocket)) throw new CertificateException("TLS socket context required");
            checkServerTrusted(chain, authType, host(((SSLSocket) socket).getHandshakeSession()));
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            system.checkServerTrusted(chain, authType, engine);
            checkServerTrusted(chain, authType, host(engine == null ? null : engine.getHandshakeSession()));
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // All supported Airtap network paths provide a hostname; no incomplete-path fallback.
            throw new CertificateException("TLS hostname context required");
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { system.checkClientTrusted(chain, authType); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { system.checkClientTrusted(chain, authType, socket); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { system.checkClientTrusted(chain, authType, engine); }
    }
}
