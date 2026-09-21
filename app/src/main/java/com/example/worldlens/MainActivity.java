package com.example.worldlens;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.example.worldlens.morse.MorseEncoder;
import com.example.worldlens.morse.MorseSoundPlayer;
import com.example.worldlens.ui.RecordingWaveformView;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier;
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifierResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public enum UiState {
        INITIALIZING,
        READY,
        LISTENING,
        PROCESSING,
        STREAMING,
        SPEAKING,
        MORSE_PLAYING,
        ERROR
    }

    private UiState currentUiState = UiState.INITIALIZING;

    private ImageClassifier imageClassifier;
    private PreviewView viewFinder;
    private ExecutorService cameraExecutor;

    // Header & Live Status
    private Button newChatTopButton;
    private TextView liveStatusIndicator;
    private ImageButton settingsButton;

    // Loading & Error states
    private View loadingStateContainer;
    private ProgressBar loadingProgressBar;
    private View errorStateContainer;
    private TextView errorText;
    private Button retryButton;

    // Response Preview Card
    private View responsePreviewCard;
    private TextView resultText;
    private TextView streamingIndicator;
    private TextView speakingIndicator;
    private TextView openChatFromPreview;

    // Bottom Deck & Composer
    private View normalComposerLayout;
    private EditText questionInput;
    private ImageButton sendQuestionButton;
    private View listeningComposerLayout;
    private RecordingWaveformView waveformView;
    private Button playMorseButton;
    private Button stopMorseButton;
    private ImageButton micButton;
    private Button moreActionsButton;

    // Chat Panel
    private View chatPanelContainer;
    private ImageButton closeChatButton;
    private Button newChatPanelButton;
    private NestedScrollView chatScrollView;
    private LinearLayout chatMessagesLayout;
    private EditText chatQuestionInput;
    private ImageButton chatMicButton;
    private ImageButton chatSendButton;

    // Conversation History Model
    public static class ChatTurn {
        public final String question;
        public String answer;
        public String morseSummary;
        public String morseCode;

        public ChatTurn(String question, String answer, String morseSummary, String morseCode) {
            this.question = question;
            this.answer = answer;
            this.morseSummary = morseSummary;
            this.morseCode = morseCode;
        }
    }
    private final List<ChatTurn> conversationHistory = new ArrayList<>();

    // Streaming Dots Animation Handler
    private final Handler streamingDotsHandler = new Handler(Looper.getMainLooper());
    private int streamingDotCount = 0;
    private final Runnable streamingDotsRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentUiState == UiState.STREAMING || currentUiState == UiState.PROCESSING) {
                streamingDotCount = (streamingDotCount % 3) + 1;
                StringBuilder sb = new StringBuilder("  ");
                for (int i = 0; i < streamingDotCount; i++) sb.append("•");
                if (streamingIndicator != null) {
                    streamingIndicator.setText(sb.toString());
                    streamingIndicator.setVisibility(View.VISIBLE);
                }
                streamingDotsHandler.postDelayed(this, 350);
            } else {
                if (streamingIndicator != null) streamingIndicator.setVisibility(View.GONE);
            }
        }
    };

    // Local Gemma VLM engine, conversation session, and background executor
    private Engine gemmaEngine;
    private Conversation gemmaConversation;
    private boolean isFirstTurnOfScan = true;
    private volatile boolean isInferenceRunning = false;
    private ExecutorService vlmExecutor;
    private volatile boolean isGemmaLoading = false;
    private volatile boolean isGemmaReady = false;
    private File gemmaModelFile = null;

    // Vision Acceleration Backend (GPU default with automatic CPU fallback)
    private Backend visionBackend = new Backend.GPU();

    private boolean isCustomQuestionAsked = false;

    // Offline Speech Recognition
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;

    // Text to Speech
    private TextToSpeech textToSpeech;
    private boolean isTtsReady = false;
    private boolean isTtsSpeaking = false;
    private StreamingTtsController streamingTtsController;

    // Morse Audio Player
    private MorseSoundPlayer morseSoundPlayer;

    // State Variables
    private String lastAskedQuestion = "";
    private String currentFullAnswer = "";
    private String currentMorseSummary = "";
    private String currentMorseCode = "";

    // Handles the camera permission request popup
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
                    showErrorState("Camera permission denied.");
                }
            });

    // Handles microphone permission request for offline voice input
    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startListening();
                } else {
                    Toast.makeText(this, "Microphone permission is required for voice input.", Toast.LENGTH_LONG).show();
                    if (resultText != null) {
                        resultText.setText("Microphone permission denied.");
                    }
                }
            });

    // SAF Document Picker to import .litertlm model from Downloads folder into app-private storage
    private final ActivityResultLauncher<String[]> modelPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importModelFromUri(uri);
                } else {
                    Toast.makeText(this, "No model file selected.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind UI Elements
        viewFinder = findViewById(R.id.viewFinder);
        newChatTopButton = findViewById(R.id.newChatTopButton);
        liveStatusIndicator = findViewById(R.id.liveStatusIndicator);
        settingsButton = findViewById(R.id.settingsButton);

        loadingStateContainer = findViewById(R.id.loadingStateContainer);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        errorStateContainer = findViewById(R.id.errorStateContainer);
        errorText = findViewById(R.id.errorText);
        retryButton = findViewById(R.id.retryButton);

        responsePreviewCard = findViewById(R.id.responsePreviewCard);
        resultText = findViewById(R.id.resultText);
        streamingIndicator = findViewById(R.id.streamingIndicator);
        speakingIndicator = findViewById(R.id.speakingIndicator);
        openChatFromPreview = findViewById(R.id.openChatFromPreview);

        normalComposerLayout = findViewById(R.id.normalComposerLayout);
        questionInput = findViewById(R.id.questionInput);
        sendQuestionButton = findViewById(R.id.sendQuestionButton);
        listeningComposerLayout = findViewById(R.id.listeningComposerLayout);
        waveformView = findViewById(R.id.waveformView);

        playMorseButton = findViewById(R.id.playMorseButton);
        stopMorseButton = findViewById(R.id.stopMorseButton);
        micButton = findViewById(R.id.micButton);
        moreActionsButton = findViewById(R.id.moreActionsButton);

        // Bind Chat Panel Views
        chatPanelContainer = findViewById(R.id.chatPanelContainer);
        closeChatButton = findViewById(R.id.closeChatButton);
        newChatPanelButton = findViewById(R.id.newChatPanelButton);
        chatScrollView = findViewById(R.id.chatScrollView);
        chatMessagesLayout = findViewById(R.id.chatMessagesLayout);
        chatQuestionInput = findViewById(R.id.chatQuestionInput);
        chatMicButton = findViewById(R.id.chatMicButton);
        chatSendButton = findViewById(R.id.chatSendButton);

        // Modern OnBackPressedDispatcher handling (migrated from deprecated onBackPressed)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (chatPanelContainer != null && chatPanelContainer.getVisibility() == View.VISIBLE) {
                    closeChatPanel();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // Initialize Morse Audio Player and Text-to-Speech
        morseSoundPlayer = new MorseSoundPlayer(this);
        initTextToSpeech();

        // Background threads
        cameraExecutor = Executors.newSingleThreadExecutor();
        vlmExecutor = Executors.newSingleThreadExecutor();

        // Set initial state
        applyUiState(UiState.INITIALIZING);

        // Initialize UI Button Listeners
        setupUIListeners();

        // 1. Initialize local EfficientNet model
        setupLocalAI();

        // 2. Initialize local Gemma-4-E2B-it VLM on background thread
        initGemmaVlm();

        // 3. Check and request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    private void setupUIListeners() {
        if (playMorseButton != null) playMorseButton.setOnClickListener(v -> playMorseCode());
        if (stopMorseButton != null) stopMorseButton.setOnClickListener(v -> stopMorseCode());

        // Wireframe Top Header: New Chat & Settings
        if (newChatTopButton != null) newChatTopButton.setOnClickListener(v -> resetConversation());
        if (newChatPanelButton != null) newChatPanelButton.setOnClickListener(v -> resetConversation());
        if (settingsButton != null) settingsButton.setOnClickListener(v -> showSettingsDialog());

        // Wireframe Quick Actions & More Dropdown
        if (moreActionsButton != null) {
            moreActionsButton.setOnClickListener(v -> showMoreActionsMenu(moreActionsButton));
        }

        // Conversational Preview Card -> Open Chat
        if (responsePreviewCard != null) {
            responsePreviewCard.setOnClickListener(v -> openChatPanel());
        }
        if (openChatFromPreview != null) {
            openChatFromPreview.setOnClickListener(v -> openChatPanel());
        }
        if (closeChatButton != null) {
            closeChatButton.setOnClickListener(v -> closeChatPanel());
        }

        // Primary Conversational Composer Send Buttons
        if (sendQuestionButton != null) {
            sendQuestionButton.setOnClickListener(v -> submitQuestion(questionInput != null ? questionInput.getText().toString() : ""));
        }
        if (chatSendButton != null) {
            chatSendButton.setOnClickListener(v -> submitQuestion(chatQuestionInput != null ? chatQuestionInput.getText().toString() : ""));
        }

        // IME Action Send on Enter Key for Main Composer
        if (questionInput != null) {
            questionInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitQuestion(questionInput.getText().toString());
                    return true;
                }
                return false;
            });
        }

        // IME Action Send on Enter Key for Chat Panel Composer
        if (chatQuestionInput != null) {
            chatQuestionInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitQuestion(chatQuestionInput.getText().toString());
                    return true;
                }
                return false;
            });
        }

        // Retry Button
        if (retryButton != null) {
            retryButton.setOnClickListener(v -> {
                if (errorStateContainer != null) errorStateContainer.setVisibility(View.GONE);
                submitQuestion(lastAskedQuestion);
            });
        }

        // Press-and-Hold Microphone Interaction with Touch Scaling Animation
        View.OnTouchListener micTouchListener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (isInferenceRunning) {
                        Toast.makeText(this, "Thinking... please wait for current answer.", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start();
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startListening();
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    // Maintain recording state
                    return true;

                case MotionEvent.ACTION_UP:
                    v.performClick();
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                    if (isListening) {
                        stopListening();
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                    if (isListening) {
                        stopListening();
                    }
                    return true;
            }
            return false;
        };

        if (micButton != null) micButton.setOnTouchListener(micTouchListener);
        if (chatMicButton != null) chatMicButton.setOnTouchListener(micTouchListener);
    }

    public void applyUiState(UiState state) {
        currentUiState = state;
        runOnUiThread(() -> {
            switch (state) {
                case INITIALIZING:
                    if (loadingStateContainer != null) loadingStateContainer.setVisibility(View.VISIBLE);
                    if (liveStatusIndicator != null) {
                        liveStatusIndicator.setText("PREPARING");
                        liveStatusIndicator.setTextColor(ContextCompat.getColor(this, R.color.wl_text_secondary));
                    }
                    if (sendQuestionButton != null) sendQuestionButton.setEnabled(false);
                    if (chatSendButton != null) chatSendButton.setEnabled(false);
                    stopStreamingAnimation();
                    break;

                case READY:
                    if (loadingStateContainer != null) loadingStateContainer.setVisibility(View.GONE);
                    if (errorStateContainer != null) errorStateContainer.setVisibility(View.GONE);
                    if (liveStatusIndicator != null) {
                        liveStatusIndicator.setText("● LIVE");
                        liveStatusIndicator.setTextColor(ContextCompat.getColor(this, R.color.ink_black));
                    }
                    if (normalComposerLayout != null) normalComposerLayout.setVisibility(View.VISIBLE);
                    if (listeningComposerLayout != null) listeningComposerLayout.setVisibility(View.GONE);
                    if (waveformView != null) waveformView.stopListening();
                    if (sendQuestionButton != null) sendQuestionButton.setEnabled(true);
                    if (chatSendButton != null) chatSendButton.setEnabled(true);
                    if (speakingIndicator != null) speakingIndicator.setVisibility(View.GONE);
                    if (playMorseButton != null) playMorseButton.setVisibility(View.VISIBLE);
                    if (stopMorseButton != null) stopMorseButton.setVisibility(View.GONE);
                    stopStreamingAnimation();
                    break;

                case LISTENING:
                    if (normalComposerLayout != null) normalComposerLayout.setVisibility(View.GONE);
                    if (listeningComposerLayout != null) listeningComposerLayout.setVisibility(View.VISIBLE);
                    if (waveformView != null) waveformView.startListening();
                    if (sendQuestionButton != null) sendQuestionButton.setEnabled(false);
                    if (chatSendButton != null) chatSendButton.setEnabled(false);
                    stopStreamingAnimation();
                    break;

                case PROCESSING:
                    if (normalComposerLayout != null) normalComposerLayout.setVisibility(View.VISIBLE);
                    if (listeningComposerLayout != null) listeningComposerLayout.setVisibility(View.GONE);
                    if (waveformView != null) waveformView.stopListening();
                    if (loadingStateContainer != null) loadingStateContainer.setVisibility(View.GONE);
                    if (errorStateContainer != null) errorStateContainer.setVisibility(View.GONE);
                    if (resultText != null) resultText.setText("Thinking...");
                    if (sendQuestionButton != null) sendQuestionButton.setEnabled(false);
                    if (chatSendButton != null) chatSendButton.setEnabled(false);
                    startStreamingAnimation();
                    break;

                case STREAMING:
                    if (sendQuestionButton != null) sendQuestionButton.setEnabled(false);
                    if (chatSendButton != null) chatSendButton.setEnabled(false);
                    if (speakingIndicator != null) {
                        speakingIndicator.setVisibility(isTtsSpeaking ? View.VISIBLE : View.GONE);
                    }
                    startStreamingAnimation();
                    break;

                case SPEAKING:
                    if (speakingIndicator != null) speakingIndicator.setVisibility(View.VISIBLE);
                    break;

                case MORSE_PLAYING:
                    if (playMorseButton != null) playMorseButton.setVisibility(View.GONE);
                    if (stopMorseButton != null) stopMorseButton.setVisibility(View.VISIBLE);
                    break;

                case ERROR:
                    if (errorStateContainer != null) errorStateContainer.setVisibility(View.VISIBLE);
                    if (normalComposerLayout != null) normalComposerLayout.setVisibility(View.VISIBLE);
                    if (listeningComposerLayout != null) listeningComposerLayout.setVisibility(View.GONE);
                    if (waveformView != null) waveformView.stopListening();
                    if (sendQuestionButton != null) sendQuestionButton.setEnabled(true);
                    if (chatSendButton != null) chatSendButton.setEnabled(true);
                    stopStreamingAnimation();
                    if (speakingIndicator != null) speakingIndicator.setVisibility(View.GONE);
                    break;
            }
        });
    }

    private void startStreamingAnimation() {
        streamingDotsHandler.removeCallbacks(streamingDotsRunnable);
        streamingDotsHandler.post(streamingDotsRunnable);
    }

    private void stopStreamingAnimation() {
        streamingDotsHandler.removeCallbacks(streamingDotsRunnable);
        if (streamingIndicator != null) streamingIndicator.setVisibility(View.GONE);
    }

    private void showErrorState(String errorMsg) {
        if (errorText != null) errorText.setText(errorMsg);
        applyUiState(UiState.ERROR);
    }

    private void copyResultToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String textToCopy = "World Lens\nAnswer: " + currentFullAnswer + "\nMorse Summary: " + currentMorseSummary + "\nMorse Code: " + currentMorseCode;
        ClipData clip = ClipData.newPlainText("World Lens Result", textToCopy);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void exportReportToMarkdown() {
        String textToCopy = "# World Lens Scan Report\n\n**Question:**\n" + lastAskedQuestion + "\n\n**Answer:**\n" + currentFullAnswer + "\n\n**Morse Summary:**\n" + currentMorseSummary + "\n\n**Morse Code:**\n`" + currentMorseCode + "`";
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, textToCopy);
        sendIntent.setType("text/plain");
        Intent shareIntent = Intent.createChooser(sendIntent, null);
        startActivity(shareIntent);
    }

    private void playMorseCode() {
        if (currentMorseSummary == null || currentMorseSummary.isEmpty()) return;
        applyUiState(UiState.MORSE_PLAYING);
        morseSoundPlayer.playMorse(currentMorseSummary, new MorseSoundPlayer.PlaybackCallback() {
            @Override
            public void onPlaybackStarted(String text, long durationMs) {}
            @Override
            public void onPlaybackFinished() {
                runOnUiThread(() -> {
                    if (currentUiState == UiState.MORSE_PLAYING) {
                        applyUiState(UiState.READY);
                    }
                });
            }
            @Override
            public void onError(String err) {
                runOnUiThread(() -> applyUiState(UiState.READY));
            }
        });
    }

    private void stopMorseCode() {
        if (morseSoundPlayer != null) morseSoundPlayer.stop();
        applyUiState(UiState.READY);
    }

    private void startListening() {
        if (isListening) {
            return;
        }

        try {
            if (speechRecognizer == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                } else {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                }
                
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        isListening = true;
                        applyUiState(UiState.LISTENING);
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                    }

                    @Override
                    public void onRmsChanged(float rmsdB) {
                        runOnUiThread(() -> {
                            if (waveformView != null) waveformView.setRmsDb(rmsdB);
                        });
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {
                    }

                    @Override
                    public void onEndOfSpeech() {
                    }

                    @Override
                    public void onError(int error) {
                        isListening = false;
                        applyUiState(UiState.READY);
                        String errorMsg;
                        switch (error) {
                            case SpeechRecognizer.ERROR_NO_MATCH:
                            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                                errorMsg = "Could not hear that. Hold to speak again.";
                                break;
                            case SpeechRecognizer.ERROR_AUDIO:
                                errorMsg = "Audio error. Please check microphone.";
                                break;
                            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                                errorMsg = "Microphone permission is required.";
                                break;
                            default:
                                errorMsg = "Voice input unavailable (code " + error + ")";
                                break;
                        }
                        Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onResults(Bundle results) {
                        isListening = false;
                        applyUiState(UiState.READY);
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String recognizedText = matches.get(0);
                            if (chatPanelContainer != null && chatPanelContainer.getVisibility() == View.VISIBLE) {
                                if (chatQuestionInput != null) {
                                    chatQuestionInput.setText(recognizedText);
                                    chatQuestionInput.setSelection(recognizedText.length());
                                }
                            } else {
                                if (questionInput != null) {
                                    questionInput.setText(recognizedText);
                                    questionInput.setSelection(recognizedText.length());
                                }
                            }
                            submitQuestion(recognizedText);
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                    }

                    @Override
                    public void onEvent(int eventType, Bundle params) {
                    }
                });
            }

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            speechRecognizer.startListening(intent);
            isListening = true;
            applyUiState(UiState.LISTENING);

        } catch (Exception e) {
            isListening = false;
            applyUiState(UiState.READY);
            Toast.makeText(this, "Failed to start speech recognition: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopListening() {
        if (speechRecognizer != null && isListening) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {}
        }
        isListening = false;
        if (waveformView != null) waveformView.stopListening();
        applyUiState(UiState.READY);
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(Locale.getDefault());
                }
                isTtsReady = true;
            } else {
                isTtsReady = false;
            }
        });
        streamingTtsController = new StreamingTtsController(textToSpeech);
    }

    private void speakText(String text) {
        if (textToSpeech != null && isTtsReady && text != null && !text.trim().isEmpty()) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "WorldLensTTS");
        }
    }

    private void stopSpeaking() {
        if (streamingTtsController != null) {
            streamingTtsController.stop();
        }
        if (textToSpeech != null && isTtsReady) {
            textToSpeech.stop();
        }
        isTtsSpeaking = false;
    }

    private void setupLocalAI() {
        try {
            ImageClassifier.ImageClassifierOptions options =
                    ImageClassifier.ImageClassifierOptions.builder()
                            .setBaseOptions(
                                    BaseOptions.builder()
                                            .setModelAssetPath("efficientnet_lite0.tflite")
                                            .build()
                            )
                            .setMaxResults(3) // Get top 3 predictions
                            .build();

            imageClassifier = ImageClassifier.createFromOptions(this, options);
        } catch (Exception e) {
            if (resultText != null) {
                resultText.setText("Model loading failed:\n" + e.getMessage());
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                if (resultText != null) {
                    resultText.setText("Failed to start camera:\n" + e.getMessage());
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (imageClassifier == null) {
            imageProxy.close();
            return;
        }

        try {
            Bitmap bitmap = imageProxy.toBitmap();

            Matrix matrix = new Matrix();
            matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();

            ImageClassifierResult result = imageClassifier.classify(mpImage);

            List<Category> categories = result.classificationResult().classifications().get(0).categories();
            StringBuilder output = new StringBuilder();
            for (Category category : categories) {
                output.append(category.categoryName())
                        .append(" : ")
                        .append(String.format("%.2f", category.score()))
                        .append("\n");
            }

            // Only update live image classification if user hasn't asked a custom question
            if (!isCustomQuestionAsked && resultText != null) {
                runOnUiThread(() -> resultText.setText("Live Recognition:\n" + output.toString()));
            }

        } catch (Exception e) {
            if (!isCustomQuestionAsked && resultText != null) {
                runOnUiThread(() -> resultText.setText("Analysis error: " + e.getMessage()));
            }
        } finally {
            imageProxy.close();
        }
    }

    private void initGemmaVlm() {
        vlmExecutor.execute(() -> {
            gemmaModelFile = findGemmaModelFile();
            if (gemmaModelFile == null) {
                isGemmaReady = false;
                runOnUiThread(() -> {
                    if (!isCustomQuestionAsked && resultText != null) {
                        resultText.setText("Gemma-4-E2B-it model not found in app storage.\n\nTap 'Settings' ⚙ to select gemma-4-E2B-it.litertlm from your Downloads folder.");
                    }
                });
                return;
            }

            initGemmaEngine(gemmaModelFile);
        });
    }

    private void importModelFromUri(@NonNull Uri uri) {
        isGemmaLoading = true;
        runOnUiThread(() -> {
            if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.VISIBLE);
            if (resultText != null) {
                resultText.setText("Importing model from Downloads into app-private storage...\nPlease keep the app open.");
            }
        });

        vlmExecutor.execute(() -> {
            File targetFile = new File(getFilesDir(), "gemma-4-E2B-it.litertlm");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(targetFile)) {

                if (in == null) {
                    throw new IOException("Cannot open input stream from chosen file.");
                }

                byte[] buffer = new byte[1024 * 1024]; // 1 MB buffer for high throughput
                int bytesRead;
                long totalBytes = 0;
                long lastReportTime = System.currentTimeMillis();

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                    long now = System.currentTimeMillis();
                    if (now - lastReportTime > 500) { // Update status every 500ms
                        lastReportTime = now;
                        long mbCopied = totalBytes / (1024 * 1024);
                        runOnUiThread(() -> {
                            if (resultText != null) resultText.setText("Importing model: " + mbCopied + " MB copied...");
                        });
                    }
                }
                out.flush();

                gemmaModelFile = targetFile;
                runOnUiThread(() -> {
                    Toast.makeText(this, "Model imported successfully!", Toast.LENGTH_SHORT).show();
                });

                // Initialize the newly imported model
                initGemmaEngine(targetFile);

            } catch (Exception e) {
                isGemmaLoading = false;
                runOnUiThread(() -> {
                    if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.GONE);
                    if (resultText != null) resultText.setText("Failed to import model: " + e.getMessage());
                });
            }
        });
    }

    private void initGemmaEngine(@NonNull File modelFile) {
        isGemmaLoading = true;
        runOnUiThread(() -> {
            if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.VISIBLE);
            if (!isCustomQuestionAsked && resultText != null) {
                resultText.setText("Found model: " + modelFile.getName() + "\nInitializing Gemma-4-E2B-it (GPU)...");
            }
        });

        // 1. First attempt: backend=GPU, visionBackend=GPU
        Engine engine = null;
        try {
            EngineConfig gpuConfig = new EngineConfig(
                    modelFile.getAbsolutePath(),
                    new Backend.GPU(),
                    new Backend.GPU(),
                    null, // audioBackend
                    null, // maxNumTokens (model default)
                    null, // maxNumImages (SDK/model default)
                    getCacheDir().getAbsolutePath()
            );
            engine = new Engine(gpuConfig);
            engine.initialize();

            gemmaEngine = engine;
            visionBackend = new Backend.GPU();
            isGemmaReady = true;
            isGemmaLoading = false;

            // Initialize persistent conversation session
            if (gemmaConversation != null) {
                try {
                    gemmaConversation.close();
                } catch (Exception ignored) {}
            }
            gemmaConversation = gemmaEngine.createConversation(new ConversationConfig());
            isFirstTurnOfScan = true;

            runOnUiThread(() -> {
                if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.GONE);
                if (!isCustomQuestionAsked && resultText != null) {
                    resultText.setText("Local Gemma-4-E2B-it ready (GPU)!\nPoint camera at an object and ask a question.");
                }
                applyUiState(UiState.READY);
            });
            return;
        } catch (Exception gpuEx) {
            // Crucial: close the failed GPU engine before creating a NEW CPU engine
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception ignored) {}
                engine = null;
            }

            runOnUiThread(() -> {
                if (!isCustomQuestionAsked && resultText != null) {
                    resultText.setText("GPU initialization failed, falling back to CPU...\n" + gpuEx.getMessage());
                }
            });
        }

        // 2. Second attempt: Create a NEW engine instance with backend=CPU, visionBackend=CPU
        try {
            EngineConfig cpuConfig = new EngineConfig(
                    modelFile.getAbsolutePath(),
                    new Backend.CPU(),
                    new Backend.CPU(),
                    null,
                    null,
                    null, // maxNumImages
                    getCacheDir().getAbsolutePath()
            );
            engine = new Engine(cpuConfig);
            engine.initialize();

            gemmaEngine = engine;
            visionBackend = new Backend.CPU();
            isGemmaReady = true;
            isGemmaLoading = false;

            // Initialize persistent conversation session
            if (gemmaConversation != null) {
                try {
                    gemmaConversation.close();
                } catch (Exception ignored) {}
            }
            gemmaConversation = gemmaEngine.createConversation(new ConversationConfig());
            isFirstTurnOfScan = true;

            runOnUiThread(() -> {
                if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.GONE);
                if (!isCustomQuestionAsked && resultText != null) {
                    resultText.setText("Local Gemma-4-E2B-it ready (CPU fallback)!\nPoint camera at an object and ask a question.");
                }
                applyUiState(UiState.READY);
            });
        } catch (Exception cpuEx) {
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception ignored) {}
                engine = null;
            }
            isGemmaReady = false;
            isGemmaLoading = false;
            runOnUiThread(() -> {
                if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.GONE);
                if (resultText != null) {
                    resultText.setText("Model initialization failed on both GPU and CPU:\n" + cpuEx.getMessage());
                }
                applyUiState(UiState.ERROR);
            });
        }
    }

    private File findGemmaModelFile() {
        // 1. Check internal app-private directory (/data/user/0/com.example.worldlens/files/)
        File internalDir = getFilesDir();
        if (internalDir != null) {
            File target = new File(internalDir, "gemma-4-E2B-it.litertlm");
            if (target.exists() && target.canRead()) {
                return target;
            }
            File[] files = internalDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".litertlm"));
            if (files != null && files.length > 0) {
                return files[0];
            }
        }

        // 2. Check external app-private directory (/sdcard/Android/data/com.example.worldlens/files/)
        File extDir = getExternalFilesDir(null);
        if (extDir != null) {
            File target = new File(extDir, "gemma-4-E2B-it.litertlm");
            if (target.exists() && target.canRead()) {
                return target;
            }
            File[] files = extDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".litertlm"));
            if (files != null && files.length > 0) {
                return files[0];
            }
        }

        return null;
    }

    private void resetConversation() {
        if (isListening) {
            stopListening();
        }

        stopSpeaking();

        if (isInferenceRunning) {
            Toast.makeText(this, "Please wait for current answer to finish...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (gemmaEngine == null || !isGemmaReady) {
            Toast.makeText(this, "Model is not ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        vlmExecutor.execute(() -> {
            if (gemmaConversation != null) {
                try {
                    gemmaConversation.close();
                } catch (Exception ignored) {}
                gemmaConversation = null;
            }

            try {
                gemmaConversation = gemmaEngine.createConversation(new ConversationConfig());
                isFirstTurnOfScan = true;
                isCustomQuestionAsked = false;

                runOnUiThread(() -> {
                    conversationHistory.clear();
                    updateChatHistoryUI();
                    closeChatPanel();
                    if (questionInput != null) questionInput.setText("");
                    if (chatQuestionInput != null) chatQuestionInput.setText("");
                    lastAskedQuestion = "";
                    currentFullAnswer = "";
                    currentMorseSummary = "";
                    currentMorseCode = "";
                    if (resultText != null) resultText.setText("Point camera at an object and hold the microphone to ask.");
                    applyUiState(UiState.READY);
                    Toast.makeText(this, "New chat started.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showErrorState("Failed to start new conversation:\n" + e.getMessage());
                });
            }
        });
    }

    private void runGemmaTurn(String question, Bitmap bitmap, boolean isFirstTurn) {
        try {
            if (gemmaConversation == null) {
                gemmaConversation = gemmaEngine.createConversation(new ConversationConfig());
            }

            String modifiedQuestion = question + "\n\nPlease provide your response exactly in the following format:\n\nFULL ANSWER:\n[your detailed answer here]\n\nMORSE SUMMARY TEXT:\n[a very short summary of max 7 words]";

            final StringBuilder accumulatedResponse = new StringBuilder();
            
            final int[] lastSpokenCharIndex = {0};
            final int[] lastVisibleCharIndex = {0};
            final boolean[] hasFoundFullAnswerStart = {false};
            final boolean[] hasFoundSummaryStart = {false};

            if (streamingTtsController != null) {
                streamingTtsController.start();
                isTtsSpeaking = true;
            }

            runOnUiThread(() -> applyUiState(UiState.STREAMING));

            MessageCallback callback = new MessageCallback() {
                @Override
                public void onMessage(@NonNull Message message) {
                    String chunk = extractTextFromMessage(message);
                    accumulatedResponse.append(chunk);
                    String currentTotal = accumulatedResponse.toString();

                    if (!hasFoundFullAnswerStart[0]) {
                        int startIdx = currentTotal.indexOf("FULL ANSWER:");
                        if (startIdx != -1) {
                            hasFoundFullAnswerStart[0] = true;
                            lastVisibleCharIndex[0] = startIdx + 12;
                            lastSpokenCharIndex[0] = startIdx + 12;
                        } else if (currentTotal.length() > 50) { 
                            hasFoundFullAnswerStart[0] = true;
                        }
                    }

                    if (hasFoundFullAnswerStart[0] && !hasFoundSummaryStart[0]) {
                        int sumIdx = currentTotal.indexOf("MORSE SUMMARY TEXT:");
                        if (sumIdx != -1) {
                            hasFoundSummaryStart[0] = true;
                            
                            if (sumIdx > lastSpokenCharIndex[0]) {
                                String newAnswerText = currentTotal.substring(lastSpokenCharIndex[0], sumIdx);
                                if (streamingTtsController != null) streamingTtsController.append(newAnswerText);
                                lastSpokenCharIndex[0] = sumIdx;
                            }
                            
                            if (sumIdx > lastVisibleCharIndex[0]) {
                                String visibleText = currentTotal.substring(lastVisibleCharIndex[0], sumIdx);
                                runOnUiThread(() -> {
                                    if (resultText != null) resultText.setText(visibleText.trim());
                                    updateStreamingChatTurn(visibleText.trim());
                                });
                            }
                        } else {
                            int safeLength = currentTotal.length() - 25; 
                            
                            if (safeLength > lastSpokenCharIndex[0]) {
                                String safeNewText = currentTotal.substring(lastSpokenCharIndex[0], safeLength);
                                if (streamingTtsController != null) streamingTtsController.append(safeNewText);
                                lastSpokenCharIndex[0] = safeLength;
                            }
                            
                            if (safeLength > lastVisibleCharIndex[0]) {
                                String visibleText = currentTotal.substring(lastVisibleCharIndex[0], safeLength);
                                runOnUiThread(() -> {
                                    if (resultText != null) resultText.setText(visibleText.trim());
                                    updateStreamingChatTurn(visibleText.trim());
                                });
                            }
                        }
                    }
                }

                @Override
                public void onDone() {
                    isInferenceRunning = false;
                    isFirstTurnOfScan = false;
                    
                    if (streamingTtsController != null) streamingTtsController.finish();
                    
                    String rawResponse = accumulatedResponse.toString();
                    currentFullAnswer = rawResponse;
                    currentMorseSummary = rawResponse;
                    currentMorseCode = "";
                    
                    int fullIndex = rawResponse.indexOf("FULL ANSWER:");
                    int summaryIndex = rawResponse.indexOf("MORSE SUMMARY TEXT:");
                    
                    if (fullIndex != -1 && summaryIndex != -1 && summaryIndex > fullIndex) {
                        currentFullAnswer = rawResponse.substring(fullIndex + 12, summaryIndex).trim();
                        currentMorseSummary = rawResponse.substring(summaryIndex + 19).trim();
                    } else {
                        String upperRaw = rawResponse.toUpperCase();
                        int fIdx = upperRaw.indexOf("FULL ANSWER");
                        int sIdx = upperRaw.indexOf("MORSE SUMMARY");
                        if (fIdx != -1 && sIdx != -1 && sIdx > fIdx) {
                            currentFullAnswer = rawResponse.substring(fIdx + 11, sIdx).replace(":", "").trim();
                            currentMorseSummary = rawResponse.substring(sIdx + 13).replace(":", "").replace("TEXT", "").trim();
                        }
                    }
                    
                    currentMorseCode = MorseEncoder.textToMorseString(currentMorseSummary);
                    
                    if (!conversationHistory.isEmpty()) {
                        ChatTurn lastTurn = conversationHistory.get(conversationHistory.size() - 1);
                        lastTurn.answer = currentFullAnswer;
                        lastTurn.morseSummary = currentMorseSummary;
                        lastTurn.morseCode = currentMorseCode;
                    }
                    
                    runOnUiThread(() -> {
                        applyUiState(UiState.READY);
                        if (questionInput != null) questionInput.setText("");
                        if (chatQuestionInput != null) chatQuestionInput.setText("");
                        if (resultText != null) resultText.setText(currentFullAnswer);
                        updateChatHistoryUI();
                    });
                }

                @Override
                public void onError(@NonNull Throwable throwable) {
                    isInferenceRunning = false;
                    if (!conversationHistory.isEmpty()) {
                        ChatTurn lastTurn = conversationHistory.get(conversationHistory.size() - 1);
                        lastTurn.answer = "Inference error: " + throwable.getMessage();
                    }
                    runOnUiThread(() -> {
                        updateChatHistoryUI();
                        showErrorState("Inference error:\n" + throwable.getMessage());
                    });
                }
            };

            if (isFirstTurn && bitmap != null) {
                File cacheImage = new File(getCacheDir(), "vlm_input.jpg");
                try (FileOutputStream fos = new FileOutputStream(cacheImage)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                }

                Content imageContent = new Content.ImageFile(cacheImage.getAbsolutePath());
                Content textContent = new Content.Text(modifiedQuestion);
                Contents contents = Contents.Companion.of(imageContent, textContent);

                gemmaConversation.sendMessageAsync(contents, callback);
            } else {
                gemmaConversation.sendMessageAsync(modifiedQuestion, callback);
            }

        } catch (Exception e) {
            isInferenceRunning = false;
            runOnUiThread(() -> {
                showErrorState("Failed to process question:\n" + e.getMessage());
                if (sendQuestionButton != null) sendQuestionButton.setEnabled(true);
                if (chatSendButton != null) chatSendButton.setEnabled(true);
            });
        }
    }

    private String extractTextFromMessage(Message message) {
        if (message == null || message.getContents() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<Content> parts = message.getContents().getContents();
        if (parts != null) {
            for (Content part : parts) {
                if (part instanceof Content.Text) {
                    sb.append(((Content.Text) part).getText());
                }
            }
        }
        if (sb.length() == 0) {
            return message.toString();
        }
        return sb.toString();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speechRecognizer != null && isListening) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {}
            isListening = false;
        }
        stopSpeaking();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        if (textToSpeech != null) {
            try {
                textToSpeech.stop();
                textToSpeech.shutdown();
            } catch (Exception ignored) {}
            textToSpeech = null;
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (vlmExecutor != null) {
            vlmExecutor.shutdown();
        }
        if (imageClassifier != null) {
            imageClassifier.close();
        }
        if (gemmaConversation != null) {
            try {
                gemmaConversation.close();
            } catch (Exception ignored) {}
            gemmaConversation = null;
        }
        if (gemmaEngine != null) {
            try {
                gemmaEngine.close();
            } catch (Exception ignored) {}
            gemmaEngine = null;
        }
    }

    // ====================================================================
    // WIREFRAME INTERACTION & STATE LOGIC
    // ====================================================================

    private void submitQuestion(String question) {
        if (isListening) {
            stopListening();
        }

        if (isInferenceRunning) {
            Toast.makeText(this, "Thinking... please wait for current answer.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (gemmaModelFile == null || (!isGemmaReady && !isGemmaLoading)) {
            modelPickerLauncher.launch(new String[]{"*/*"});
            return;
        }

        if (isGemmaLoading) {
            Toast.makeText(this, "Gemma model is still initializing. Please wait...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isGemmaReady || gemmaEngine == null) {
            Toast.makeText(this, "Model not ready. Tap to select model file.", Toast.LENGTH_LONG).show();
            modelPickerLauncher.launch(new String[]{"*/*"});
            return;
        }

        String prompt = (question == null) ? "" : question.trim();
        if (prompt.isEmpty()) {
            prompt = isFirstTurnOfScan ? "Describe this image in detail." : "Tell me more about what you see.";
        } else {
            isCustomQuestionAsked = true;
        }

        lastAskedQuestion = prompt;
        isInferenceRunning = true;
        stopSpeaking();
        stopMorseCode();
        applyUiState(UiState.PROCESSING);

        // Append user turn to conversation history
        ChatTurn newTurn = new ChatTurn(prompt, "Thinking...", "", "");
        conversationHistory.add(newTurn);
        updateChatHistoryUI();

        // Clear input fields
        if (questionInput != null) questionInput.setText("");
        if (chatQuestionInput != null) chatQuestionInput.setText("");

        // Hide keyboard
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {}

        final String finalQ = prompt;
        if (isFirstTurnOfScan) {
            Bitmap capturedBitmap = viewFinder.getBitmap();
            if (capturedBitmap == null) {
                isInferenceRunning = false;
                showErrorState("Failed to capture image from camera.");
                return;
            }
            final Bitmap imageForInference = capturedBitmap;
            vlmExecutor.execute(() -> runGemmaTurn(finalQ, imageForInference, true));
        } else {
            vlmExecutor.execute(() -> runGemmaTurn(finalQ, null, false));
        }
    }

    private void showMoreActionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, isTtsSpeaking ? "Stop Speaking" : "Read Answer Aloud");
        popup.getMenu().add(0, 2, 1, "Copy Response");
        popup.getMenu().add(0, 3, 2, "Export Report (Markdown)");
        popup.getMenu().add(0, 4, 3, "Morse Code Details");
        popup.getMenu().add(0, 5, 4, "View Full Conversation");
        popup.getMenu().add(0, 6, 5, "New Scan");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    if (isTtsSpeaking) {
                        stopSpeaking();
                        applyUiState(UiState.READY);
                    } else if (currentFullAnswer != null && !currentFullAnswer.trim().isEmpty()) {
                        isTtsSpeaking = true;
                        applyUiState(UiState.SPEAKING);
                        speakText(currentFullAnswer);
                    } else {
                        Toast.makeText(this, "No answer to read aloud.", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case 2:
                    copyResultToClipboard();
                    return true;
                case 3:
                    exportReportToMarkdown();
                    return true;
                case 4:
                    showMorseDetailsDialog();
                    return true;
                case 5:
                    openChatPanel();
                    return true;
                case 6:
                    resetConversation();
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void showSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("World Lens Settings")
                .setMessage("• AI Engine: Gemma-4-E2B-it via LiteRT-LM\n" +
                        "• Vision Acceleration: " + (visionBackend instanceof Backend.GPU ? "GPU" : "CPU") + "\n" +
                        "• Speech: Offline On-Device TTS\n" +
                        "• Audio Morse: 700 Hz Tone Generator\n" +
                        "• Privacy: 100% Offline & Private\n" +
                        "• Target Device: iQOO Neo 6\n\n" +
                        "Tap 'Select Model' if you wish to import a different .litertlm file.")
                .setPositiveButton("Close", null)
                .setNeutralButton("Select Model", (dialog, which) -> {
                    modelPickerLauncher.launch(new String[]{"*/*"});
                })
                .show();
    }

    private void showMorseDetailsDialog() {
        String summary = (currentMorseSummary != null && !currentMorseSummary.isEmpty()) ? currentMorseSummary : "No Morse code generated yet.";
        String code = (currentMorseCode != null && !currentMorseCode.isEmpty()) ? currentMorseCode : "No dots or dashes yet.";

        new AlertDialog.Builder(this)
                .setTitle("Morse Code Details")
                .setMessage("Summary:\n" + summary + "\n\nMorse Code:\n" + code)
                .setPositiveButton("Play Morse", (dialog, which) -> playMorseCode())
                .setNegativeButton("Copy Code", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Morse Code", code);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Morse code copied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Close", null)
                .show();
    }

    private void openChatPanel() {
        if (chatPanelContainer != null) {
            chatPanelContainer.setVisibility(View.VISIBLE);
            updateChatHistoryUI();
            if (chatScrollView != null) {
                chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void closeChatPanel() {
        if (chatPanelContainer != null) {
            chatPanelContainer.setVisibility(View.GONE);
        }
    }

    private void updateChatHistoryUI() {
        if (chatMessagesLayout == null) return;
        chatMessagesLayout.removeAllViews();

        if (conversationHistory.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("No messages yet.\nPoint the camera at an object and ask a question to begin.");
            emptyView.setTextColor(ContextCompat.getColor(this, R.color.wl_text_secondary));
            emptyView.setTextSize(14);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(32, 64, 32, 64);
            chatMessagesLayout.addView(emptyView);
            return;
        }

        for (int i = 0; i < conversationHistory.size(); i++) {
            ChatTurn turn = conversationHistory.get(i);

            // User Question Bubble
            LinearLayout userRow = new LinearLayout(this);
            userRow.setOrientation(LinearLayout.HORIZONTAL);
            userRow.setGravity(Gravity.END);
            LinearLayout.LayoutParams userRowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            userRowParams.setMargins(48, 8, 0, 8);
            userRow.setLayoutParams(userRowParams);

            TextView userBubble = new TextView(this);
            userBubble.setText(turn.question);
            userBubble.setTextColor(ContextCompat.getColor(this, R.color.wl_text_primary));
            userBubble.setTextSize(14);
            userBubble.setBackgroundResource(R.drawable.bg_bubble_user);
            userBubble.setPadding(32, 20, 32, 20);
            userRow.addView(userBubble);
            chatMessagesLayout.addView(userRow);

            // AI Answer Card
            LinearLayout aiCard = new LinearLayout(this);
            aiCard.setOrientation(LinearLayout.VERTICAL);
            aiCard.setBackgroundResource(R.drawable.bg_bubble_ai);
            aiCard.setPadding(30, 22, 30, 22);
            LinearLayout.LayoutParams aiCardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            aiCardParams.setMargins(0, 8, 48, 16);
            aiCard.setLayoutParams(aiCardParams);

            // AI Label Header
            TextView aiTitle = new TextView(this);
            aiTitle.setText("AI");
            aiTitle.setTextColor(ContextCompat.getColor(this, R.color.wl_primary));
            aiTitle.setTextSize(13);
            aiTitle.setTypeface(null, Typeface.BOLD);
            aiCard.addView(aiTitle);

            // AI Body Text
            TextView aiBody = new TextView(this);
            aiBody.setTag("ai_turn_" + i);
            aiBody.setText(turn.answer);
            aiBody.setTextColor(ContextCompat.getColor(this, R.color.wl_text_body));
            aiBody.setTextSize(14);
            aiBody.setLineSpacing(4f, 1f);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bodyParams.setMargins(0, 6, 0, 8);
            aiBody.setLayoutParams(bodyParams);
            aiCard.addView(aiBody);

            // Morse Preview Pill (if available)
            if (turn.morseSummary != null && !turn.morseSummary.isEmpty() && !turn.morseSummary.equals("...") && !turn.morseSummary.equals(turn.answer)) {
                TextView morsePill = new TextView(this);
                morsePill.setText("••• Morse: " + turn.morseSummary);
                morsePill.setTextColor(ContextCompat.getColor(this, R.color.wl_primary));
                morsePill.setTextSize(12);
                morsePill.setTypeface(null, Typeface.BOLD);
                morsePill.setBackgroundResource(R.drawable.bg_live_badge);
                morsePill.setPadding(20, 10, 20, 10);
                LinearLayout.LayoutParams morseParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                morseParams.setMargins(0, 4, 0, 10);
                morsePill.setLayoutParams(morseParams);
                aiCard.addView(morsePill);
            }

            // Action Buttons Row inside AI card
            LinearLayout actionRow = new LinearLayout(this);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER_VERTICAL);

            final String answerText = turn.answer;
            final String morseText = turn.morseSummary;

            // Read aloud
            Button readBtn = new Button(this, null, androidx.appcompat.R.attr.buttonStyleSmall);
            readBtn.setText("🔊 Read");
            readBtn.setTextSize(11);
            readBtn.setTextColor(ContextCompat.getColor(this, R.color.wl_text_primary));
            readBtn.setBackgroundResource(R.drawable.bg_pill_button);
            readBtn.setPadding(24, 0, 24, 0);
            readBtn.setOnClickListener(v -> speakText(answerText));
            actionRow.addView(readBtn);

            // Play Morse
            if (morseText != null && !morseText.isEmpty()) {
                Button morseBtn = new Button(this, null, androidx.appcompat.R.attr.buttonStyleSmall);
                morseBtn.setText("••• Morse");
                morseBtn.setTextSize(11);
                morseBtn.setTextColor(ContextCompat.getColor(this, R.color.wl_text_primary));
                morseBtn.setBackgroundResource(R.drawable.bg_pill_button);
                LinearLayout.LayoutParams morseBtnParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                morseBtnParams.setMarginStart(12);
                morseBtn.setLayoutParams(morseBtnParams);
                morseBtn.setPadding(24, 0, 24, 0);
                morseBtn.setOnClickListener(v -> {
                    applyUiState(UiState.MORSE_PLAYING);
                    morseSoundPlayer.playMorse(morseText, new MorseSoundPlayer.PlaybackCallback() {
                        @Override public void onPlaybackStarted(String text, long durationMs) {}
                        @Override public void onPlaybackFinished() {
                            runOnUiThread(() -> {
                                if (currentUiState == UiState.MORSE_PLAYING) applyUiState(UiState.READY);
                            });
                        }
                        @Override public void onError(String err) {
                            runOnUiThread(() -> applyUiState(UiState.READY));
                        }
                    });
                });
                actionRow.addView(morseBtn);
            }

            // Copy
            Button copyBtn = new Button(this, null, androidx.appcompat.R.attr.buttonStyleSmall);
            copyBtn.setText("📋 Copy");
            copyBtn.setTextSize(11);
            copyBtn.setTextColor(ContextCompat.getColor(this, R.color.wl_text_primary));
            copyBtn.setBackgroundResource(R.drawable.bg_pill_button);
            LinearLayout.LayoutParams copyBtnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            copyBtnParams.setMarginStart(12);
            copyBtn.setLayoutParams(copyBtnParams);
            copyBtn.setPadding(24, 0, 24, 0);
            copyBtn.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("AI Answer", answerText);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Answer copied", Toast.LENGTH_SHORT).show();
            });
            actionRow.addView(copyBtn);

            aiCard.addView(actionRow);
            chatMessagesLayout.addView(aiCard);
        }

        if (chatScrollView != null) {
            chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateStreamingChatTurn(String partialAnswer) {
        if (conversationHistory.isEmpty()) return;
        ChatTurn lastTurn = conversationHistory.get(conversationHistory.size() - 1);
        lastTurn.answer = partialAnswer;

        if (chatMessagesLayout != null && chatPanelContainer != null && chatPanelContainer.getVisibility() == View.VISIBLE) {
            TextView targetView = chatMessagesLayout.findViewWithTag("ai_turn_" + (conversationHistory.size() - 1));
            if (targetView != null) {
                targetView.setText(partialAnswer);
            }
            if (chatScrollView != null) {
                boolean isAtBottom = !chatScrollView.canScrollVertically(1);
                if (isAtBottom) {
                    chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
                }
            }
        }
    }
}