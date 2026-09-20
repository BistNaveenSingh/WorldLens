package com.example.worldlens.morse;

import org.junit.Test;

import static org.junit.Assert.*;

public class MorseResponseParserTest {

    @Test
    public void testParse_standardFormat() {
        String raw = "ANSWER:\n"
                + "The object appears to be a red backpack with two shoulder straps.\n\n"
                + "MORSE_SUMMARY:\n"
                + "Red backpack, two straps";

        MorseResponseParser.ParsedResponse result = MorseResponseParser.parse(raw);

        assertEquals("The object appears to be a red backpack with two shoulder straps.", result.getFullAnswer());
        assertEquals("Red backpack, two straps", result.getMorseSummaryText());
        assertEquals(MorseEncoder.textToMorseString("Red backpack, two straps"), result.getMorseCode());
        assertEquals(".-. . -.. / -... .- -.-. -.- .--. .- -.-. -.- / - .-- --- / ... - .-. .- .--. ...", result.getMorseCode());
        assertFalse(result.isFallback());
    }

    @Test
    public void testParse_noAnswerHeader() {
        String raw = "This is a ceramic coffee mug.\n\n"
                + "MORSE_SUMMARY:\n"
                + "Ceramic coffee mug";

        MorseResponseParser.ParsedResponse result = MorseResponseParser.parse(raw);

        assertEquals("This is a ceramic coffee mug.", result.getFullAnswer());
        assertEquals("Ceramic coffee mug", result.getMorseSummaryText());
        assertEquals("-.-. . .-. .- -- .. -.-. / -.-. --- ..-. ..-. . . / -- ..- --.", result.getMorseCode());
        assertFalse(result.isFallback());
    }

    @Test
    public void testParse_missingMorseSummaryTriggersFallback() {
        String raw = "ANSWER:\nThis appears to be a red backpack with two shoulder straps.";

        MorseResponseParser.ParsedResponse result = MorseResponseParser.parse(raw);

        assertEquals("This appears to be a red backpack with two shoulder straps.", result.getFullAnswer());
        assertTrue(result.isFallback());
        // Filler prefix "This appears to be a" is stripped
        assertTrue(result.getMorseSummaryText().toLowerCase().contains("red backpack"));
        assertTrue(result.getMorseSummaryText().split(" ").length <= 10);
        assertTrue(result.getMorseSummaryText().length() <= 60);
        assertFalse(result.getMorseCode().isEmpty());
    }

    @Test
    public void testParse_fillerPrefixRemoval() {
        String raw = "The object appears to be a ceramic coffee mug.";
        String fallback = MorseResponseParser.generateFallbackSummary(raw);

        assertEquals("Ceramic coffee mug", fallback);
        assertEquals("-.-. . .-. .- -- .. -.-. / -.-. --- ..-. ..-. . . / -- ..- --.", MorseEncoder.textToMorseString(fallback));
    }

    @Test
    public void testParse_laptopDeskFiller() {
        String raw = "The image shows a black laptop on a wooden desk.";
        String fallback = MorseResponseParser.generateFallbackSummary(raw);

        assertEquals("Black laptop on a wooden desk", fallback);
    }

    @Test
    public void testParse_markdownStrippedInMorseSummary() {
        String raw = "ANSWER:\nHere is the answer.\n\nMORSE_SUMMARY:\n**Red backpack**, `two straps`";

        MorseResponseParser.ParsedResponse result = MorseResponseParser.parse(raw);

        assertEquals("Red backpack, two straps", result.getMorseSummaryText());
        assertEquals(".-. . -.. / -... .- -.-. -.- .--. .- -.-. -.- / - .-- --- / ... - .-. .- .--. ...", result.getMorseCode());
    }

    @Test
    public void testParse_longSummaryTruncation() {
        String longSummary = "This is an extremely long morse summary that definitely exceeds the ten word maximum limit set by specification";
        String sanitized = MorseResponseParser.sanitizeMorseSummary(longSummary);

        assertTrue(sanitized.split(" ").length <= 10);
        assertTrue(sanitized.length() <= 60);
    }

    @Test
    public void testParse_emptyAndNull() {
        MorseResponseParser.ParsedResponse emptyResult = MorseResponseParser.parse("");
        assertEquals("", emptyResult.getFullAnswer());
        assertEquals("", emptyResult.getMorseSummaryText());
        assertEquals("", emptyResult.getMorseCode());

        MorseResponseParser.ParsedResponse nullResult = MorseResponseParser.parse(null);
        assertEquals("", nullResult.getFullAnswer());
        assertEquals("", nullResult.getMorseSummaryText());
        assertEquals("", nullResult.getMorseCode());
    }

    @Test
    public void testFormatPrompt_containsBothMarkers() {
        String prompt = MorseResponseParser.formatPrompt("What is this?");
        assertTrue(prompt.contains("What is this?"));
        assertTrue(prompt.contains("ANSWER:"));
        assertTrue(prompt.contains("MORSE_SUMMARY:"));
    }
}
