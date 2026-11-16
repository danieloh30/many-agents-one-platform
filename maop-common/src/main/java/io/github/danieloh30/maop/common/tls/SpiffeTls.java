package io.github.danieloh30.maop.common.tls;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Builds a client {@link SSLContext} from SVID material. The server is authenticated by validating its chain
 * against the SPIFFE trust bundle and requiring its SPIFFE ID to equal the expected one; DNS hostname verification
 * is replaced by this SPIFFE ID check (as in the SPIFFE X.509-SVID specification's authentication model).
 */
public final class SpiffeTls {

    private SpiffeTls() {
    }

    public static SSLContext clientContext(SvidSource.Material m, String expectedServerSpiffeId)
            throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            char[] pw = new char[0];
            ks.setKeyEntry("svid", m.key(), pw, m.chain());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), new TrustManager[] { trustManager(m.bundle(), expectedServerSpiffeId) }, null);
            return ctx;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    static X509ExtendedTrustManager trustManager(List<X509Certificate> bundle, String expectedPeerId)
            throws GeneralSecurityException {
        try {
            KeyStore ts = KeyStore.getInstance("PKCS12");
            ts.load(null, null);
            int i = 0;
            for (X509Certificate ca : bundle) {
                ts.setCertificateEntry("ca-" + i++, ca);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(ts);
            X509TrustManager pkix = null;
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) {
                    pkix = x;
                }
            }
            return new SpiffeTrustManager(pkix, expectedPeerId);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /** PKIX validation against the bundle plus an exact SPIFFE ID match; no hostname verification. */
    static final class SpiffeTrustManager extends X509ExtendedTrustManager {
        private final X509TrustManager pkix;
        private final String expected;

        SpiffeTrustManager(X509TrustManager pkix, String expected) {
            this.pkix = pkix;
            this.expected = expected;
        }

        private void checkPeer(X509Certificate[] chain, String authType, boolean server) throws CertificateException {
            if (server) {
                pkix.checkServerTrusted(chain, authType);
            } else {
                pkix.checkClientTrusted(chain, authType);
            }
            String id = SpiffeIds.fromCertificate(chain[0])
                    .orElseThrow(() -> new CertificateException("Peer certificate is not an X.509-SVID"));
            if (expected != null && !expected.equals(id)) {
                throw new CertificateException("Unexpected peer SPIFFE ID " + id + " (expected " + expected + ")");
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkPeer(chain, authType, false);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkPeer(chain, authType, true);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkPeer(chain, authType, false);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkPeer(chain, authType, true);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkPeer(chain, authType, false);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkPeer(chain, authType, true);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return pkix.getAcceptedIssuers();
        }
    }
}
