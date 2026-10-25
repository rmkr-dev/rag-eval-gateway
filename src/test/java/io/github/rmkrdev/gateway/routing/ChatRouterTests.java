package io.github.rmkrdev.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import io.github.rmkrdev.gateway.provider.ChatChunk;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatProviderFactory;
import io.github.rmkrdev.gateway.provider.ChatStream;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import io.github.rmkrdev.gateway.provider.ProviderRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ChatRouterTests {

    private static final ChatRequest REQUEST =
            new ChatRequest("chat", List.of(new ChatMessage("user", "hi")), null, null, null, null);

    private final Map<String, Deque<Supplier<ProviderReply>>> scripts = new HashMap<>();
    private final Map<String, Deque<Supplier<ChatStream>>> streams = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final List<Duration> sleeps = new ArrayList<>();

    private static ProviderReply answer(String text) {
        return new ProviderReply(new ChatMessage("assistant", text), "stop", new Usage(1, 1));
    }

    private Supplier<ProviderReply> fail(String provider, Failure failure, Integer status, Duration retryAfter) {
        return () -> {
            throw new ProviderException(provider, failure, status, retryAfter, "boom", null);
        };
    }

    private void script(String provider, List<Supplier<ProviderReply>> steps) {
        scripts.put(provider, new ArrayDeque<>(steps));
    }

    private ChatRouter router(int maxAttempts, String... targetProviders) {
        Map<String, GatewayProperties.Provider> providers = new LinkedHashMap<>();
        List<GatewayProperties.Target> targets = new ArrayList<>();
        for (String id : targetProviders) {
            ProviderType type = id.startsWith("native") ? ProviderType.ANTHROPIC : ProviderType.OPENAI_COMPATIBLE;
            providers.put(id, new GatewayProperties.Provider(type, "http://" + id, null, "Authorization", "Bearer ",
                    Duration.ofSeconds(1), Duration.ofSeconds(1), "/chat/completions", Map.of(), null));
            targets.add(new GatewayProperties.Target(id, id + "-model"));
        }
        GatewayProperties.Retry retry = new GatewayProperties.Retry(maxAttempts, Duration.ofMillis(100),
                Duration.ofMillis(250), 2.0, 0.0);
        GatewayProperties properties = new GatewayProperties(providers,
                Map.of("chat", new GatewayProperties.ModelAlias(targets)), retry);
        ChatProviderFactory factory = new ChatProviderFactory() {
            @Override
            public ProviderType type() {
                return ProviderType.OPENAI_COMPATIBLE;
            }

            @Override
            public ChatProvider create(String id, GatewayProperties.Provider config) {
                return new ChatProvider() {
                    @Override
                    public String id() {
                        return id;
                    }

                    @Override
                    public ProviderReply chat(String model, ChatRequest request) {
                        calls.merge(id, 1, Integer::sum);
                        return scripts.get(id).removeFirst().get();
                    }

                    @Override
                    public ChatStream stream(String model, ChatRequest request) {
                        calls.merge(id, 1, Integer::sum);
                        return streams.get(id).removeFirst().get();
                    }
                };
            }
        };
        ProviderRegistry registry = new ProviderRegistry(properties, List.of(factory));
        return new ChatRouter(properties, registry, new Backoff(retry, () -> 0.0), sleeps::add);
    }

    @Test
    void firstTargetAnswers() {
        script("primary", List.of(() -> answer("one")));

        RoutedReply routed = router(2, "primary", "backup").route(REQUEST);

        assertThat(routed.reply().message().content()).isEqualTo("one");
        assertThat(routed.target().provider()).isEqualTo("primary");
        assertThat(routed.target().model()).isEqualTo("primary-model");
        assertThat(routed.calls()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesTransientFailureOnSameTarget() {
        script("primary", List.of(fail("primary", Failure.TRANSIENT, 503, null), () -> answer("second try")));

        RoutedReply routed = router(3, "primary", "backup").route(REQUEST);

        assertThat(routed.target().provider()).isEqualTo("primary");
        assertThat(routed.calls()).isEqualTo(2);
        assertThat(routed.failedAttempts()).containsExactly(
                new Attempt("primary", "primary-model", 1, Failure.TRANSIENT, 503));
        assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void fallsBackAfterRetriesAreExhaustedWithGrowingCappedBackoff() {
        script("primary", List.of(
                fail("primary", Failure.TRANSIENT, 500, null),
                fail("primary", Failure.TRANSIENT, 500, null),
                fail("primary", Failure.TRANSIENT, null, null),
                fail("primary", Failure.TRANSIENT, 502, null)));
        script("backup", List.of(() -> answer("backup")));

        RoutedReply routed = router(4, "primary", "backup").route(REQUEST);

        assertThat(routed.target().provider()).isEqualTo("backup");
        assertThat(calls).containsEntry("primary", 4).containsEntry("backup", 1);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(250));
    }

    @Test
    void honoursRetryAfterUpToMaxBackoff() {
        script("primary", List.of(
                fail("primary", Failure.TRANSIENT, 429, Duration.ofMillis(150)),
                fail("primary", Failure.TRANSIENT, 429, Duration.ofSeconds(30)),
                () -> answer("ok")));

        router(3, "primary").route(REQUEST);

        assertThat(sleeps).containsExactly(Duration.ofMillis(150), Duration.ofMillis(250));
    }

    @Test
    void providerFailureFallsBackWithoutRetry() {
        script("primary", List.of(fail("primary", Failure.PROVIDER, 401, null)));
        script("backup", List.of(() -> answer("backup")));

        RoutedReply routed = router(3, "primary", "backup").route(REQUEST);

        assertThat(routed.target().provider()).isEqualTo("backup");
        assertThat(calls).containsEntry("primary", 1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void requestFailureStopsRouting() {
        script("primary", List.of(fail("primary", Failure.REQUEST, 422, null)));
        script("backup", List.of(() -> answer("never")));

        RequestRejectedException e = catchThrowableOfType(RequestRejectedException.class,
                () -> router(3, "primary", "backup").route(REQUEST));

        assertThat(e.status()).isEqualTo(422);
        assertThat(e.attempts()).hasSize(1);
        assertThat(calls).doesNotContainKey("backup");
    }

    @Test
    void reportsEveryAttemptWhenAllTargetsFail() {
        script("primary", List.of(
                fail("primary", Failure.TRANSIENT, 503, null), fail("primary", Failure.TRANSIENT, 503, null)));
        script("backup", List.of(fail("backup", Failure.PROVIDER, 404, null)));

        AllTargetsFailedException e = catchThrowableOfType(AllTargetsFailedException.class,
                () -> router(2, "primary", "backup").route(REQUEST));

        assertThat(e.alias()).isEqualTo("chat");
        assertThat(e.attempts()).containsExactly(
                new Attempt("primary", "primary-model", 1, Failure.TRANSIENT, 503),
                new Attempt("primary", "primary-model", 2, Failure.TRANSIENT, 503),
                new Attempt("backup", "backup-model", 1, Failure.PROVIDER, 404));
    }

    @Test
    void skipsProviderTypesWithoutAnAdapter() {
        script("backup", List.of(() -> answer("backup")));

        RoutedReply routed = router(3, "native-anthropic", "backup").route(REQUEST);

        assertThat(routed.target().provider()).isEqualTo("backup");
        assertThat(routed.failedAttempts()).containsExactly(
                new Attempt("native-anthropic", "native-anthropic-model", 1, Failure.PROVIDER, null));
    }

    @Test
    void unknownAliasIsRejected() {
        ChatRequest request = new ChatRequest("nope", REQUEST.messages(), null, null, null, null);

        assertThatThrownBy(() -> router(1, "primary").route(request))
                .isInstanceOf(UnknownModelException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void jitterShortensTheWaitWithinBounds() {
        GatewayProperties.Retry retry = new GatewayProperties.Retry(3, Duration.ofMillis(1000),
                Duration.ofSeconds(10), 2.0, 0.5);

        assertThat(new Backoff(retry, () -> 0.0).delay(2, null)).isEqualTo(Duration.ofMillis(2000));
        assertThat(new Backoff(retry, () -> 1.0).delay(2, null)).isEqualTo(Duration.ofMillis(1000));
        assertThat(new Backoff(retry).delay(1, null)).isBetween(Duration.ofMillis(500), Duration.ofMillis(1000));
    }

    /** A scripted stream: each step returns a chunk or throws; {@code null} ends it. */
    private static final class ScriptedStream implements ChatStream {

        private final Iterator<Supplier<ChatChunk>> steps;
        boolean closed;

        ScriptedStream(List<Supplier<ChatChunk>> steps) {
            this.steps = steps.iterator();
        }

        @Override
        public ChatChunk next() {
            return steps.hasNext() ? steps.next().get() : null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static Supplier<ChatChunk> chunk(String text) {
        return () -> ChatChunk.text(text);
    }

    private static Supplier<ChatChunk> failChunk(String provider, Failure failure) {
        return () -> {
            throw new ProviderException(provider, failure, null, "stream broke");
        };
    }

    private void streams(String provider, List<Supplier<ChatStream>> steps) {
        streams.put(provider, new ArrayDeque<>(steps));
    }

    private static Supplier<ChatStream> failOpen(String provider, Failure failure, Integer status) {
        return () -> {
            throw new ProviderException(provider, failure, status, "cannot open");
        };
    }

    @Test
    void streamStartsOnFirstTargetAndHandsOutTheFirstChunkFirst() {
        ScriptedStream upstream = new ScriptedStream(List.of(chunk("Hel"), chunk("lo")));
        streams("primary", List.of(() -> upstream));

        RoutedStream routed = router(2, "primary", "backup").routeStream(REQUEST);

        assertThat(routed.target().provider()).isEqualTo("primary");
        assertThat(routed.calls()).isEqualTo(1);
        assertThat(routed.timeToFirstToken()).isPositive();
        assertThat(routed.next().content()).isEqualTo("Hel");
        assertThat(routed.next().content()).isEqualTo("lo");
        assertThat(routed.next()).isNull();
        assertThat(routed.elapsed()).isGreaterThanOrEqualTo(routed.timeToFirstToken());
        routed.close();
        assertThat(upstream.closed).isTrue();
    }

    @Test
    void streamRetriesAndFallsBackBeforeTheFirstChunk() {
        ScriptedStream brokenBeforeFirstToken = new ScriptedStream(List.of(failChunk("primary", Failure.TRANSIENT)));
        streams("primary", List.of(failOpen("primary", Failure.TRANSIENT, 503), () -> brokenBeforeFirstToken));
        streams("backup", List.of(() -> new ScriptedStream(List.of(chunk("from backup")))));

        RoutedStream routed = router(2, "primary", "backup").routeStream(REQUEST);

        assertThat(routed.target().provider()).isEqualTo("backup");
        assertThat(routed.calls()).isEqualTo(3);
        assertThat(routed.failedAttempts()).containsExactly(
                new Attempt("primary", "primary-model", 1, Failure.TRANSIENT, 503),
                new Attempt("primary", "primary-model", 2, Failure.TRANSIENT, null));
        assertThat(brokenBeforeFirstToken.closed).isTrue();
        assertThat(sleeps).containsExactly(Duration.ofMillis(100));
        assertThat(routed.next().content()).isEqualTo("from backup");
    }

    @Test
    void emptyStreamCountsAsAProviderFailure() {
        ScriptedStream empty = new ScriptedStream(List.of());
        streams("primary", List.of(() -> empty));
        streams("backup", List.of(() -> new ScriptedStream(List.of(chunk("ok")))));

        RoutedStream routed = router(3, "primary", "backup").routeStream(REQUEST);

        assertThat(routed.target().provider()).isEqualTo("backup");
        assertThat(routed.failedAttempts()).extracting(Attempt::failure).containsExactly(Failure.PROVIDER);
        assertThat(empty.closed).isTrue();
    }

    @Test
    void streamRequestRejectionStopsRouting() {
        streams("primary", List.of(failOpen("primary", Failure.REQUEST, 400)));

        RequestRejectedException e = catchThrowableOfType(RequestRejectedException.class,
                () -> router(3, "primary", "backup").routeStream(REQUEST));

        assertThat(e.status()).isEqualTo(400);
        assertThat(calls).doesNotContainKey("backup");
    }

    @Test
    void failureAfterTheFirstChunkReachesTheCaller() {
        streams("primary", List.of(() -> new ScriptedStream(List.of(chunk("partial"),
                failChunk("primary", Failure.TRANSIENT)))));

        RoutedStream routed = router(3, "primary", "backup").routeStream(REQUEST);

        assertThat(routed.next().content()).isEqualTo("partial");
        assertThatThrownBy(routed::next).isInstanceOf(ProviderException.class);
        assertThat(calls).doesNotContainKey("backup");
    }

    @Test
    void plainCallsReportElapsedTime() {
        script("primary", List.of(() -> answer("one")));

        assertThat(router(1, "primary").route(REQUEST).elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
    }
}
