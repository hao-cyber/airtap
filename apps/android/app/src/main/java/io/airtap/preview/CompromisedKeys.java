package io.airtap.preview;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

/** Additional SPKI denial; never grants trust. Updated from the public incident metadata. */
final class CompromisedKeys {
    // https://emudhra.we-have-a-problem.com/impacted-intermediates.json (2026-10-27)
    // Eight keys, including both TLS and code-signing CAs. Cross-signs share these keys.
    static final Set<String> SHA256 = Set.of(
        "ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105",
        "43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b",
        "e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41",
        "49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f",
        "63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8",
        "5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0",
        "ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6",
        "92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30"
    );
    private CompromisedKeys() {}
    static void check(List<X509Certificate> chain) throws CertificateException {
        if (chain == null || chain.isEmpty()) throw new CertificateException("TLS verified chain unavailable");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (X509Certificate certificate : chain) {
                if (certificate == null) throw new CertificateException("TLS certificate unavailable");
                byte[] spki = certificate.getPublicKey().getEncoded();
                if (spki == null || spki.length == 0) throw new CertificateException("TLS public key unavailable");
                StringBuilder hex = new StringBuilder(64);
                for (byte value : digest.digest(spki)) hex.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
                if (SHA256.contains(hex.toString())) throw new CertificateException("TLS compromised intermediate key rejected");
            }
        } catch (CertificateException failure) { throw failure; }
        catch (Exception failure) { throw new CertificateException("TLS key validation unavailable", failure); }
    }
}
