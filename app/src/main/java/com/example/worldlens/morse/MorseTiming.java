package com.example.worldlens.morse;

/**
 * Timing and acoustic constants for the Morse Code Audio Player,
 * calibrated to match the reference audio (morse.wav) at 20 WPM.
 */
public final class MorseTiming {

    private MorseTiming() {
        // Prevent instantiation
    }

    /**
     * Audio sample rate in Hz (16 kHz for crisp sine synthesis and low latency).
     */
    public static final int SAMPLE_RATE = 16000;

    /**
     * Dominant tone frequency in Hz (calibrated directly from morse.wav: 550 Hz).
     */
    public static final double FREQUENCY = 550.0;

    /**
     * Standard Morse time unit duration (60 ms, standard 20 WPM cadence).
     */
    public static final long UNIT = 60L;

    /**
     * Duration of a dot (.) = 60 ms (1 unit).
     */
    public static final long DOT = UNIT;

    /**
     * Duration of a dash (-) = 180 ms (3 units).
     */
    public static final long DASH = UNIT * 3;

    /**
     * Silence gap between dots and dashes of the same letter = 60 ms (1 unit).
     */
    public static final long GAP_SYMBOL = UNIT;

    /**
     * Silence gap between letters within the same word = 180 ms (3 units).
     */
    public static final long GAP_LETTER = UNIT * 3;

    /**
     * Silence gap between words = 420 ms (7 units).
     */
    public static final long GAP_WORD = UNIT * 7;

    /**
     * Linear/cosine envelope ramp duration in milliseconds (~2 ms) to prevent speaker clicks.
     */
    public static final long RAMP_MS = 2L;

    /**
     * Number of audio samples for the 2 ms envelope ramp.
     */
    public static final int RAMP_SAMPLES = (int) (SAMPLE_RATE * (RAMP_MS / 1000.0));
}
