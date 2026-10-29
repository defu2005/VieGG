package com.rentgg.gameaccount.application;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Random game-account passwords for the owner to set on the publisher's side. Look-alike characters (0/O, 1/l/I) are
 * left out so the owner can type it by hand; the symbol set is small because some games reject unusual symbols.
 */
final class PasswordGenerator {

    static final int LENGTH = 16;

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "@#$%&*!?";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {}

    /** {@link #LENGTH} characters with at least two upper, two lower, two digits and one symbol. */
    static String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);
        pick(chars, UPPER, 2);
        pick(chars, LOWER, 2);
        pick(chars, DIGITS, 2);
        pick(chars, SYMBOLS, 1);
        pick(chars, ALL, LENGTH - chars.size());
        Collections.shuffle(chars, RANDOM);
        StringBuilder sb = new StringBuilder(LENGTH);
        chars.forEach(sb::append);
        return sb.toString();
    }

    private static void pick(List<Character> out, String from, int n) {
        for (int i = 0; i < n; i++) {
            out.add(from.charAt(RANDOM.nextInt(from.length())));
        }
    }
}
