package com.example.worldlens.morse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Encodes text into International Morse Code symbols.
 * Independent of the output medium (audio tones, display, etc.).
 */
public final class MorseEncoder {

    private static final Map<Character, String> MORSE_TABLE = new HashMap<>();

    static {
        // Standard International Morse alphabet (A-Z)
        MORSE_TABLE.put('A', ".-");
        MORSE_TABLE.put('B', "-...");
        MORSE_TABLE.put('C', "-.-.");
        MORSE_TABLE.put('D', "-..");
        MORSE_TABLE.put('E', ".");
        MORSE_TABLE.put('F', "..-.");
        MORSE_TABLE.put('G', "--.");
        MORSE_TABLE.put('H', "....");
        MORSE_TABLE.put('I', "..");
        MORSE_TABLE.put('J', ".---");
        MORSE_TABLE.put('K', "-.-");
        MORSE_TABLE.put('L', ".-..");
        MORSE_TABLE.put('M', "--");
        MORSE_TABLE.put('N', "-.");
        MORSE_TABLE.put('O', "---");
        MORSE_TABLE.put('P', ".--.");
        MORSE_TABLE.put('Q', "--.-");
        MORSE_TABLE.put('R', ".-.");
        MORSE_TABLE.put('S', "...");
        MORSE_TABLE.put('T', "-");
        MORSE_TABLE.put('U', "..-");
        MORSE_TABLE.put('V', "...-");
        MORSE_TABLE.put('W', ".--");
        MORSE_TABLE.put('X', "-..-");
        MORSE_TABLE.put('Y', "-.--");
        MORSE_TABLE.put('Z', "--..");

        // Digits (0-9)
        MORSE_TABLE.put('0', "-----");
        MORSE_TABLE.put('1', ".----");
        MORSE_TABLE.put('2', "..---");
        MORSE_TABLE.put('3', "...--");
        MORSE_TABLE.put('4', "....-");
        MORSE_TABLE.put('5', ".....");
        MORSE_TABLE.put('6', "-....");
        MORSE_TABLE.put('7', "--...");
        MORSE_TABLE.put('8', "---..");
        MORSE_TABLE.put('9', "----.");
    }

    private MorseEncoder() {
        // Prevent instantiation
    }

    /**
     * Checks if a character has a valid Morse representation.
     */
    public static boolean isEncodable(char c) {
        return MORSE_TABLE.containsKey(Character.toUpperCase(c));
    }

    /**
     * Returns the Morse code string for a single character, or null if unsupported.
     */
    public static String getMorseForChar(char c) {
        return MORSE_TABLE.get(Character.toUpperCase(c));
    }

    /**
     * Converts a text string to standard human-readable Morse code string
     * with letters separated by single spaces and words separated by " / ".
     */
    public static String textToMorseString(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        List<List<String>> words = textToMorseWords(text);
        if (words.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < words.size(); w++) {
            List<String> wordCodes = words.get(w);
            for (int c = 0; c < wordCodes.size(); c++) {
                sb.append(wordCodes.get(c));
                if (c < wordCodes.size() - 1) {
                    sb.append(" ");
                }
            }
            if (w < words.size() - 1) {
                sb.append(" / ");
            }
        }
        return sb.toString();
    }

    /**
     * Splits input text into words, each containing a list of Morse symbol strings.
     * Treats whitespace and '/' as word boundaries.
     * Unsupported characters are safely ignored.
     *
     * @param text The input string.
     * @return List of words, each word being a list of Morse symbol strings (e.g. [".-", "-..."]).
     */
    public static List<List<String>> textToMorseWords(String text) {
        List<List<String>> words = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return words;
        }

        List<String> currentWord = new ArrayList<>();
        String upper = text.toUpperCase(Locale.ROOT);
        int len = upper.length();

        for (int i = 0; i < len; i++) {
            char ch = upper.charAt(i);

            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '/') {
                if (!currentWord.isEmpty()) {
                    words.add(new ArrayList<>(currentWord));
                    currentWord.clear();
                }
            } else if (isEncodable(ch)) {
                currentWord.add(getMorseForChar(ch));
            }
        }

        if (!currentWord.isEmpty()) {
            words.add(currentWord);
        }

        return words;
    }

    /**
     * Parses an actual Morse code string (e.g. "... --- ..." or ".-. . -.. / -.-. .- .-. ")
     * into a list of words, where each word contains a list of letter Morse symbol strings.
     * Word boundaries are defined by '/' and letter boundaries by whitespace.
     */
    public static List<List<String>> parseMorseCodeToWords(String morseCode) {
        List<List<String>> words = new ArrayList<>();
        if (morseCode == null || morseCode.trim().isEmpty()) {
            return words;
        }

        String[] rawWords = morseCode.trim().split("/");
        for (String rawWord : rawWords) {
            String trimmedWord = rawWord.trim();
            if (trimmedWord.isEmpty()) {
                continue;
            }

            String[] rawLetters = trimmedWord.split("\\s+");
            List<String> wordLetters = new ArrayList<>();
            for (String letter : rawLetters) {
                String clean = letter.replaceAll("[^.-]", "");
                if (!clean.isEmpty()) {
                    wordLetters.add(clean);
                }
            }
            if (!wordLetters.isEmpty()) {
                words.add(wordLetters);
            }
        }
        return words;
    }
}
