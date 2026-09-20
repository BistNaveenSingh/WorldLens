package com.example.worldlens.morse;

import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gemma responses into a full answer and a concise Morse summary.
 * Also provides prompt formatting and safe local fallback compression.
 */
public class MorseResponseParser {

    private static final String TAG = "WORLDLENS";

    private static final int PREFERRED_MAX_WORDS = 8;
    private static final int HARD_MAX_WORDS = 10;
    private static final int HARD_MAX_CHARS = 60;

    // Pattern to identify the Morse summary header (e.g., "MORSE_SUMMARY:", "MORSE SUMMARY:")
    private static final Pattern MORSE_HEADER_PATTERN = Pattern.compile(
            "(?i)\\bMORSE[ _]SUMMARY\\s*:\\s*"
    );

    // Pattern to identify the Answer header (e.g., "ANSWER:")
    private static final Pattern ANSWER_HEADER_PATTERN = Pattern.compile(
            "(?i)^\\s*ANSWER\\s*:\\s*"
    );

    // Common filler prefixes to eliminate when synthesizing a fallback summary
    private static final Pattern[] FILLER_PREFIXES = new Pattern[]{
            Pattern.compile("(?i)^this appears to be (a|an)?\\s*"),
            Pattern.compile("(?i)^the object appears to be (a|an)?\\s*"),
            Pattern.compile("(?i)^it appears to be (a|an)?\\s*"),
            Pattern.compile("(?i)^the image shows (a|an)?\\s*"),
            Pattern.compile("(?i)^this image shows (a|an)?\\s*"),
            Pattern.compile("(?i)^the photo shows (a|an)?\\s*"),
            Pattern.compile("(?i)^based on the image,\\s*"),
            Pattern.compile("(?i)^the object is (a|an)?\\s*"),
            Pattern.compile("(?i)^it looks like (a|an)?\\s*"),
            Pattern.compile("(?i)^it seems to be (a|an)?\\s*"),
            Pattern.compile("(?i)^i can see (a|an)?\\s*"),
            Pattern.compile("(?i)^there is (a|an)?\\s*"),
            Pattern.compile("(?i)^this is (a|an)?\\s*"),
            Pattern.compile("(?i)^it is (a|an)?\\s*")
    };

    public static class ParsedResponse {
        private final String fullAnswer;
        private final String morseSummaryText;
        private final String morseCode;
        private final boolean isFallback;

        public ParsedResponse(String fullAnswer, String morseSummaryText, String morseCode, boolean isFallback) {
            this.fullAnswer = fullAnswer != null ? fullAnswer.trim() : "";
            this.morseSummaryText = morseSummaryText != null ? morseSummaryText.trim() : "";
            this.morseCode = morseCode != null ? morseCode.trim() : "";
            this.isFallback = isFallback;
        }

        public String getFullAnswer() {
            return fullAnswer;
        }

        public String getMorseSummaryText() {
            return morseSummaryText;
        }

        public String getMorseSummary() {
            return morseSummaryText;
        }

        public String getMorseCode() {
            return morseCode;
        }

        public boolean isFallback() {
            return isFallback;
        }
    }

    /**
     * Formats the Gemma prompt so the model returns both ANSWER and MORSE_SUMMARY sections.
     */
    public static String formatPrompt(String userQuestion) {
        String cleanQuestion = userQuestion != null ? userQuestion.trim() : "";
        return cleanQuestion + "\n\n"
                + "Respond in this exact format:\n"
                + "ANSWER:\n"
                + "<helpful, complete answer in English>\n\n"
                + "MORSE_SUMMARY:\n"
                + "<concise 3-8 word English semantic summary, max 10 words, max 60 chars, no markdown>";
    }

    /**
     * Safely extracts the Full Answer and Morse Summary from the raw Gemma response,
     * and programmatically generates the Morse Code (dots and dashes) using MorseEncoder.
     * Never crashes. Falls back gracefully to local condensation if parsing fails.
     */
    public static ParsedResponse parse(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return new ParsedResponse("", "", "", true);
        }

        String text = rawResponse.trim();
        Matcher morseMatcher = MORSE_HEADER_PATTERN.matcher(text);

