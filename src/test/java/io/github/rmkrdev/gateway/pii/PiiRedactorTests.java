package io.github.rmkrdev.gateway.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PiiRedactorTests {

    static PiiProperties properties(PiiMode mode) {
        return properties(mode, Map.of(), Map.of(), false);
    }

    static PiiProperties properties(PiiMode mode, Map<String, PiiMode> models, Map<String, PiiMode> clients,
            boolean restore) {
        return new PiiProperties(mode, models, clients, List.of(), Map.of(), restore, true);
    }

    private static ChatRequest request(String model, String... contents) {
        List<ChatMessage> messages = new ArrayList<>();
        for (String content : contents) {
            messages.add(new ChatMessage("user", content));
        }
        return new ChatRequest(model, messages, 0.2, 100, true, Map.of("feature", "support"));
    }

    private static PiiRedactor redactor(PiiProperties properties) {
        return new PiiRedactor(BuiltInDetectors.all(), properties);
    }

    @Test
    void offLeavesTheRequestUntouched() {
        ChatRequest request = request("chat", "mail priya@example.com");
        Redaction redaction = redactor(properties(PiiMode.OFF)).redact(request, "billing-api");
        assertThat(redaction.request()).isSameAs(request);
        assertThat(redaction.total()).isZero();
        assertThat(redaction.restorer()).isNull();
    }

    @Test
    void redactNumbersPlaceholdersAndReusesThemForTheSameValue() {
        Redaction redaction = redactor(properties(PiiMode.REDACT)).redact(request("chat",
                "Mail priya@example.com and ravi@example.com, card 4111 1111 1111 1111",
                "Again: priya@example.com from 10.0.0.12"), "billing-api");

        List<ChatMessage> messages = redaction.request().messages();
        assertThat(messages.get(0).content()).isEqualTo("Mail [EMAIL_1] and [EMAIL_2], card [CARD_1]");
        assertThat(messages.get(1).content()).isEqualTo("Again: [EMAIL_1] from [IP_1]");
        assertThat(redaction.counts()).containsExactly(
                Map.entry("credit-card", 1), Map.entry("email", 3), Map.entry("ip-address", 1));
        assertThat(redaction.total()).isEqualTo(5);
        assertThat(redaction.summary()).isEqualTo("credit-card=1,email=3,ip-address=1");
        assertThat(redaction.request().model()).isEqualTo("chat");
        assertThat(redaction.request().maxTokens()).isEqualTo(100);
        assertThat(redaction.request().stream()).isTrue();
        assertThat(redaction.request().metadata()).containsEntry("feature", "support");
    }

    @Test
    void untouchedMessagesKeepTheirIdentity() {
        ChatRequest request = request("chat", "no personal data here", "call 9876543210");
        Redaction redaction = redactor(properties(PiiMode.REDACT)).redact(request, null);
        assertThat(redaction.request().messages().get(0)).isSameAs(request.messages().get(0));
        assertThat(redaction.request().messages().get(1).content()).isEqualTo("call [PHONE_1]");
    }

    @Test
    void nothingFoundReturnsTheSameRequest() {
        ChatRequest request = request("chat", "What is retrieval augmented generation?");
        assertThat(redactor(properties(PiiMode.REDACT)).redact(request, null).request()).isSameAs(request);
    }

    @Test
    void maskKeepsTheShapeButNotTheValue() {
        Redaction redaction = redactor(properties(PiiMode.MASK)).redact(
                request("chat", "card 4111 1111 1111 1111, aadhaar 2345 6789 0124"), null);
        assertThat(redaction.request().messages().get(0).content())
                .isEqualTo("card **** **** **** 1111, aadhaar **** **** 0124");
        assertThat(redaction.restorer()).isNull();
    }

    @Test
    void blockRefusesWithCountsOnly() {
        PiiBlockedException e = catchThrowableOfType(PiiBlockedException.class, () -> redactor(properties(PiiMode.BLOCK))
                .redact(request("chat", "PAN ABCPE1234F, SSN 123-45-6789, PAN ABCPE1234F"), null));
        assertThat(e.counts()).containsExactly(Map.entry("us-ssn", 1), Map.entry("in-pan", 2));
        assertThat(e.total()).isEqualTo(3);
        assertThat(e.getMessage()).contains("us-ssn", "in-pan").doesNotContain("ABCPE1234F", "123-45-6789");
    }

    @Test
    void blockLetsCleanPromptsThrough() {
        ChatRequest request = request("chat", "Summarise the attached policy.");
        assertThat(redactor(properties(PiiMode.BLOCK)).redact(request, null).request()).isSameAs(request);
    }

    @Test
    void stricterOfModelAndClientWinsAndClientsIgnoreCase() {
        PiiRedactor redactor = redactor(properties(PiiMode.REDACT,
                Map.of("internal", PiiMode.OFF, "public", PiiMode.BLOCK),
                Map.of("Billing-API", PiiMode.MASK, "trusted", PiiMode.OFF), false));
        assertThat(redactor.modeFor("someone", "chat")).isEqualTo(PiiMode.REDACT);
        assertThat(redactor.modeFor("billing-api", "chat")).isEqualTo(PiiMode.MASK);
        assertThat(redactor.modeFor("someone", "internal")).isEqualTo(PiiMode.OFF);
        assertThat(redactor.modeFor("billing-api", "internal")).isEqualTo(PiiMode.MASK);
        assertThat(redactor.modeFor("trusted", "public")).isEqualTo(PiiMode.BLOCK);
        assertThat(redactor.modeFor("trusted", "chat")).isEqualTo(PiiMode.OFF);
        assertThat(redactor.modeFor(null, null)).isEqualTo(PiiMode.REDACT);
    }

    @Test
    void earlierDetectorsWinOverlaps() {
        PiiDetector employee = new PatternDetector("employee-id", "EMPLOYEE", Pattern.compile("EMP-\\d{10}"));
        List<PiiDetector> detectors = new ArrayList<>(List.of(employee));
        detectors.addAll(BuiltInDetectors.all());
        PiiRedactor redactor = new PiiRedactor(detectors, properties(PiiMode.REDACT));

        Redaction redaction = redactor.redact(request("chat", "EMP-9876543210 called +91 98765 43210"), null);
        assertThat(redaction.request().messages().get(0).content()).isEqualTo("[EMPLOYEE_1] called [PHONE_1]");
    }

    @Test
    void aadhaarIsNotAlsoAPhoneNumber() {
        Redaction redaction = redactor(properties(PiiMode.REDACT)).redact(request("chat", "id 987654321012"), null);
        assertThat(redaction.counts()).containsOnlyKeys("in-aadhaar");
    }

    @Test
    void restoreIsOnlyOfferedForRedactWhenSwitchedOn() {
        ChatRequest request = request("chat", "mail priya@example.com");
        assertThat(redactor(properties(PiiMode.REDACT)).redact(request, null).restorer()).isNull();
        Redaction redaction = redactor(properties(PiiMode.REDACT, Map.of(), Map.of(), true)).redact(request, null);
        assertThat(redaction.restore("Sent to [EMAIL_1].")).isEqualTo("Sent to priya@example.com.");
        assertThat(redactor(properties(PiiMode.MASK, Map.of(), Map.of(), true)).redact(request, null).restorer())
                .isNull();
    }

    @Test
    void scrubUsesLabelsWithoutNumbers() {
        PiiRedactor redactor = redactor(properties(PiiMode.OFF));
        assertThat(redactor.scrub("upstream said: bad input 'priya@example.com' from 10.1.2.3"))
                .isEqualTo("upstream said: bad input '[EMAIL]' from [IP]");
        assertThat(redactor.forLog("priya@example.com")).isEqualTo("[EMAIL]");
        assertThat(redactor.scrub(null)).isNull();
        PiiRedactor raw = new PiiRedactor(BuiltInDetectors.all(),
                new PiiProperties(PiiMode.OFF, Map.of(), Map.of(), List.of(), Map.of(), false, false));
        assertThat(raw.forLog("priya@example.com")).isEqualTo("priya@example.com");
    }

    @Test
    void badSpansFromAPluggedInDetectorAreIgnored() {
        PiiDetector broken = new PiiDetector() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public void detect(String text, Sink sink) {
                sink.found(-1, 3);
                sink.found(2, 2);
                sink.found(0, text.length() + 5);
            }
        };
        PiiRedactor redactor = new PiiRedactor(List.of(broken), properties(PiiMode.REDACT));
        ChatRequest request = request("chat", "hello");
        assertThat(redactor.redact(request, null).request()).isSameAs(request);
        assertThat(broken.label()).isEqualTo("BROKEN");
    }
}
