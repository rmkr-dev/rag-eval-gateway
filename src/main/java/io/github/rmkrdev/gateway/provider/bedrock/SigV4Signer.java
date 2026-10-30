package io.github.rmkrdev.gateway.provider.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for request headers, written against the AWS specification and
 * checked with the official test suite, so the gateway needs no AWS SDK.
 *
 * <p>Paths are taken as they go on the wire and URI-encoded once more for the canonical request,
 * which is what every AWS service except S3 expects. Query parameters are decoded and encoded
 * again, then sorted. Header names are lower-cased, values trimmed with inner whitespace
 * collapsed, and repeated headers joined with commas.
 */
public final class SigV4Signer {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final String region;
    private final String service;

    /**
     * @param region signing region, for example {@code us-east-1}
     * @param service signing name, {@code bedrock} for Bedrock runtime
     */
    public SigV4Signer(String region, String service) {
        this.region = region;
        this.service = service;
    }

    /**
     * AWS credentials. {@link #toString()} never shows the keys.
     *
     * @param sessionToken {@code null} unless the credentials are temporary
     */
    public record Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {

        @Override
        public String toString() {
            return "Credentials[***]";
        }
    }

    /**
     * What gets signed.
     *
     * @param method HTTP method
     * @param rawPath path exactly as sent, already percent-encoded
     * @param rawQuery query string as sent without {@code ?}, or {@code null}
     * @param headers headers to sign, {@code host} included, in the order they are sent
     * @param body request body
     */
    public record Request(String method, String rawPath, String rawQuery, List<Map.Entry<String, String>> headers,
            byte[] body) {
    }

    /**
     * The result of signing.
     *
     * @param headers headers to add to the request: {@code X-Amz-Date}, the session token and
     *     content hash when used, and {@code Authorization}
     */
    public record Signature(String canonicalRequest, String stringToSign, String signature,
            Map<String, String> headers) {
    }

    /**
     * Signs a request.
     *
     * @param signPayloadHeader also send and sign {@code x-amz-content-sha256}
     */
    public Signature sign(Request request, Credentials credentials, Instant time, boolean signPayloadHeader) {
        String amzDate = AMZ_DATE.format(time);
        String date = amzDate.substring(0, 8);
        String payloadHash = HEX.formatHex(sha256(request.body() == null ? new byte[0] : request.body()));

        Map<String, String> added = new LinkedHashMap<>();
        added.put("X-Amz-Date", amzDate);
        if (credentials.sessionToken() != null && !credentials.sessionToken().isBlank()) {
            added.put("X-Amz-Security-Token", credentials.sessionToken());
        }
        if (signPayloadHeader) {
            added.put("x-amz-content-sha256", payloadHash);
        }
        List<Map.Entry<String, String>> all = new ArrayList<>(request.headers());
        all.addAll(added.entrySet());

        Map<String, List<String>> canonical = new TreeMap<>();
        for (Map.Entry<String, String> header : all) {
            canonical.computeIfAbsent(header.getKey().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(WHITESPACE.matcher(header.getValue().strip()).replaceAll(" "));
        }
        StringBuilder headerBlock = new StringBuilder();
        canonical.forEach((name, values) -> headerBlock.append(name).append(':')
                .append(String.join(",", values)).append('\n'));
        String signedHeaders = String.join(";", canonical.keySet());

        String canonicalRequest = request.method() + '\n'
                + canonicalUri(request.rawPath()) + '\n'
                + canonicalQuery(request.rawQuery()) + '\n'
                + headerBlock + '\n'
                + signedHeaders + '\n'
                + payloadHash;

        String scope = date + '/' + region + '/' + service + "/aws4_request";
        String stringToSign = ALGORITHM + '\n' + amzDate + '\n' + scope + '\n'
                + HEX.formatHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] key = hmac(("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = HEX.formatHex(hmac(key, stringToSign));

        Map<String, String> headers = new LinkedHashMap<>(added);
        headers.put("Authorization", ALGORITHM + " Credential=" + credentials.accessKeyId() + '/' + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return new Signature(canonicalRequest, stringToSign, signature, headers);
    }

    static String canonicalUri(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        return uriEncode(rawPath, false);
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String[]> params = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.add(new String[] {uriEncode(percentDecode(name), true), uriEncode(percentDecode(value), true)});
        }
        params.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        StringBuilder query = new StringBuilder();
        for (String[] param : params) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(param[0]).append('=').append(param[1]);
        }
        return query.toString();
    }

    /**
     * Percent-encodes every byte except the RFC 3986 unreserved characters.
     *
     * @param encodeSlash whether {@code /} is encoded too; it is kept in paths
     */
    public static String uriEncode(String value, boolean encodeSlash) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length
                    && Character.digit(bytes[i + 1], 16) >= 0 && Character.digit(bytes[i + 2], 16) >= 0) {
                out.write(Character.digit(bytes[i + 1], 16) * 16 + Character.digit(bytes[i + 2], 16));
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
