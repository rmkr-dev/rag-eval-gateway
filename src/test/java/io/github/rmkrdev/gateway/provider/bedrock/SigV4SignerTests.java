package io.github.rmkrdev.gateway.provider.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks the signer against the AWS SigV4 test suite in {@code src/test/resources/sigv4}. */
class SigV4SignerTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "get-vanilla",
            "post-vanilla",
            "get-vanilla-query",
            "post-vanilla-query",
            "get-vanilla-empty-query-key",
            "post-vanilla-empty-query-value",
            "get-vanilla-query-order-key-case",
            "get-vanilla-query-order-encoded",
            "get-vanilla-query-unreserved",
            "get-vanilla-utf8-query",
            "get-unreserved",
            "get-utf8",
            "get-space-normalized",
            "get-space-unnormalized",
            "get-header-key-duplicate",
            "get-header-value-multiline",
            "get-header-value-order",
            "get-header-value-trim",
            "post-header-key-case",
            "post-header-key-sort",
            "post-header-value-case",
            "get-vanilla-with-session-token",
            "post-sts-header-before",
            "post-x-www-form-urlencoded",
            "post-x-www-form-urlencoded-parameters",
            "bedrock-converse-session-token"
    })
    void matchesTestVector(String name) throws IOException {
        JsonNode context = JSON.readTree(resource(name, "context.json"));
        JsonNode credentials = context.path("credentials");
        SigV4Signer signer = new SigV4Signer(context.path("region").asString(), context.path("service").asString());
        SigV4Signer.Credentials keys = new SigV4Signer.Credentials(credentials.path("access_key_id").asString(),
                credentials.path("secret_access_key").asString(), credentials.path("token").asString(null));

        SigV4Signer.Signature signed = signer.sign(parse(resource(name, "request.txt")), keys,
                Instant.parse(context.path("timestamp").asString()), context.path("sign_body").asBoolean(false));

        assertThat(signed.canonicalRequest()).isEqualTo(resource(name, "header-canonical-request.txt"));
        assertThat(signed.stringToSign()).isEqualTo(resource(name, "header-string-to-sign.txt"));
        assertThat(signed.signature()).isEqualTo(resource(name, "header-signature.txt").strip());
        assertThat(signed.headers().get("Authorization"))
                .startsWith("AWS4-HMAC-SHA256 Credential=" + keys.accessKeyId() + "/")
                .endsWith("Signature=" + signed.signature());
    }

    @Test
    void addsDateAndSessionTokenHeadersAndKeepsKeysOutOfToString() {
        SigV4Signer.Credentials keys = new SigV4Signer.Credentials("AKIDEXAMPLE", "secret-example", "token-example");
        SigV4Signer.Signature signed = new SigV4Signer("eu-west-1", "bedrock").sign(
                new SigV4Signer.Request("POST", "/model/m/converse", null, List.of(Map.entry("host", "h")), new byte[0]),
                keys, Instant.parse("2026-10-30T06:30:00Z"), false);

        assertThat(signed.headers()).containsEntry("X-Amz-Date", "20261030T063000Z")
                .containsEntry("X-Amz-Security-Token", "token-example")
                .doesNotContainKey("x-amz-content-sha256");
        assertThat(signed.headers().get("Authorization"))
                .contains("Credential=AKIDEXAMPLE/20261030/eu-west-1/bedrock/aws4_request")
                .contains("SignedHeaders=host;x-amz-date;x-amz-security-token");
        assertThat(keys.toString()).doesNotContain("AKIDEXAMPLE", "secret-example", "token-example");
    }

    @Test
    void encodesModelIdsAsOneSegment() {
        assertThat(SigV4Signer.uriEncode("anthropic.claude-3-5-sonnet-20240620-v1:0", true))
                .isEqualTo("anthropic.claude-3-5-sonnet-20240620-v1%3A0");
        assertThat(SigV4Signer.uriEncode("arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.x", true))
                .isEqualTo("arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Ainference-profile%2Fus.x");
        assertThat(SigV4Signer.canonicalUri("/model/a%3A0/converse")).isEqualTo("/model/a%253A0/converse");
        assertThat(SigV4Signer.canonicalUri("")).isEqualTo("/");
    }

    /** Reads a raw HTTP request; header lines starting with whitespace continue the previous one. */
    private static SigV4Signer.Request parse(String raw) {
        int split = raw.indexOf("\n\n");
        String head = split < 0 ? raw : raw.substring(0, split);
        byte[] body = split < 0 ? new byte[0] : raw.substring(split + 2).getBytes(StandardCharsets.UTF_8);
        String[] lines = head.split("\n");
        String requestLine = lines[0];
        String method = requestLine.substring(0, requestLine.indexOf(' '));
        String target = requestLine.substring(method.length() + 1, requestLine.lastIndexOf(" HTTP/"));
        int question = target.indexOf('?');
        String path = question < 0 ? target : target.substring(0, question);
        String query = question < 0 ? null : target.substring(question + 1);
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            if (Character.isWhitespace(line.charAt(0))) {
                Map.Entry<String, String> last = headers.removeLast();
                headers.add(new AbstractMap.SimpleEntry<>(last.getKey(), last.getValue() + "\n" + line));
                continue;
            }
            int colon = line.indexOf(':');
            headers.add(new AbstractMap.SimpleEntry<>(line.substring(0, colon), line.substring(colon + 1)));
        }
        return new SigV4Signer.Request(method, path, query, headers, body);
    }

    private static String resource(String vector, String file) throws IOException {
        try (InputStream in = SigV4SignerTests.class.getResourceAsStream("/sigv4/" + vector + "/" + file)) {
            assertThat(in).as(vector + "/" + file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
