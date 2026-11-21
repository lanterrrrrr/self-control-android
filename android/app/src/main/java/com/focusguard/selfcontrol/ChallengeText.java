package com.focusguard.selfcontrol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class ChallengeText {
    record Quote(String text, int length, String source, String rights) {}

    record MathQuestion(String prompt, int answer) {
        boolean passes(String input) {
            if (input == null) {
                return false;
            }
            try {
                String value = Normalizer.normalize(input, Normalizer.Form.NFKC).trim();
                return Integer.parseInt(value) == answer;
            } catch (NumberFormatException error) {
                return false;
            }
        }
    }

    private ChallengeText() {}

    static List<Quote> loadQuotes(Reader input) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        List<Quote> quotes = new ArrayList<>();
        Map<Integer, Integer> lengthCounts = new HashMap<>();
        Set<String> seenText = new HashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            if (columns.length != 4 || columns[2].trim().isEmpty() || columns[3].trim().isEmpty()) {
                throw new IOException("Invalid quote record");
            }
            String normalized = significantText(columns[0]);
            int length = normalized.codePointCount(0, normalized.length());
            if (length == 0 || !Integer.toString(length).equals(columns[1])
                    || normalized.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    != Character.UnicodeScript.HAN) || !seenText.add(normalized)) {
                throw new IOException("Invalid quote length or duplicate text");
            }
            quotes.add(new Quote(columns[0], length, columns[2], columns[3]));
            lengthCounts.merge(length, 1, Integer::sum);
        }
        if (quotes.isEmpty() || lengthCounts.values().stream().anyMatch(count -> count < 4)) {
            throw new IOException("Quote lengths do not support three distinct alternatives");
        }
        return List.copyOf(quotes);
    }

    static List<Quote> typingQuotes(List<Quote> quotes, Quote spoken, Random random) {
        List<Quote> choices = new ArrayList<>();
        for (Quote quote : quotes) {
            if (quote.length() == spoken.length() && !quote.equals(spoken)) {
                choices.add(quote);
            }
        }
        Collections.shuffle(choices, random);
        return List.copyOf(choices.subList(0, 3));
    }

    static boolean voicePasses(String expectedText, String transcript) {
        int[] expected = significantText(expectedText).codePoints().toArray();
        int[] actual = significantText(transcript).codePoints().toArray();
        if (expected.length == 0 || actual.length == 0) {
            return false;
        }
        int maxEdits = expected.length * 3 / 10;
        if (Math.abs(expected.length - actual.length) > maxEdits) {
            return false;
        }
        return editDistance(expected, actual) <= maxEdits;
    }

    private static int editDistance(int[] expected, int[] actual) {
        int[] previous = new int[expected.length + 1];
        int[] current = new int[expected.length + 1];
        for (int index = 0; index < previous.length; index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= actual.length; row++) {
            current[0] = row;
            for (int column = 1; column <= expected.length; column++) {
                int substitution = previous[column - 1] + (actual[row - 1] == expected[column - 1] ? 0 : 1);
                current[column] = Math.min(substitution, 1 + Math.min(previous[column], current[column - 1]));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[expected.length];
    }

    static boolean typingPasses(String expectedText, String input) {
        String expected = significantText(expectedText);
        return !expected.isEmpty() && expected.equals(significantText(input));
    }

    private static String significantText(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().filter(ChallengeText::isSignificant).forEach(result::appendCodePoint);
        return result.toString();
    }

    private static boolean isSignificant(int codePoint) {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            return false;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> false;
            default -> true;
        };
    }

    static MathQuestion randomMath(Random random) {
        int left = 11 + random.nextInt(29);
        int right = 2 + random.nextInt(9);
        return switch (random.nextInt(3)) {
            case 0 -> new MathQuestion(left + " + " + right + " = ?", left + right);
            case 1 -> new MathQuestion(left + " − " + right + " = ?", left - right);
            default -> {
                int factor = 3 + random.nextInt(9);
                yield new MathQuestion(factor + " × " + right + " = ?", factor * right);
            }
        };
    }
}
