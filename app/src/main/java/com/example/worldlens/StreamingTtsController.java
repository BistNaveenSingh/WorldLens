package com.example.worldlens;

import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.util.LinkedList;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamingTtsController {
    private static final String TAG = "STREAM_TTS";
    private TextToSpeech tts;
    private StringBuilder buffer;
    private Queue<String> chunkQueue;
    private boolean isSpeaking;
    private boolean isFinished;
    private int utteranceIdCounter;

    // Pattern for sentence boundaries or clauses (periods, question marks, exclamation marks, semicolons, commas if long enough)
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile("(?<=[.?!;:])\\s+|\\n+");

    public StreamingTtsController(TextToSpeech tts) {
        this.tts = tts;
        this.buffer = new StringBuilder();
        this.chunkQueue = new LinkedList<>();
        this.isSpeaking = false;
        this.isFinished = false;
        this.utteranceIdCounter = 0;
    }

    public void start() {
        Log.d(TAG, "first speech started (initialized)");
        buffer.setLength(0);
        chunkQueue.clear();
        isFinished = false;
        isSpeaking = false;
    }

    public void append(String text) {
        if (text == null || text.isEmpty()) return;
        buffer.append(text);
        processBuffer();
    }

    private void processBuffer() {
        if (buffer.length() == 0) return;

        // Try to find a sentence boundary
        Matcher matcher = BOUNDARY_PATTERN.matcher(buffer.toString());
        int lastBoundary = -1;
        while (matcher.find()) {
            lastBoundary = matcher.end();
        }

        // If no hard boundary, check word count fallback
        if (lastBoundary == -1) {
            String currentText = buffer.toString();
            String[] words = currentText.split("\\s+");
            if (words.length >= 8) { // 8 words threshold
                // Find the last space to avoid cutting a word in half
                lastBoundary = currentText.lastIndexOf(' ');
            }
        }

        if (lastBoundary > 0) {
            String chunkToSpeak = buffer.substring(0, lastBoundary).trim();
            buffer.delete(0, lastBoundary);
            if (!chunkToSpeak.isEmpty()) {
                queueChunk(chunkToSpeak);
            }
        }
    }

    private void queueChunk(String chunk) {
        Log.d(TAG, "first speech queued: " + chunk);
        String utteranceId = "STREAM_TTS_" + (utteranceIdCounter++);
        tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, utteranceId);
    }

    public void finish() {
        isFinished = true;
        String remainingText = buffer.toString().trim();
        if (!remainingText.isEmpty()) {
            queueChunk(remainingText);
        }
        buffer.setLength(0);
        Log.d(TAG, "speech complete (queued all)");
    }

    public void stop() {
        tts.stop();
        buffer.setLength(0);
        chunkQueue.clear();
        isFinished = true;
    }
}
