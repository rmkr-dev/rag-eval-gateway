package io.github.rmkrdev.gateway.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** True positives and false-positive guards for every built-in detector, on its own. */
class BuiltInDetectorsTests {

    private static PiiDetector detector(String id) {
        return BuiltInDetectors.all().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> found(String id, String text) {
        List<String> values = new ArrayList<>();
        detector(id).detect(text, (start, end) -> values.add(text.substring(start, end)));
        return values;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "email       | write to a.b+tag@mail.example.co.in today | a.b+tag@mail.example.co.in",
            "email       | <Priya.R@example.com>                     | Priya.R@example.com",
            "credit-card | card 4111 1111 1111 1111 exp 12/27        | 4111 1111 1111 1111",
            "credit-card | 5500-0000-0000-0004                       | 5500-0000-0000-0004",
            "credit-card | amex 378282246310005.                     | 378282246310005",
            "iban        | pay DE89 3704 0044 0532 0130 00 now       | DE89 3704 0044 0532 0130 00",
            "iban        | GB82WEST12345698765432                    | GB82WEST12345698765432",
            "us-ssn      | SSN 123-45-6789                           | 123-45-6789",
            "in-pan      | PAN ABCPE1234F on file                    | ABCPE1234F",
            "in-aadhaar  | aadhaar 2345 6789 0124                    | 2345 6789 0124",
            "in-aadhaar  | 491827364507                              | 491827364507",
            "ip-address  | from 192.168.1.10, then                   | 192.168.1.10",
            "ip-address  | host 2001:db8::8a2e:370:7334 down         | 2001:db8::8a2e:370:7334",
            "ip-address  | 2001:0db8:0000:0000:0000:ff00:0042:8329   | 2001:0db8:0000:0000:0000:ff00:0042:8329",
            "ip-address  | link fe80::1.                             | fe80::1",
            "phone       | call +91 98765 43210                      | +91 98765 43210",
            "phone       | call +1 (415) 555-2671 now                | +1 (415) 555-2671",
            "phone       | mobile 9876543210                         | 9876543210",
            "phone       | mobile 098765-43210                       | 098765-43210",
            "phone       | office (415) 555-2671                     | (415) 555-2671",
            "phone       | office 415.555.2671                       | 415.555.2671"
    })
    void findsTheValue(String id, String text, String value) {
        assertThat(found(id, text)).containsExactly(value);
    }

    @Test
    void findsEveryEmailInALongText() {
        String text = "x@@y a@example.com,b.c@example.org " + "q".repeat(70) + "@example.net @example.com "
                + "words ".repeat(500) + "last@example.in";
        assertThat(found("email", text)).containsExactly("a@example.com", "b.c@example.org", "last@example.in");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "email       | user@localhost, @handle, foo@bar, a@b.c1",
            "credit-card | card 4111 1111 1111 1112",
            "credit-card | order 1234567890123456 and 9234567890123452",
            "credit-card | 4111111111111111999999",
            "iban        | DE89 3704 0044 0532 0130 01",
            "iban        | SKU AB12CDEFGHIJKLMNOP",
            "us-ssn      | 000-12-3456 666-12-3456 900-12-3456 123-00-4567 123-45-0000 123456789",
            "in-pan      | ABCDE1234F abcpe1234f ABCPE12345 XABCPE1234F",
            "in-aadhaar  | 2345 6789 0125 1234 5678 9012 2345-6789 0124",
            "ip-address  | 256.1.1.1 1.2.3 1.2.3.4.5 v1.2.3.4",
            "ip-address  | at 12:30:45 use std::vector and a::b, mac 00:1A:2B:3C:4D:5E",
            "phone       | on 2026-11-02 id 12345 ref 1234567890 build 1.2.3 +12",
            "phone       | 5551234567 and 98765432101234"
    })
    void leavesLookAlikesAlone(String id, String text) {
        assertThat(found(id, text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "5500000000000004", "378282246310005", "6011111111111117"})
    void luhnAcceptsTestCards(String card) {
        assertThat(BuiltInDetectors.luhn(card)).isTrue();
        assertThat(BuiltInDetectors.validCard(card)).isTrue();
    }

    @Test
    void cardsNeedAKnownFirstDigit() {
        assertThat(BuiltInDetectors.luhn("1111111111111117")).isTrue();
        assertThat(BuiltInDetectors.validCard("1111111111111117")).isFalse();
    }

    @Test
    void verhoeffRejectsEverySingleDigitChange() {
        String valid = "234567890124";
        assertThat(BuiltInDetectors.verhoeff(valid)).isTrue();
        for (int i = 0; i < valid.length(); i++) {
            for (char c = '0'; c <= '9'; c++) {
                if (c != valid.charAt(i)) {
                    String changed = valid.substring(0, i) + c + valid.substring(i + 1);
                    assertThat(BuiltInDetectors.verhoeff(changed)).as(changed).isFalse();
                }
            }
        }
    }

    @Test
    void masksKeepOnlyWhatIsSafeToShow() {
        assertThat(detector("credit-card").mask("4111 1111 1111 1111")).isEqualTo("**** **** **** 1111");
        assertThat(detector("email").mask("priya.r@example.com")).isEqualTo("p***@example.com");
        assertThat(detector("iban").mask("DE89 3704 0044 0532 0130 00")).isEqualTo("DE** **** **** **** **30 00");
        assertThat(detector("in-aadhaar").mask("2345 6789 0124")).isEqualTo("**** **** 0124");
        assertThat(detector("us-ssn").mask("123-45-6789")).isEqualTo("***-**-6789");
        assertThat(detector("phone").mask("+91 98765 43210")).isEqualTo("+** ***** ***10");
        assertThat(detector("ip-address").mask("10.1.2.3")).isEqualTo("**.*.*.*");
        assertThat(detector("in-pan").mask("ABCPE1234F")).isEqualTo("**********");
    }

    @Test
    void labelsAndIdsAreStable() {
        assertThat(BuiltInDetectors.all()).extracting(PiiDetector::id).containsExactly(
                "credit-card", "iban", "in-aadhaar", "us-ssn", "in-pan", "email", "ip-address", "phone");
        assertThat(BuiltInDetectors.all()).extracting(PiiDetector::label).containsExactly(
                "CARD", "IBAN", "AADHAAR", "SSN", "PAN", "EMAIL", "IP", "PHONE");
    }

    @Test
    void longInputStaysFast() {
        String text = "lorem ipsum 1234 dolor@ sit 10.0.0 amet ".repeat(5_000) + "x@example.com";
        PiiRedactor redactor = new PiiRedactor(BuiltInDetectors.all(), PiiRedactorTests.properties(PiiMode.REDACT));
        long started = System.nanoTime();
        assertThat(redactor.scrub(text)).endsWith("[EMAIL]");
        assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
    }
}
