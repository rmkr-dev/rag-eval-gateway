package io.github.rmkrdev.gateway.pii;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The detectors that ship with the gateway. Every pattern is compiled once, anchored on word
 * boundaries so it never matches inside a longer token, and numbers that carry a checksum are
 * only reported when the checksum holds.
 *
 * <table>
 *   <caption>Built-in detectors</caption>
 *   <tr><th>id</th><th>label</th><th>finds</th><th>guard</th></tr>
 *   <tr><td>credit-card</td><td>CARD</td><td>13 to 19 digits, spaces or dashes allowed</td><td>Luhn, first digit 2 to 6</td></tr>
 *   <tr><td>iban</td><td>IBAN</td><td>IBAN, compact or in groups of four</td><td>ISO 7064 mod 97</td></tr>
 *   <tr><td>in-aadhaar</td><td>AADHAAR</td><td>12 digits, first 2 to 9, in groups of four</td><td>Verhoeff</td></tr>
 *   <tr><td>us-ssn</td><td>SSN</td><td>{@code 123-45-6789}</td><td>no 000, 666 or 9xx area, no 00 group, no 0000 serial</td></tr>
 *   <tr><td>in-pan</td><td>PAN</td><td>{@code ABCPE1234F}</td><td>valid holder type in the fourth letter</td></tr>
 *   <tr><td>email</td><td>EMAIL</td><td>addresses with a dotted domain</td><td>top-level domain of letters</td></tr>
 *   <tr><td>ip-address</td><td>IP</td><td>IPv4 and IPv6, compressed or full</td><td>octets up to 255, at most eight IPv6 groups</td></tr>
 *   <tr><td>phone</td><td>PHONE</td><td>{@code +} international, Indian mobile, North American formatted</td><td>8 to 15 digits</td></tr>
 * </table>
 */
public final class BuiltInDetectors {

    private BuiltInDetectors() {
    }

    static final Pattern CARD = Pattern.compile("(?=\\d)(?<![\\w-])(?:\\d[ -]?+){12,18}\\d(?![\\w-])");

    static final Pattern IBAN = Pattern.compile(
            "(?=[A-Z])(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,4})?)(?![A-Za-z0-9])");

    static final Pattern AADHAAR = Pattern.compile("(?=[2-9])(?<![\\w-])[2-9]\\d{3}([ -]?)\\d{4}\\1\\d{4}(?![\\w-])");

    static final Pattern SSN = Pattern.compile(
            "(?=\\d)(?<![\\w-])(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}(?![\\w-])");

    static final Pattern PAN = Pattern.compile("(?=[A-Z])(?<![A-Za-z0-9])[A-Z]{3}[ABCFGHJLPT][A-Z]\\d{4}[A-Z](?![A-Za-z0-9])");

    static final Pattern EMAIL = Pattern.compile(
            "(?=[A-Za-z0-9._%+-])(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+"
                    + "[A-Za-z]{2,24}(?![A-Za-z0-9-])");

    static final Pattern IPV4 = Pattern.compile(
            "(?=\\d)(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
                    + "(?![\\w]|\\.\\d)");

    private static final String HEX4 = "[0-9A-Fa-f]{1,4}";

    static final Pattern IPV6 = Pattern.compile("(?=[0-9A-Fa-f:])(?<![\\w:.])(?:" + HEX4 + "(?::" + HEX4 + "){7}|(?:" + HEX4
            + "(?::" + HEX4 + "){0,6})?::(?:" + HEX4 + "(?::" + HEX4 + "){0,6})?)(?![\\w:]|\\.\\d)");

    static final Pattern PHONE = Pattern.compile("(?=[+(\\d])(?<![\\w+.-])(?:"
            + "\\+\\d{1,3}(?:[ .-]?\\(\\d{1,4}\\))?(?:[ .-]?+\\d++)++"
            + "|(?:0|91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}"
            + "|(?:\\([2-9]\\d{2}\\) ?|[2-9]\\d{2}[.-])\\d{3}[.-]\\d{4}"
            + ")(?![\\w]|[.-]\\d)");

