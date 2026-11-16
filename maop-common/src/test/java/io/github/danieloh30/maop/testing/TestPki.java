package io.github.danieloh30.maop.testing;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Test PKI that mimics SPIRE: a trust-domain CA issuing X.509-SVIDs (single URI SAN) written in the same file
 * layout spiffe-helper uses (svid.pem, svid_key.pem as PKCS#8, svid_bundle.pem).
 */
public final class TestPki {

    private final KeyPair caKeys;
    private final X509Certificate ca;
    private long serial = System.nanoTime();

    /** Load a CA persisted by {@link #save(Path)}. */
    public static TestPki load(Path dir) {
        try {
            return new TestPki(io.github.danieloh30.maop.common.tls.PemSupport.key(dir.resolve("ca_key.pem")),
                    io.github.danieloh30.maop.common.tls.PemSupport.certificates(dir.resolve("ca.pem")).getFirst());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TestPki(java.security.PrivateKey key, X509Certificate ca) {
        this.caKeys = new KeyPair(ca.getPublicKey(), key);
        this.ca = ca;
    }

    public TestPki save(Path dir) {
        try {
            Files.createDirectories(dir);
            write(dir.resolve("ca.pem"), ca);
            write(dir.resolve("ca_key.pem"), new JcaPKCS8Generator(caKeys.getPrivate(), null).generate());
            return this;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public TestPki(String name) {
        try {
            caKeys = ec();
            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=" + name);
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(serial++),
                    Date.from(now.minusSeconds(60)), Date.from(now.plus(Duration.ofDays(2))), subject, caKeys.getPublic());
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            ca = sign(b, caKeys);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private X509Certificate sign(X509v3CertificateBuilder b, KeyPair signer) throws Exception {
        ContentSigner s = new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(s));
    }

    /** Issue an SVID with the given URI SANs (normally exactly one spiffe:// ID) plus optional DNS names. */
    public Path issue(Path dir, Duration ttl, List<String> uris, List<String> dnsNames) {
        try {
            Files.createDirectories(dir);
            KeyPair kp = ec();
            Instant now = Instant.now();
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(new X500Name(ca.getSubjectX500Principal().getName()),
                    BigInteger.valueOf(serial++), Date.from(now.minusSeconds(60)), Date.from(now.plus(ttl)),
                    new X500Name("O=SPIRE"), kp.getPublic());
            List<GeneralName> sans = new ArrayList<>();
            uris.forEach(u -> sans.add(new GeneralName(GeneralName.uniformResourceIdentifier, u)));
            dnsNames.forEach(d -> sans.add(new GeneralName(GeneralName.dNSName, d)));
            if (!sans.isEmpty()) {
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans.toArray(GeneralName[]::new)));
            }
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement));
            X509Certificate cert = sign(b, caKeys);
            write(dir.resolve("svid.pem"), cert);
            write(dir.resolve("svid_key.pem"), new JcaPKCS8Generator(kp.getPrivate(), null).generate());
            write(dir.resolve("svid_bundle.pem"), ca);
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Path issue(Path dir, String spiffeId, String... dnsNames) {
        return issue(dir, Duration.ofHours(1), List.of(spiffeId), List.of(dnsNames));
    }

    private static void write(Path file, Object pemObject) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(pemObject);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sw.toString());
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
