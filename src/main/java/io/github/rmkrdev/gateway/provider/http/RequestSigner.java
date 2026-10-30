package io.github.rmkrdev.gateway.provider.http;

import java.net.URI;
import java.util.Map;

/**
 * Adds per-request authentication headers computed from the exact bytes sent, for protocols
 * such as AWS SigV4 where a static key header is not enough.
 */
@FunctionalInterface
public interface RequestSigner {

    /**
     * @param method HTTP method
     * @param uri full request URI, already encoded as it goes on the wire
     * @param body request body bytes
     * @return headers to add to this request
     */
    Map<String, String> sign(String method, URI uri, byte[] body);
}
