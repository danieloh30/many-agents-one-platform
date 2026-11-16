package io.github.danieloh30.maop.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.github.danieloh30.maop.common.tls.SvidSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** Minimal HTTPS server requiring client certificates from the test PKI; for client-side tests. */
public final class FakeMtlsServer implements AutoCloseable {

    public record Reply(int status, String body, long delayMs) {
    }

    public record Seen(String method, String path, String body, java.util.Map<String, List<String>> headers) {
    }

    private final HttpsServer server;
    public final List<Seen> requests = new CopyOnWriteArrayList<>();

    public FakeMtlsServer(java.nio.file.Path svidDir, Function<Seen, Reply> handler) throws Exception {
        SvidSource.Material m = new SvidSource(svidDir).current();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("k", m.key(), new char[0], m.chain());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        for (int i = 0; i < m.bundle().size(); i++) {
            ts.setCertificateEntry("ca" + i, m.bundle().get(i));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters p = ctx.getDefaultSSLParameters();
                p.setNeedClientAuth(true);
                params.setSSLParameters(p);
            }
        });
        server.createContext("/", ex -> handle(ex, handler));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void handle(HttpExchange ex, Function<Seen, Reply> handler) throws IOException {
        Seen seen = new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestHeaders());
        requests.add(seen);
        Reply r = handler.apply(seen);
        if (r.delayMs() > 0) {
            try {
                Thread.sleep(r.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] b = r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    public String url() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
