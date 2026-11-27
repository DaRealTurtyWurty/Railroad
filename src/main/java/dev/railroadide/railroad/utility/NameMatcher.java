package dev.railroadide.railroad.utility;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Matches a typed pattern against a name, favouring camel-case humps and matches at word starts.
 */
public final class NameMatcher {
    private static final int MATCH = 1;
    private static final int EXACT_CASE = 1;
    private static final int WORD_START = 8;
    private static final int CONSECUTIVE = 5;
    private static final int LEADING = 10;
    private static final int PREFIX = 30;
    private static final int EQUAL = 100;

    private NameMatcher() {
    }

    /**
     * Matches every pattern character, in order, against the text.
     *
     * @param pattern typed pattern; an empty pattern matches everything with a zero score
     * @param text candidate text
     * @return best match found, or {@code null} when the text does not contain the pattern as a subsequence
     */
    public static @Nullable Match match(String pattern, String text) {
        return match(pattern, text, false);
    }

    /**
     * Matches and scores a name using the requested case policy.
     *
     * @param pattern typed pattern
     * @param text candidate name
     * @param caseSensitive whether each character's case must match
     * @return best match found, or {@code null} when the pattern is not a subsequence
     */
    public static @Nullable Match match(String pattern, String text, boolean caseSensitive) {
        if (pattern.isEmpty())
            return new Match(0, new int[0]);

        if (pattern.length() > text.length())
            return null;

        int[] humps = humpPositions(pattern, text, caseSensitive);
        if (humps == null) {
            humps = subsequencePositions(pattern, text, caseSensitive);
        }

        if (humps == null)
            return null;

        var best = new Match(score(pattern, text, humps), humps);
        int[] substring = substringPositions(pattern, text, caseSensitive);
        if (substring != null) {
            int substringScore = score(pattern, text, substring);
            if (substringScore > best.score()) {
                best = new Match(substringScore, substring);
            }
        }

        return best;
    }

    /**
     * Checks a case-insensitive subsequence without building match positions or a score.
     *
     * @param pattern typed pattern
     * @param text candidate name
     * @return whether the name contains all pattern characters in order
     */
    public static boolean matches(String pattern, String text) {
        return matches(pattern, text, 0);
    }

    /**
     * Checks a case-insensitive subsequence within the suffix of a qualified name.
     *
     * @param pattern typed pattern
     * @param text candidate name
     * @param start the inclusive start of the name to match
     * @return whether the suffix contains all pattern characters in order
     */
    public static boolean matches(String pattern, String text, int start) {
        int from = start;
        for (int index = 0; index < pattern.length(); index++) {
            int found = find(pattern.charAt(index), text, from, false);
            if (found < 0)
                return false;

            from = found + 1;
        }

        return true;
    }

    /**
     * Tests whether a character begins a word: the first character, an upper-case letter after a lower-case letter or
     * digit, a letter or digit after a separator, or a letter after a digit.
     *
     * @param text text containing the character
     * @param index index of the character
     * @return whether the character starts a word
     */
    public static boolean isWordStart(String text, int index) {
        if (index == 0)
            return true;

        char current = text.charAt(index);
        char previous = text.charAt(index - 1);
        if (!Character.isLetterOrDigit(current))
            return false;

        if (!Character.isLetterOrDigit(previous))
            return true;

        if (Character.isUpperCase(current) && !Character.isUpperCase(previous))
            return true;

        return Character.isLetter(current) && Character.isDigit(previous);
    }

    private static int @Nullable [] humpPositions(String pattern, String text, boolean caseSensitive) {
        int[] positions = new int[pattern.length()];
        int from = 0;
        for (int index = 0; index < pattern.length(); index++) {
            char wanted = pattern.charAt(index);
            int found = -1;
            if (index > 0 && from < text.length() && sameCharacter(wanted, text.charAt(from), caseSensitive)) {
                found = from;
            } else {
                for (int candidate = from; candidate < text.length(); candidate++) {
                    if (sameCharacter(wanted, text.charAt(candidate), caseSensitive) && isWordStart(text, candidate)) {
                        found = candidate;
                        break;
                    }
                }

                if (found < 0) {
                    found = find(wanted, text, from, caseSensitive);
                }
            }

            if (found < 0)
                return null;

            positions[index] = found;
            from = found + 1;
        }

        return positions;
    }

    private static int @Nullable [] subsequencePositions(String pattern, String text, boolean caseSensitive) {
        int[] positions = new int[pattern.length()];
        int from = 0;
        for (int index = 0; index < pattern.length(); index++) {
            int found = find(pattern.charAt(index), text, from, caseSensitive);
            if (found < 0)
                return null;

            positions[index] = found;
            from = found + 1;
        }

        return positions;
    }

    private static int @Nullable [] substringPositions(String pattern, String text, boolean caseSensitive) {
        int start = -1;
        for (int candidate = 0; candidate <= text.length() - pattern.length(); candidate++) {
            if (!text.regionMatches(!caseSensitive, candidate, pattern, 0, pattern.length()))
                continue;

            if (start < 0 || isWordStart(text, candidate)) {
                start = candidate;
            }

            if (isWordStart(text, candidate))
                break;
        }

        if (start < 0)
            return null;

        int[] positions = new int[pattern.length()];
        for (int index = 0; index < positions.length; index++) {
            positions[index] = start + index;
        }

        return positions;
    }

    private static int find(char wanted, String text, int start, boolean caseSensitive) {
        for (int index = start; index < text.length(); index++) {
            if (sameCharacter(wanted, text.charAt(index), caseSensitive))
                return index;
        }

        return -1;
    }

    private static boolean sameCharacter(char left, char right, boolean caseSensitive) {
        return left == right || !caseSensitive && Character.toLowerCase(Character.toUpperCase(left)) == Character
            .toLowerCase(Character.toUpperCase(right));
    }

    private static int score(String pattern, String text, int[] positions) {
        int score = 0;
        for (int index = 0; index < positions.length; index++) {
            int position = positions[index];
            score += MATCH;
            if (text.charAt(position) == pattern.charAt(index)) {
                score += EXACT_CASE;
            }

            if (isWordStart(text, position)) {
                score += WORD_START;
            }

            if (index > 0 && positions[index - 1] == position - 1) {
                score += CONSECUTIVE;
            }
        }

        if (positions[0] == 0) {
            score += LEADING;
        }

        if (text.equalsIgnoreCase(pattern)) {
            score += EQUAL;
        } else if (text.regionMatches(true, 0, pattern, 0, pattern.length())) {
            score += PREFIX;
        }

        return score - (text.length() - pattern.length()) / 4;
    }

    /**
     * Successful match of a pattern.
     *
     * @param score relative quality; higher is better
     * @param positions text indices matched by each pattern character, in ascending order
     */
    public record Match(int score, int[] positions) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Match match && score == match.score
                && Arrays.equals(positions, match.positions);
        }

        @Override
        public int hashCode() {
            return 31 * score + Arrays.hashCode(positions);
        }

        @Override
        public String toString() {
            return "Match[score=" + score + ", positions=" + Arrays.toString(positions) + "]";
        }
    }
}
