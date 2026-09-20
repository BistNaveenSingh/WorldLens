package com.example.worldlens;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import com.example.worldlens.morse.MorseEncoder;
import com.example.worldlens.morse.MorseSoundPlayer;

import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

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

    private ImageClassifier imageClassifier;
    private TextView resultText;
    private PreviewView viewFinder;
    private EditText questionInput;
    private ImageButton micButton;
    private Button askButton;
    private ProgressBar loadingProgressBar;
    private ExecutorService cameraExecutor;

    // Export UI
    private View copyResultCard;
    private View exportReportCard;

    // UI States & Navigation
    private View cameraStateContainer;
    private View resultStateContainer;
    private View loadingStateContainer;
    private View errorStateContainer;
    private View exportStateContainer;
    
    private TextView errorText;
    private Button retryButton;
    private TextView questionEchoText;
    private Button readAnswerButton;
    private Button stopMorseButton;
    private Button playMorseButton;
    private Button openExportStateButton;
    private TextView morseSummaryText;
    private TextView morseCodeText;
    
    private View backToHomeButton;
    private View deleteScanButton;
    private View backToResultButton;
    private Button newScanButtonFromExport;

    // Local Gemma VLM engine, conversation session, and background executor
    private Engine gemmaEngine;
    private Conversation gemmaConversation;
    private boolean isFirstTurnOfScan = true;
    private volatile boolean isInferenceRunning = false;
    private ExecutorService vlmExecutor;
    private volatile boolean isGemmaLoading = false;
    private volatile boolean isGemmaReady = false;
    private File gemmaModelFile = null;

    // Configurable backends (GPU default with automatic CPU fallback)
    private Backend llmBackend = new Backend.GPU();
    private Backend visionBackend = new Backend.GPU();

    private boolean isCustomQuestionAsked = false;

    // Offline Speech Recognition
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;

    // Accessibility Mode & Haptics
    private TextView accessibilityLabel;
    private SwitchCompat accessibilitySwitch;
    private boolean isAccessibilityModeEnabled = false;
    private Vibrator vibrator;

    // Text to Speech
    private TextToSpeech textToSpeech;
    private boolean isTtsReady = false;

    // Morse Audio Player
    private MorseSoundPlayer morseSoundPlayer;

    // State Variables
    private String lastAskedQuestion = "";
    private String currentFullAnswer = "";
    private String currentMorseSummary = "";
    private String currentMorseCode = "";

    // Haptic Feedback Event Types
    private enum HapticEvent {
        PROCESSING_STARTED,
        ANSWER_COMPLETED,
        ERROR,
        NEW_SCAN
    }

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
                    resultText.setText("Microphone permission denied.");
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
        resultText = findViewById(R.id.resultText);
        viewFinder = findViewById(R.id.viewFinder);
        questionInput = findViewById(R.id.questionInput);
        micButton = findViewById(R.id.micButton);
        askButton = findViewById(R.id.askButton);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        accessibilityLabel = findViewById(R.id.accessibilityLabel);
        accessibilitySwitch = findViewById(R.id.accessibilitySwitch);
        
        // Find these if they exist in XML
        playMorseButton = findViewById(R.id.playMorseButton);
        morseSummaryText = findViewById(R.id.morseSummaryText);
        morseCodeText = findViewById(R.id.morseCodeText);
        copyResultCard = findViewById(R.id.copyResultCard);
        exportReportCard = findViewById(R.id.exportReportCard);
        
        cameraStateContainer = findViewById(R.id.cameraStateContainer);
        resultStateContainer = findViewById(R.id.resultStateContainer);
        loadingStateContainer = findViewById(R.id.loadingStateContainer);
        errorStateContainer = findViewById(R.id.errorStateContainer);
        exportStateContainer = findViewById(R.id.exportStateContainer);
        
        errorText = findViewById(R.id.errorText);
        retryButton = findViewById(R.id.retryButton);
        questionEchoText = findViewById(R.id.questionEchoText);
        readAnswerButton = findViewById(R.id.readAnswerButton);
        stopMorseButton = findViewById(R.id.stopMorseButton);
        openExportStateButton = findViewById(R.id.openExportStateButton);
        
        backToHomeButton = findViewById(R.id.backToHomeButton);
        deleteScanButton = findViewById(R.id.deleteScanButton);
        backToResultButton = findViewById(R.id.backToResultButton);
        newScanButtonFromExport = findViewById(R.id.newScanButtonFromExport);

        morseSoundPlayer = new MorseSoundPlayer(this);
        // Initialize Haptic Engine and Text-to-Speech
        initHaptics();
        initTextToSpeech();

        // Background thread for camera image analysis
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Background thread for local Gemma-4-E2B-it VLM operations
        vlmExecutor = Executors.newSingleThreadExecutor();

        // Initialize UI Shell Button Listeners
        setupUIListeners();

        // 1. Initialize the local EfficientNet model
        setupLocalAI();

        // 2. Initialize the local Gemma-4-E2B-it VLM on background thread
        initGemmaVlm();

        // 3. Check and request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void setupUIListeners() {
        if (accessibilitySwitch != null) {
            accessibilitySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isAccessibilityModeEnabled = isChecked;
                accessibilityLabel.setText(isChecked ? "[ Accessibility Mode: ON ]" : "[ Accessibility Mode: OFF ]");
                if (isChecked) {
                    if (resultText != null) resultText.setTextSize(18);
                    triggerHaptic(HapticEvent.PROCESSING_STARTED);
                    Toast.makeText(this, "Accessibility Mode enabled (Haptics ON)", Toast.LENGTH_SHORT).show();
                } else {
                    if (resultText != null) resultText.setTextSize(15);
                    if (vibrator != null) {
                        vibrator.cancel();
                    }
                    Toast.makeText(this, "Accessibility Mode disabled", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (newScanButtonFromExport != null) newScanButtonFromExport.setOnClickListener(v -> resetConversation());
        if (openExportStateButton != null) openExportStateButton.setOnClickListener(v -> showExportState());
        if (backToHomeButton != null) backToHomeButton.setOnClickListener(v -> resetConversation());
        if (deleteScanButton != null) deleteScanButton.setOnClickListener(v -> resetConversation());
        if (backToResultButton != null) backToResultButton.setOnClickListener(v -> showResultState());
        if (copyResultCard != null) copyResultCard.setOnClickListener(v -> copyResultToClipboard());
        if (exportReportCard != null) exportReportCard.setOnClickListener(v -> exportReportToMarkdown());
        if (playMorseButton != null) playMorseButton.setOnClickListener(v -> playMorseCode());
        if (stopMorseButton != null) stopMorseButton.setOnClickListener(v -> stopMorseCode());

        if (askButton != null) askButton.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
            }

            if (isInferenceRunning) {
                Toast.makeText(this, "Thinking... please wait for the current answer.", Toast.LENGTH_SHORT).show();
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

            String question = questionInput.getText().toString().trim();
            if (question.isEmpty()) {
                question = "Describe this image in detail.";
            } else {
                isCustomQuestionAsked = true;
            }

            lastAskedQuestion = question;
            showLoadingState();

            isInferenceRunning = true;
            if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.VISIBLE);
            askButton.setEnabled(false);
            if (newScanButtonFromExport != null) newScanButtonFromExport.setEnabled(false);
            stopSpeaking();
            stopMorseCode();
            triggerHaptic(HapticEvent.PROCESSING_STARTED);

            String finalQ = question;
            runOnUiThread(() -> {
                askButton.setText("ASK WORLD LENS");
                askButton.setEnabled(true);
                if (newScanButtonFromExport != null) newScanButtonFromExport.setEnabled(true);
            });

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
        });

        if (micButton != null) micButton.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListening();
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                }
            }
        });
    }

    private void showCameraState() {
        if (cameraStateContainer != null) cameraStateContainer.setVisibility(View.VISIBLE);
        if (resultStateContainer != null) resultStateContainer.setVisibility(View.GONE);
        if (loadingStateContainer != null) loadingStateContainer.setVisibility(View.GONE);
        if (errorStateContainer != null) errorStateContainer.setVisibility(View.GONE);
        if (exportStateContainer != null) exportStateContainer.setVisibility(View.GONE);
    }

    private void showLoadingState() {
        if (cameraStateContainer != null) cameraStateContainer.setVisibility(View.GONE);
        if (resultStateContainer != null) resultStateContainer.setVisibility(View.GONE);
        if (loadingStateContainer != null) loadingStateContainer.setVisibility(View.VISIBLE);
        if (errorStateContainer != null) errorStateContainer.setVisibility(View.GONE);
        if (exportStateContainer != null) exportStateContainer.setVisibility(View.GONE);
    }

    private void showResultState() {
        if (cameraStateContainer != null) cameraStateContainer.setVisibility(View.GONE);
        if (resultStateContainer != null) resultStateContainer.setVisibility(View.VISIBLE);
        if (loadingStateContainer != null) loadingStateContainer.setVisibility(View.GONE);
        if (errorStateContainer != null) errorStateContainer.setVisibility(View.GONE);
        if (exportStateContainer != null) exportStateContainer.setVisibility(View.GONE);
    }

    private void showErrorState(String errorMsg) {
        if (cameraStateContainer != null) cameraStateContainer.setVisibility(View.GONE);
        if (resultStateContainer != null) resultStateContainer.setVisibility(View.GONE);
        if (loadingStateContainer != null) loadingStateContainer.setVisibility(View.GONE);
        if (errorStateContainer != null) errorStateContainer.setVisibility(View.VISIBLE);
        if (exportStateContainer != null) exportStateContainer.setVisibility(View.GONE);
        if (errorText != null) errorText.setText(errorMsg);
    }
    
    private void showExportState() {
        if (cameraStateContainer != null) cameraStateContainer.setVisibility(View.GONE);
        if (resultStateContainer != null) resultStateContainer.setVisibility(View.GONE);
        if (loadingStateContainer != null) loadingStateContainer.setVisibility(View.GONE);
        if (errorStateContainer != null) errorStateContainer.setVisibility(View.GONE);
        if (exportStateContainer != null) exportStateContainer.setVisibility(View.VISIBLE);
    }

    private void copyResultToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String textToCopy = "World Lens\nAnswer: " + currentFullAnswer + "\nMorse Summary: " + currentMorseSummary + "\nMorse Code: " + currentMorseCode;
        ClipData clip = ClipData.newPlainText("World Lens Result", textToCopy);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void exportReportToMarkdown() {
        copyResultToClipboard();
        Toast.makeText(this, "Export simulated (Copied to clipboard)", Toast.LENGTH_SHORT).show();
    }

    private void playMorseCode() {
        if (currentMorseSummary.isEmpty()) return;
        if (playMorseButton != null) playMorseButton.setEnabled(false);
        if (stopMorseButton != null) stopMorseButton.setVisibility(View.VISIBLE);
        morseSoundPlayer.playMorse(currentMorseSummary, new MorseSoundPlayer.PlaybackCallback() {
            @Override
            public void onPlaybackStarted(String text, long durationMs) {}
            @Override
            public void onPlaybackFinished() {
                runOnUiThread(() -> {
                    if (playMorseButton != null) playMorseButton.setEnabled(true);
                    if (stopMorseButton != null) stopMorseButton.setVisibility(View.GONE);
                });
            }
            @Override
            public void onError(String err) {}
        });
    }

    private void stopMorseCode() {
        if (morseSoundPlayer != null) morseSoundPlayer.stop();
        if (playMorseButton != null) playMorseButton.setEnabled(true);
        if (stopMorseButton != null) stopMorseButton.setVisibility(View.GONE);
    }


    private void startListening() {
        if (isListening) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "Offline speech recognition requires Android 12 (API 31)+", Toast.LENGTH_LONG).show();
            return;
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            Toast.makeText(this, "Offline speech recognition is not available on this device.\nPlease verify offline speech packs in Android Settings.", Toast.LENGTH_LONG).show();
            resultText.setText("Offline speech recognition is not available.\nPlease check offline speech packs in Android Settings.");
            return;
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        isListening = true;
                        // micButton text handled by XML
                        resultText.setText("Listening (offline)... Speak your question now.");
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                    }

                    @Override
                    public void onRmsChanged(float rmsdB) {
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {
                    }

                    @Override
                    public void onEndOfSpeech() {
                        resultText.setText("Processing speech offline...");
                    }

                    @Override
                    public void onError(int error) {
                        isListening = false;
                        // micButton text handled by XML
                        String errorMsg;
                        switch (error) {
                            case SpeechRecognizer.ERROR_NO_MATCH:
                                errorMsg = "No speech recognized. Tap 🎤 to try again.";
                                break;
                            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                                errorMsg = "No speech detected. Tap 🎤 to try again.";
                                break;
                            case SpeechRecognizer.ERROR_AUDIO:
                                errorMsg = "Audio recording error. Please check microphone.";
                                break;
                            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                                errorMsg = "Microphone permission is required.";
                                break;
                            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                            case SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT:
                                errorMsg = "Offline speech service disconnected. Check offline language pack in settings.";
                                break;
                            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                                errorMsg = "Current language is not installed for offline recognition.";
                                break;
                            default:
                                errorMsg = "Speech recognition error (code " + error + "). Tap 🎤 to try again.";
                                break;
                        }
                        resultText.setText(errorMsg);
                    }

                    @Override
                    public void onResults(Bundle results) {
                        isListening = false;
                        // micButton text handled by XML
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String recognizedText = matches.get(0);
                            questionInput.setText(recognizedText);
                            questionInput.setSelection(recognizedText.length());
                            resultText.setText("Voice recognized: \"" + recognizedText + "\"\nReview/edit above, then tap ASK WORLD LENS.");
                        } else {
                            resultText.setText("No speech recognized. Tap 🎤 to try again.");
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
            // micButton text handled by XML
            resultText.setText("Listening (offline)... Speak your question now.");

        } catch (Exception e) {
            isListening = false;
            // micButton text handled by XML
            resultText.setText("Failed to start speech recognition: " + e.getMessage());
        }
    }

    private void stopListening() {
        if (speechRecognizer != null && isListening) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {}
        }
        isListening = false;
        // micButton text handled by XML
    }

    private void initHaptics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    private void triggerHaptic(HapticEvent event) {
        if (!isAccessibilityModeEnabled || vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = null;
                switch (event) {
                    case PROCESSING_STARTED:
                        // Short 50ms vibration pulse
                        effect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE);
                        break;
                    case ANSWER_COMPLETED:
                        // Confirmation double pulse: 50ms buzz, 80ms rest, 70ms buzz
                        effect = VibrationEffect.createWaveform(new long[]{0, 50, 80, 70}, -1);
                        break;
                    case ERROR:
                        // Distinct triple pulse warning: 80ms buzz, 80ms rest, 80ms buzz, 80ms rest, 120ms buzz
                        effect = VibrationEffect.createWaveform(new long[]{0, 80, 80, 80, 80, 120}, -1);
                        break;
                    case NEW_SCAN:
                        // Crisp 35ms tick pulse
                        effect = VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE);
                        break;
                }
                if (effect != null) {
                    vibrator.vibrate(effect);
                }
            } else {
                switch (event) {
                    case PROCESSING_STARTED:
                        vibrator.vibrate(50);
                        break;
                    case ANSWER_COMPLETED:
                        vibrator.vibrate(new long[]{0, 50, 80, 70}, -1);
                        break;
                    case ERROR:
                        vibrator.vibrate(new long[]{0, 80, 80, 80, 80, 120}, -1);
                        break;
                    case NEW_SCAN:
                        vibrator.vibrate(35);
                        break;
                }
            }
        } catch (Exception ignored) {}
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
    }

    private void speakText(String text) {
        if (textToSpeech != null && isTtsReady && text != null && !text.trim().isEmpty()) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "WorldLensTTS");
        }
    }

    private void stopSpeaking() {
        if (textToSpeech != null && isTtsReady) {
            textToSpeech.stop();
        }
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
            resultText.setText("Model loading failed:\n" + e.getMessage());
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
                resultText.setText("Failed to start camera:\n" + e.getMessage());
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
            if (!isCustomQuestionAsked) {
                runOnUiThread(() -> resultText.setText("Live Recognition:\n" + output.toString()));
            }

        } catch (Exception e) {
            if (!isCustomQuestionAsked) {
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
                    askButton.setText("IMPORT MODEL (.litertlm)");
                    askButton.setEnabled(true);
                    if (!isCustomQuestionAsked) {
                        resultText.setText("Gemma-4-E2B-it model not found in app storage.\n\nTap 'IMPORT MODEL' to select gemma-4-E2B-it.litertlm from your Downloads folder.");
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
            askButton.setEnabled(false);
            loadingProgressBar.setVisibility(View.VISIBLE);
            resultText.setText("Importing model from Downloads into app-private storage...\nPlease keep the app open.");
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
                        runOnUiThread(() -> resultText.setText("Importing model: " + mbCopied + " MB copied..."));
                    }
                }
                out.flush();

                gemmaModelFile = targetFile;
                runOnUiThread(() -> {
                    Toast.makeText(this, "Model imported successfully!", Toast.LENGTH_SHORT).show();
                    askButton.setText("ASK WORLD LENS");
                    askButton.setEnabled(true);
                });

                // Initialize the newly imported model
                initGemmaEngine(targetFile);

            } catch (Exception e) {
                isGemmaLoading = false;
                runOnUiThread(() -> {
                    askButton.setEnabled(true);
                    loadingProgressBar.setVisibility(View.GONE);
                    resultText.setText("Failed to import model: " + e.getMessage());
                });
            }
        });
    }

    private void initGemmaEngine(@NonNull File modelFile) {
        isGemmaLoading = true;
        runOnUiThread(() -> {
            loadingProgressBar.setVisibility(View.VISIBLE);
            if (!isCustomQuestionAsked) {
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
                    null, // maxNumImages (SDK/model default, NOT hard-coded)
                    getCacheDir().getAbsolutePath()
            );
            engine = new Engine(gpuConfig);
            engine.initialize();

            gemmaEngine = engine;
            llmBackend = new Backend.GPU();
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
                askButton.setText("ASK WORLD LENS");
                askButton.setEnabled(true);
                if (newScanButtonFromExport != null) newScanButtonFromExport.setEnabled(true);
                loadingProgressBar.setVisibility(View.GONE);
                if (!isCustomQuestionAsked) {
                    resultText.setText("Local Gemma-4-E2B-it ready (GPU)!\nPoint camera at an object and ask a question.");
                }
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
                if (!isCustomQuestionAsked) {
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
            llmBackend = new Backend.CPU();
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
                askButton.setText("ASK WORLD LENS");
                askButton.setEnabled(true);
                if (newScanButtonFromExport != null) newScanButtonFromExport.setEnabled(true);
                loadingProgressBar.setVisibility(View.GONE);
                if (!isCustomQuestionAsked) {
                    resultText.setText("Local Gemma-4-E2B-it ready (CPU fallback)!\nPoint camera at an object and ask a question.");
                }
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
                askButton.setEnabled(true);
                loadingProgressBar.setVisibility(View.GONE);
                resultText.setText("Model initialization failed on both GPU and CPU:\n" + cpuEx.getMessage());
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
        triggerHaptic(HapticEvent.NEW_SCAN);

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
                    questionInput.setText("");
                    askButton.setText("ASK WORLD LENS");
                    showCameraState();
                    if (resultText != null) resultText.setText("Ready for new scan.\nPoint camera at an object and ask a question.");
                    Toast.makeText(this, "New scan session started.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    resultText.setText("Failed to start new conversation:\n" + e.getMessage());
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

            MessageCallback callback = new MessageCallback() {
                @Override
                public void onMessage(@NonNull Message message) {
                    String chunk = extractTextFromMessage(message);
                    accumulatedResponse.append(chunk);
                    runOnUiThread(() -> {
                        if (resultText != null) resultText.setText("Thinking...");
                    });
                }

                @Override
                public void onDone() {
                    isInferenceRunning = false;
                    isFirstTurnOfScan = false;
                    
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
                    
                    triggerHaptic(HapticEvent.ANSWER_COMPLETED);
                    speakText(currentFullAnswer);
                    
                    runOnUiThread(() -> {
                        showResultState();
                        if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.GONE);
                        if (askButton != null) askButton.setEnabled(true);
                        if (newScanButtonFromExport != null) newScanButtonFromExport.setEnabled(true);
                        if (askButton != null) askButton.setText("ASK FOLLOW-UP");
                        if (questionInput != null) questionInput.setText("");
                        
                        if (resultText != null) resultText.setText(currentFullAnswer);
                        if (morseSummaryText != null) morseSummaryText.setText(currentMorseSummary);
                        if (morseCodeText != null) morseCodeText.setText(currentMorseCode);
                        if (questionEchoText != null) questionEchoText.setText(lastAskedQuestion);
                    });
                }

                @Override
                public void onError(@NonNull Throwable throwable) {
                    isInferenceRunning = false;
                    triggerHaptic(HapticEvent.ERROR);
                    runOnUiThread(() -> {
                        showErrorState("Inference error:\n" + throwable.getMessage());
                        if (askButton != null) askButton.setEnabled(true);
                        if (newScanButtonFromExport != null) newScanButtonFromExport.setEnabled(true);
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
            triggerHaptic(HapticEvent.ERROR);
            runOnUiThread(() -> {
                showErrorState("Failed to process question:\n" + e.getMessage());
                if (askButton != null) askButton.setEnabled(true);
                if (newScanButtonFromExport != null) newScanButtonFromExport.setEnabled(true);
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
            // micButton text handled by XML
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {}
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
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {}
            vibrator = null;
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
}