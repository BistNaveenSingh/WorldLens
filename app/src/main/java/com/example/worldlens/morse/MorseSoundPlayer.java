package com.example.worldlens.morse;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * High-fidelity, tone-based Morse Code Sound Player.
 *
 * Dynamically synthesizes and plays International Morse Code audio tones
 * calibrated to match the reference audio (morse.wav):
 * - 550 Hz pure sine wave
 * - 60 ms Dot, 180 ms Dash (20 WPM cadence)
 * - 60 ms Symbol gap, 180 ms Letter gap, 420 ms Word gap
 * - ~2 ms click-free linear envelope attack/release ramps
 * - Asynchronous playback via low-latency Android AudioTrack
 */
public class MorseSoundPlayer {

    private static final String TAG = "MORSE_SOUND";

    /**
     * Callback interface to notify the UI on main thread.
     */
    public interface PlaybackCallback {
        void onPlaybackStarted(String morseRepresentation, long totalDurationMs);
        void onPlaybackFinished();
        void onError(String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MorseAudioThread");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });

    private AudioTrack activeAudioTrack = null;
    private Future<?> activePlaybackTask = null;
    private volatile boolean isPlaying = false;
    private volatile boolean isCancelled = false;
    private PlaybackCallback activeCallback = null;

    // Precomputed PCM buffers for maximum performance and zero garbage collection during playback
    private final short[] dotPcm;
    private final short[] dashPcm;
    private final short[] symbolGapPcm;
    private final short[] letterGapPcm;
    private final short[] wordGapPcm;

    public MorseSoundPlayer(Context context) {
        // Pre-generate acoustic tone and silence buffers
        this.dotPcm = generateTonePcm(MorseTiming.DOT);
        this.dashPcm = generateTonePcm(MorseTiming.DASH);
        this.symbolGapPcm = new short[(int) (MorseTiming.SAMPLE_RATE * (MorseTiming.GAP_SYMBOL / 1000.0))];
        this.letterGapPcm = new short[(int) (MorseTiming.SAMPLE_RATE * (MorseTiming.GAP_LETTER / 1000.0))];
        this.wordGapPcm = new short[(int) (MorseTiming.SAMPLE_RATE * (MorseTiming.GAP_WORD / 1000.0))];
    }

    /**
     * Generates a 16-bit PCM mono sine wave tone with smooth attack and release ramps.
     */
    private static short[] generateTonePcm(long durationMs) {
        int numSamples = (int) (MorseTiming.SAMPLE_RATE * (durationMs / 1000.0));
        short[] pcm = new short[numSamples];
        double angularFreq = 2.0 * Math.PI * MorseTiming.FREQUENCY / MorseTiming.SAMPLE_RATE;
        double maxAmplitude = 0.90 * 32767.0; // 90% peak amplitude matching reference
        int rampSamples = Math.min(MorseTiming.RAMP_SAMPLES, numSamples / 4);

        for (int i = 0; i < numSamples; i++) {
            double sample = Math.sin(i * angularFreq);

            // Apply smooth linear envelope ramp to eliminate speaker clicks/pops
            double envelope = 1.0;
            if (i < rampSamples) {
                envelope = (double) i / rampSamples;
            } else if (i >= numSamples - rampSamples) {
                envelope = (double) (numSamples - 1 - i) / rampSamples;
            }

            pcm[i] = (short) Math.round(sample * maxAmplitude * envelope);
        }
        return pcm;
    }

    /**
     * Returns true if Morse audio playback is active.
     */
    public synchronized boolean isPlaying() {
        return isPlaying;
    }

    /**
     * Plays actual Morse Code string (dots and dashes) as audio tones with a completion callback.
     *
     * @param morseCode Actual Morse symbols (e.g. "... --- ...").
     * @param callback UI callback for lifecycle events.
     */
    public synchronized void playMorseCode(String morseCode, PlaybackCallback callback) {
        stop(); // Stop any active playback cleanly

        if (morseCode == null || morseCode.trim().isEmpty()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onError("No Morse code to play."));
            }
            return;
        }