    /** All built-in detectors, in the order overlapping matches are settled. */
    public static List<PiiDetector> all() {
        return List.of(
                new PatternDetector("credit-card", "CARD", CARD, BuiltInDetectors::validCard,
                        value -> Masking.keepLast(value, 4)),
                new PatternDetector("iban", "IBAN", IBAN, BuiltInDetectors::validIban, Masking::iban),
                new PatternDetector("in-aadhaar", "AADHAAR", AADHAAR, BuiltInDetectors::validAadhaar,
                        value -> Masking.keepLast(value, 4)),
                new PatternDetector("us-ssn", "SSN", SSN, null, value -> Masking.keepLast(value, 4)),
                new PatternDetector("in-pan", "PAN", PAN),
                new EmailDetector(),
                new IpAddressDetector(),
                new PatternDetector("phone", "PHONE", PHONE, BuiltInDetectors::validPhone,
                        value -> Masking.keepLast(value, 2)));
    }

    /**
     * Emails, matched outward from each {@code @} rather than tried at every position, which
     * keeps long prompts cheap.
     */
    static final class EmailDetector implements PiiDetector {

        @Override
        public String id() {
            return "email";
        }

        @Override
        public String label() {
            return "EMAIL";
        }

        @Override
        public void detect(String text, Sink sink) {
            int at = text.indexOf('@');
            if (at < 0) {
                return;
            }
            Matcher matcher = EMAIL.matcher(text).useTransparentBounds(true).useAnchoringBounds(false);
            int done = 0;
            while (at >= 0) {
                int start = at;
                while (start > done && at - start <= 64 && isLocal(text.charAt(start - 1))) {
                    start--;
                }
                if (start < at && at - start <= 64) {
                    matcher.region(start, text.length());
                    if (matcher.lookingAt()) {
                        sink.found(matcher.start(), matcher.end());
                        done = matcher.end();
                    }
                }
                at = text.indexOf('@', Math.max(at + 1, done));
            }
        }

        @Override
        public String mask(String value) {
            return Masking.email(value);
        }

        private static boolean isLocal(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '%' || c == '+' || c == '-';
        }
    }

    /** IPv4 and IPv6 under one id, since callers think of both as "an IP address". */
    static final class IpAddressDetector implements PiiDetector {

        private final PatternDetector v4 = new PatternDetector("ip-address", "IP", IPV4);
        private final PatternDetector v6 = new PatternDetector("ip-address", "IP", IPV6,
                BuiltInDetectors::validIpv6, null);

        @Override
        public String id() {
            return "ip-address";
        }

        @Override
        public String label() {
            return "IP";
        }

        @Override
        public void detect(String text, Sink sink) {
            if (text.indexOf('.') >= 0) {
                v4.detect(text, sink);
            }
            if (text.indexOf(':') >= 0) {
                v6.detect(text, sink);
            }
        }
    }

    static String digits(String value) {
        StringBuilder digits = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    static boolean validCard(String value) {
        String digits = digits(value);
        return digits.length() >= 13 && digits.length() <= 19
                && digits.charAt(0) >= '2' && digits.charAt(0) <= '6'
                && luhn(digits);
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }

    static boolean validIban(String value) {
        String iban = value.replace(" ", "");
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
        for (int i = 0; i < rearranged.length(); i++) {
            numeric.append(Character.digit(rearranged.charAt(i), 36));
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static final int[][] VERHOEFF_D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};

    private static final int[][] VERHOEFF_P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    static boolean verhoeff(String digits) {
        int check = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            check = VERHOEFF_D[check][VERHOEFF_P[i % 8][d]];
        }
        return check == 0;
    }

    static boolean validAadhaar(String value) {
        String digits = digits(value);
        return digits.length() == 12 && verhoeff(digits);
    }

    static boolean validPhone(String value) {
        int count = digits(value).length();
        return count >= 8 && count <= 15;
    }

    /** At most eight groups, at least two of them filled, and at least one digit, so {@code a::b} stays. */
    static boolean validIpv6(String value) {
        int filled = 0;
        boolean digit = false;
        for (String group : value.split(":", -1)) {
            if (!group.isEmpty()) {
                filled++;
            }
            for (int i = 0; i < group.length(); i++) {
                digit |= Character.isDigit(group.charAt(i));
            }
        }
        return filled >= 2 && filled <= 8 && digit;
    }
}
