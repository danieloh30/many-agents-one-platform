package io.github.danieloh30.maop.tools.model;

import io.github.danieloh30.maop.common.model.Caller;
import io.github.danieloh30.maop.common.tls.SpiffeIds;
import io.vertx.core.http.HttpServerRequest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Caller identity derived only from the verified mTLS peer certificate (the TLS layer has already validated the
 * chain against the SPIFFE trust bundle). Request headers are never consulted.
 */
public record VerifiedCaller(String spiffeId, String trustDomain, String serial, String notAfter) {

    public Caller toCaller() {
        return new Caller(spiffeId, serial, notAfter);
    }

    public String workload() {
        int i = spiffeId.lastIndexOf('/');
        return spiffeId.substring(i + 1);
    }

    public static Optional<VerifiedCaller> from(HttpServerRequest request) {
        SSLSession session = request.sslSession();
        if (session == null) {
            return Optional.empty();
        }
        try {
            Certificate[] certs = session.getPeerCertificates();
            if (certs.length == 0 || !(certs[0] instanceof X509Certificate leaf)) {
                return Optional.empty();
            }
            return SpiffeIds.fromCertificate(leaf).map(id -> new VerifiedCaller(id, SpiffeIds.trustDomain(id),
                    leaf.getSerialNumber().toString(16), leaf.getNotAfter().toInstant().toString()));
        } catch (SSLPeerUnverifiedException e) {
            return Optional.empty();
        }
    }
}
