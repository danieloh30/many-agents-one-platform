package io.github.danieloh30.maop.common.tls;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** SPIFFE ID helpers (X.509-SVID: exactly one URI SAN with the spiffe scheme). */
public final class SpiffeIds {

    private static final int SAN_URI = 6;

    private SpiffeIds() {
    }

    public static List<String> uriSans(X509Certificate cert) {
        List<String> uris = new ArrayList<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && san.get(0) instanceof Integer type && type == SAN_URI) {
                        uris.add(String.valueOf(san.get(1)));
                    }
                }
            }
        } catch (CertificateParsingException e) {
            return List.of();
        }
        return uris;
    }

    /** The SPIFFE ID of an X.509-SVID, or empty if the certificate is not a valid SVID (zero or several URI SANs). */
    public static Optional<String> fromCertificate(X509Certificate cert) {
        List<String> uris = uriSans(cert);
        if (uris.size() != 1 || !uris.getFirst().startsWith("spiffe://")) {
            return Optional.empty();
        }
        return Optional.of(uris.getFirst());
    }

    public static String trustDomain(String spiffeId) {
        String rest = spiffeId.substring("spiffe://".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    public static boolean inTrustDomain(String spiffeId, String trustDomain) {
        return spiffeId != null && spiffeId.startsWith("spiffe://") && trustDomain(spiffeId).equals(trustDomain);
    }
}
