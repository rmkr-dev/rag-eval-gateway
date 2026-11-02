package io.github.rmkrdev.gateway.pii;

/** Masks for {@code mask} mode. Separators stay, letters and digits become {@code *}. */
public final class Masking {

    private Masking() {
    }

    /** Every letter and digit masked: {@code 10.1.2.3} becomes {@code **.*.*.*}. */
    public static String all(String value) {
        return keepLast(value, 0);
    }

    /** All but the last {@code keep} letters and digits masked: {@code **** **** **** 1111}. */
    public static String keepLast(String value, int keep) {
        char[] chars = value.toCharArray();
        int kept = 0;
        for (int i = chars.length - 1; i >= 0; i--) {
            if (Character.isLetterOrDigit(chars[i])) {
                if (kept < keep) {
                    kept++;
                } else {
                    chars[i] = '*';
                }
            }
        }
        return new String(chars);
    }

    /** First letter of the mailbox and the domain kept: {@code j***@example.com}. */
    public static String email(String value) {
        int at = value.lastIndexOf('@');
        if (at <= 0) {
            return all(value);
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    /** Country code and last four kept: {@code DE** **** **** **** **30 00}. */
    public static String iban(String value) {
        return value.substring(0, 2) + keepLast(value.substring(2), 4);
    }
}
