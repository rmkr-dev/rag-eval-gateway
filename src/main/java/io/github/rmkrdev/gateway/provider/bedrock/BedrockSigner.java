package io.github.rmkrdev.gateway.provider.bedrock;

import io.github.rmkrdev.gateway.provider.http.RequestSigner;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Signs Bedrock runtime calls. Only {@code host}, {@code x-amz-date} and the session token are
 * signed, so headers the HTTP client adds or rewrites later cannot break the signature.
 */
final class BedrockSigner implements RequestSigner {

    private final SigV4Signer signer;
    private final SigV4Signer.Credentials credentials;
    private final Clock clock;

    BedrockSigner(SigV4Signer signer, SigV4Signer.Credentials credentials, Clock clock) {
        this.signer = signer;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Override
    public Map<String, String> sign(String method, URI uri, byte[] body) {
        String host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        SigV4Signer.Request request = new SigV4Signer.Request(method, uri.getRawPath(), uri.getRawQuery(),
                List.of(Map.entry("host", host)), body);
        return signer.sign(request, credentials, clock.instant(), false).headers();
    }
}