        List<List<String>> words = MorseEncoder.parseMorseCodeToWords(morseCode);
        if (words.isEmpty()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onError("No playable Morse symbols found."));
            }
            return;
        }

        long estimatedDurationMs = calculateTotalDurationMs(words);

        Log.d(TAG, "Playing: " + morseCode.trim());

        isPlaying = true;
        isCancelled = false;
        activeCallback = callback;

        if (callback != null) {
            mainHandler.post(() -> callback.onPlaybackStarted(morseCode.trim(), estimatedDurationMs));
        }

        activePlaybackTask = audioExecutor.submit(() -> runAudioPlayback(words));
    }

    /**
     * Plays actual Morse Code with default logging and no UI callback.
     */
    public void playMorseCode(String morseCode) {
        playMorseCode(morseCode, null);
    }

    /**
     * Plays text as Morse Code audio tones with a completion callback.
     * Encodes text into Morse symbols and delegates to playMorseCode.
     *
     * @param text Input text to encode and play.
     * @param callback UI callback for lifecycle events.
     */
    public synchronized void playMorse(String text, PlaybackCallback callback) {
        String morseCode = MorseEncoder.textToMorseString(text);
        playMorseCode(morseCode, callback);
    }

    /**
     * Plays text with default logging and no UI callback.
     */
    public void playMorse(String text) {
        playMorse(text, null);
    }

    /**
     * The background audio playback routine feeding PCM tone blocks into AudioTrack.
     */
    private void runAudioPlayback(List<List<String>> words) {
        AudioTrack track = null;
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(
                    MorseTiming.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferSize = Math.max(minBufferSize, 8192);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(MorseTiming.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            synchronized (this) {
                if (isCancelled) {
                    track.release();
                    return;
                }
                activeAudioTrack = track;
            }

            track.play();

            int totalWords = words.size();
            for (int w = 0; w < totalWords && !isCancelled; w++) {
                List<String> word = words.get(w);
                boolean isLastWord = (w == totalWords - 1);
                int totalLetters = word.size();

                for (int l = 0; l < totalLetters && !isCancelled; l++) {
                    String letterCode = word.get(l);
                    boolean isLastLetter = (l == totalLetters - 1);
                    int totalSymbols = letterCode.length();

                    for (int s = 0; s < totalSymbols && !isCancelled; s++) {
                        char symbol = letterCode.charAt(s);
                        boolean isLastSymbol = (s == totalSymbols - 1);

                        // 1. Play Tone (Dot or Dash)
                        short[] tone = (symbol == '-') ? dashPcm : dotPcm;
                        writePcm(track, tone);

                        // 2. Play Gap after symbol if not the end of the letter
                        if (!isLastSymbol && !isCancelled) {
                            writePcm(track, symbolGapPcm);
                        }
                    }

                    // 3. Play Letter Gap if not the end of the word
                    if (!isLastLetter && !isCancelled) {
                        writePcm(track, letterGapPcm);
                    }
                }

                // 4. Play Word Gap if not the final word
                if (!isLastWord && !isCancelled) {
                    writePcm(track, wordGapPcm);
                }
            }

            // Let the trailing audio drain naturally if not cancelled
            if (!isCancelled && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during Morse audio playback: " + e.getMessage(), e);
        } finally {
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.stop();
                } catch (Exception ignored) {}
                try {
                    track.release();
                } catch (Exception ignored) {}
            }

            synchronized (this) {
                activeAudioTrack = null;
                isPlaying = false;
            }

            if (!isCancelled) {
                Log.d(TAG, "Playback complete");
                PlaybackCallback cb = activeCallback;
                activeCallback = null;
                if (cb != null) {
                    mainHandler.post(cb::onPlaybackFinished);
                }
            }
        }
    }

    /**
     * Writes short PCM samples to the AudioTrack with cancellation check.
     */
    private void writePcm(AudioTrack track, short[] pcm) {
        if (track == null || pcm == null || isCancelled) {
            return;
        }
        int offset = 0;
        while (offset < pcm.length && !isCancelled) {
            int written = track.write(pcm, offset, pcm.length - offset);
            if (written <= 0) {
                break;
            }
            offset += written;
        }
    }

    /**
     * Calculates estimated duration of Morse playback in milliseconds.
     */
    private static long calculateTotalDurationMs(List<List<String>> words) {
        long total = 0L;
        int totalWords = words.size();
        for (int w = 0; w < totalWords; w++) {
            List<String> word = words.get(w);
            boolean isLastWord = (w == totalWords - 1);
            int totalLetters = word.size();

            for (int l = 0; l < totalLetters; l++) {
                String letter = word.get(l);
                boolean isLastLetter = (l == totalLetters - 1);
                int totalSymbols = letter.length();

                for (int s = 0; s < totalSymbols; s++) {
                    char symbol = letter.charAt(s);
                    boolean isLastSymbol = (s == totalSymbols - 1);
                    total += (symbol == '-') ? MorseTiming.DASH : MorseTiming.DOT;
                    if (!isLastSymbol) {
                        total += MorseTiming.GAP_SYMBOL;
                    }
                }
                if (!isLastLetter) {
                    total += MorseTiming.GAP_LETTER;
                }
            }
            if (!isLastWord) {
                total += MorseTiming.GAP_WORD;
            }
        }
        return total;
    }

    /**
     * Immediately stops Morse audio playback and flushes audio buffers.
     */
    public synchronized void stop() {
        if (!isPlaying && !isCancelled) {
            return;
        }

        isCancelled = true;
        isPlaying = false;

        Log.d(TAG, "Playback stopped");

        if (activeAudioTrack != null) {
            try {
                activeAudioTrack.pause();
                activeAudioTrack.flush();
                activeAudioTrack.stop();
            } catch (Exception ignored) {}
        }

        if (activePlaybackTask != null) {
            activePlaybackTask.cancel(true);
            activePlaybackTask = null;
        }

        PlaybackCallback cb = activeCallback;
        activeCallback = null;
        if (cb != null) {
            mainHandler.post(cb::onPlaybackFinished);
        }
    }

    /**
     * Releases audio executor and resources. Should be called in onDestroy().
     */
    public synchronized void release() {
        stop();
        audioExecutor.shutdownNow();
    }
}