        if (morseMatcher.find()) {
            int morseIndex = morseMatcher.start();
            int morseContentStart = morseMatcher.end();

            String answerPart = text.substring(0, morseIndex).trim();
            String morsePart = text.substring(morseContentStart).trim();

            // Strip leading ANSWER: marker from the answer section
            Matcher answerMatcher = ANSWER_HEADER_PATTERN.matcher(answerPart);
            if (answerMatcher.find()) {
                answerPart = answerPart.substring(answerMatcher.end()).trim();
            }

            // Sanitize Morse Summary
            String sanitizedMorse = sanitizeMorseSummary(morsePart);

            // If parsed summary is empty, generate fallback from the answer
            if (sanitizedMorse.isEmpty()) {
                logWarning("MORSE_SUMMARY section was empty. Generating safe fallback.");
                sanitizedMorse = generateFallbackSummary(answerPart);
                String morseCode = MorseEncoder.textToMorseString(sanitizedMorse);
                return new ParsedResponse(answerPart, sanitizedMorse, morseCode, true);
            }

            String morseCode = MorseEncoder.textToMorseString(sanitizedMorse);
            return new ParsedResponse(answerPart, sanitizedMorse, morseCode, false);
        }

        // Fallback: MORSE_SUMMARY header not found in the response
        logWarning("Failed to parse MORSE_SUMMARY from AI response. Using safe fallback.");
        String fullAnswer = text;
        Matcher answerMatcher = ANSWER_HEADER_PATTERN.matcher(fullAnswer);
        if (answerMatcher.find()) {
            fullAnswer = fullAnswer.substring(answerMatcher.end()).trim();
        }

        String fallbackSummary = generateFallbackSummary(fullAnswer);
        String morseCode = MorseEncoder.textToMorseString(fallbackSummary);
        return new ParsedResponse(fullAnswer, fallbackSummary, morseCode, true);
    }

    private static void logWarning(String message) {
        try {
            Log.w(TAG, message);
        } catch (Throwable ignored) {
            // Unit test environment fallback
        }
    }

    /**
     * Sanitizes and enforces length/character constraints on a Morse summary:
     * - Removes markdown (*, _, #, `, ~, >)
     * - Removes unwanted punctuation
     * - Limits to at most HARD_MAX_WORDS (10) words and HARD_MAX_CHARS (60) characters
     */
    public static String sanitizeMorseSummary(String text) {
        if (text == null) {
            return "";
        }

        // Take only the first line if multiple lines are produced
        String line = text.split("\n")[0].trim();

        // Strip markdown formatting characters
        line = line.replaceAll("[*#_`~>\\[\\]()]", "");

        // Replace multiple whitespace with a single space
        line = line.replaceAll("\\s+", " ").trim();

        // Enforce maximum words (prefer <= 8, hard max 10)
        String[] words = line.split(" ");
        if (words.length > HARD_MAX_WORDS) {
            StringBuilder sb = new StringBuilder();
            int count = Math.min(words.length, PREFERRED_MAX_WORDS);
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(" ");
                sb.append(words[i]);
            }
            line = sb.toString().trim();
        }

        // Enforce maximum character length (60 chars)
        if (line.length() > HARD_MAX_CHARS) {
            int cutIndex = line.lastIndexOf(' ', HARD_MAX_CHARS);
            if (cutIndex > 20) {
                line = line.substring(0, cutIndex).trim();
            } else {
                line = line.substring(0, HARD_MAX_CHARS).trim();
            }
        }

        // Remove trailing punctuation like dots or colons
        line = line.replaceAll("[,.;:!?-]+$", "").trim();

        return line;
    }

    /**
     * Synthesizes a concise, semantic Morse summary from a full answer:
     * - Removes conversational filler prefixes
     * - Isolates the primary clause
     * - Removes markdown and punctuation
     * - Bounds to 3-8 words (hard max 10 words, <= 60 characters)
     */
    public static String generateFallbackSummary(String fullAnswer) {
        if (fullAnswer == null || fullAnswer.trim().isEmpty()) {
            return "Object detected";
        }

        // 1. Take first sentence or clause
        String cleaned = fullAnswer.split("\n")[0].trim();
        int dotIndex = cleaned.indexOf('.');
        if (dotIndex > 0) {
            cleaned = cleaned.substring(0, dotIndex).trim();
        }

        // 2. Strip markdown
        cleaned = cleaned.replaceAll("[*#_`~>\\[\\]()]", "");

        // 3. Remove conversational filler prefixes iteratively
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Pattern pattern : FILLER_PREFIXES) {
                Matcher m = pattern.matcher(cleaned);
                if (m.find()) {
                    cleaned = cleaned.substring(m.end()).trim();
                    changed = true;
                    break;
                }
            }
        }

        // 4. Clean extra spaces and punctuation
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        cleaned = sanitizeMorseSummary(cleaned);

        if (cleaned.isEmpty()) {
            return "Object detected";
        }

        // Ensure first letter is capitalized
        if (Character.isLowerCase(cleaned.charAt(0))) {
            cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        }

        return cleaned;
    }
}
