package io.github.marcofanti.aauth.demo.common;

import io.github.marcofanti.aauth.http.HttpTransport;
import io.github.marcofanti.aauth.keys.CachingJwksFetcher;
import io.github.marcofanti.aauth.keys.DefaultHttpClient;
import io.github.marcofanti.aauth.keys.JwksCache;
import io.github.marcofanti.aauth.signing.JwksFetcher;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * HTTP plumbing the demo injects into the AAuth library.
 *
 * <p>The library's default clients admit only public HTTPS URLs. The demo's identifiers
 * ({@code https://ps.uma.lab}, {@code https://sca.uma.lab}, ...) resolve to 127.0.0.1 behind
 * the local Caddy front, so every library call gets this JDK client instead. TLS trust comes
 * from the JVM truststore ({@code javax.net.ssl.trustStore}, set by scripts/run-demo.sh to the
 * JDK CAs plus the mkcert root). HTTP/1.1 is pinned because the Python Person Server's parser
 * mishandles the JDK client's h2c upgrade.
 */
public final class AAuthHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private AAuthHttp() {}

    public static HttpClient client() {
        return CLIENT;
    }

    public static HttpTransport transport() {
        return HttpTransport.jdk(CLIENT);
    }

    /**
     * A JWKS fetcher (metadata discovery + 1-hour cache) over the demo client. Calls are
     * serialized: {@link CachingJwksFetcher} records a fetch before performing it, so a
     * concurrent cold-cache caller is refused by the per-issuer rate limit instead of waiting
     * for the first fetch. Serialized, the second caller finds the cache warm.
     */
    public static JwksFetcher jwksFetcher() {
        CachingJwksFetcher caching =
                new CachingJwksFetcher(new DefaultHttpClient(CLIENT), new JwksCache(), 60, Clock.systemUTC());
        Object lock = new Object();
        return (id, dwk, kid) -> {
            synchronized (lock) {
                return caching.fetch(id, kid, dwk);
            }
        };
    }
}
