package com.example.worldlens.model;

/**
 * Encapsulates a single conversational turn in World Lens.
 * Holds:
 * 1. Full Answer (comprehensive English response from Gemma)
 * 2. Morse Summary Text (concise English summary)
 * 3. Morse Code (actual dots and dashes programmatically generated from the summary)
 */
public class WorldLensTurn {
    private final int turnNumber;
    private final String question;
    private final String fullAnswer;
    private final String morseSummaryText;
    private final String morseCode;
    private final long timestamp;

    public WorldLensTurn(int turnNumber, String question, String fullAnswer, String morseSummaryText, String morseCode) {
        this.turnNumber = turnNumber;
        this.question = question != null ? question.trim() : "";
        this.fullAnswer = fullAnswer != null ? fullAnswer.trim() : "";
        this.morseSummaryText = morseSummaryText != null ? morseSummaryText.trim() : "";
        this.morseCode = morseCode != null ? morseCode.trim() : "";
        this.timestamp = System.currentTimeMillis();
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public String getQuestion() {
        return question;
    }

    public String getFullAnswer() {
        return fullAnswer;
    }

    public String getMorseSummaryText() {
        return morseSummaryText;
    }

    /**
     * Backward-compatible alias for getMorseSummaryText().
     */
    public String getMorseSummary() {
        return morseSummaryText;
    }

    public String getMorseCode() {
        return morseCode;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
