package io.github.rmkrdev.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatProviderFactory;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ChatRouterTests {

    private static final ChatRequest REQUEST =
            new ChatRequest("chat", List.of(new ChatMessage("user", "hi")), null, null, null, null);

    private final Map<String, Deque<Supplier<ProviderReply>>> scripts = new HashMap<>();
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
                    Duration.ofSeconds(1), Duration.ofSeconds(1), "/chat/completions", Map.of()));
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
}
