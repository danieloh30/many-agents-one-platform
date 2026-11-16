package io.github.danieloh30.maop.common.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Reads the X.509-SVID, key and trust bundle that spiffe-helper writes as PEM files, and reloads them when
 * spiffe-helper rotates them (detected by file modification time, checked at most every two seconds).
 */
public final class SvidSource {

    public record Material(PrivateKey key, X509Certificate[] chain, List<X509Certificate> bundle, String spiffeId,
            long fingerprint) {

        public X509Certificate leaf() {
            return chain[0];
        }
    }

    private final Path cert;
    private final Path key;
    private final Path bundle;
    private volatile Material current;
    private volatile long lastCheck;
    private volatile long lastStamp = -1;

    public SvidSource(Path dir) {
        this(dir.resolve("svid.pem"), dir.resolve("svid_key.pem"), dir.resolve("svid_bundle.pem"));
    }

    public SvidSource(Path cert, Path key, Path bundle) {
        this.cert = cert;
        this.key = key;
        this.bundle = bundle;
    }

    public Material current() {
        long now = System.currentTimeMillis();
        if (current == null || now - lastCheck > 2000) {
            synchronized (this) {
                lastCheck = now;
                long stamp = stamp();
                if (current == null || stamp != lastStamp) {
                    current = load();
                    lastStamp = stamp;
                }
            }
        }
        return current;
    }

    /** Load material without requiring a valid X.509-SVID (tests of invalid certificates). */
    public Material currentUnchecked() {
        try {
            List<X509Certificate> chain = readCertificates(cert);
            return new Material(readKey(key), chain.toArray(X509Certificate[]::new), readCertificates(bundle),
                    SpiffeIds.fromCertificate(chain.getFirst()).orElse(null), Instant.now().toEpochMilli());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean available() {
        return Files.isReadable(cert) && Files.isReadable(key) && Files.isReadable(bundle);
    }

    private long stamp() {
        try {
            return Objects.hash(Files.getLastModifiedTime(cert).toMillis(), Files.getLastModifiedTime(key).toMillis(),
                    Files.getLastModifiedTime(bundle).toMillis(), Files.size(cert));
        } catch (IOException e) {
            throw new IllegalStateException("SVID files not readable in " + cert.getParent() + ": " + e.getMessage(), e);
        }
    }

    private Material load() {
        try {
            List<X509Certificate> chain = readCertificates(cert);
            if (chain.isEmpty()) {
                throw new IllegalStateException("No certificate in " + cert);
            }
            List<X509Certificate> trust = readCertificates(bundle);
            PrivateKey pk = readKey(key);
            String id = SpiffeIds.fromCertificate(chain.getFirst())
                    .orElseThrow(() -> new IllegalStateException("Certificate in " + cert + " is not an X.509-SVID"));
            return new Material(pk, chain.toArray(X509Certificate[]::new), trust, id,
                    Instant.now().toEpochMilli());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load SVID material: " + e.getMessage(), e);
        }
    }

    public static List<X509Certificate> readCertificates(Path file) throws IOException, GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<>();
        for (var c : cf.generateCertificates(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            out.add((X509Certificate) c);
        }
        return out;
    }

    public static PrivateKey readKey(Path file) throws IOException, GeneralSecurityException {
        String pem = Files.readString(file, StandardCharsets.US_ASCII);
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new GeneralSecurityException("Expected a PKCS#8 'PRIVATE KEY' PEM in " + file);
        }
        String b64 = pem.substring(pem.indexOf("-----BEGIN PRIVATE KEY-----") + 27, pem.indexOf("-----END PRIVATE KEY-----"))
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String alg : List.of("EC", "RSA", "Ed25519")) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (GeneralSecurityException ignored) {
                // try next algorithm
            }
        }
        throw new GeneralSecurityException("Unsupported private key algorithm in " + file);
    }
}
