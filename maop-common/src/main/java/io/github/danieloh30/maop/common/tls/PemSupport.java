package io.github.danieloh30.maop.common.tls;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/** PEM reading helpers (certificates and PKCS#8 private keys). */
public final class PemSupport {

    private PemSupport() {
    }

    public static List<X509Certificate> certificates(Path file) throws Exception {
        return SvidSource.readCertificates(file);
    }

    public static PrivateKey key(Path file) throws Exception {
        return SvidSource.readKey(file);
    }
}
