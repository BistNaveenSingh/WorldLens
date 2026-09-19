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
    private Button micButton;
    private Button askButton;
    private Button newScanButton;
    private ProgressBar loadingProgressBar;
    private ExecutorService cameraExecutor;

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
                    resultText.setText("Camera permission denied.");
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
        newScanButton = findViewById(R.id.newScanButton);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        accessibilityLabel = findViewById(R.id.accessibilityLabel);
        accessibilitySwitch = findViewById(R.id.accessibilitySwitch);

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
        accessibilitySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isAccessibilityModeEnabled = isChecked;
            accessibilityLabel.setText(isChecked ? "[ Accessibility Mode: ON ]" : "[ Accessibility Mode: OFF ]");
            if (isChecked) {
                resultText.setTextSize(18);
                triggerHaptic(HapticEvent.PROCESSING_STARTED);
                Toast.makeText(this, "Accessibility Mode enabled (Haptics ON)", Toast.LENGTH_SHORT).show();
            } else {
                resultText.setTextSize(15);
                if (vibrator != null) {
                    vibrator.cancel();
                }
                Toast.makeText(this, "Accessibility Mode disabled", Toast.LENGTH_SHORT).show();
            }
        });

        newScanButton.setOnClickListener(v -> resetConversation());

        askButton.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
            }

            if (isInferenceRunning) {
                Toast.makeText(this, "Thinking... please wait for the current answer.", Toast.LENGTH_SHORT).show();
                return;
            }

            // If model is missing or not ready, let user pick/import it from Downloads folder
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
                Toast.makeText(this, "Please type a question or tap the microphone.", Toast.LENGTH_SHORT).show();
                return;
            }

            isCustomQuestionAsked = true;
            isInferenceRunning = true;
            loadingProgressBar.setVisibility(View.VISIBLE);
            askButton.setEnabled(false);
            newScanButton.setEnabled(false);
            stopSpeaking();
            triggerHaptic(HapticEvent.PROCESSING_STARTED);

            if (isFirstTurnOfScan) {
                resultText.setText("Analyzing image & answering...");

                // Capture ONE camera frame snapshot from the active preview (leaves CameraX untouched)
                Bitmap capturedBitmap = viewFinder.getBitmap();
                if (capturedBitmap == null) {
                    // Fallback to local cat.jpg test image if camera preview is unavailable
                    capturedBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.cat);
                }

                final Bitmap imageForInference = capturedBitmap;
                vlmExecutor.execute(() -> runGemmaTurn(question, imageForInference, true));
            } else {
                resultText.setText("Thinking (using conversation history)...");
                vlmExecutor.execute(() -> runGemmaTurn(question, null, false));
            }
        });

        micButton.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
                return;
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });
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
                        micButton.setText("⏹");
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
                        micButton.setText("🎤");
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
                        micButton.setText("🎤");
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
            micButton.setText("⏹");
            resultText.setText("Listening (offline)... Speak your question now.");

        } catch (Exception e) {
            isListening = false;
            micButton.setText("🎤");
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
        micButton.setText("🎤");
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
                newScanButton.setEnabled(true);
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
                newScanButton.setEnabled(true);
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
                    resultText.setText("Ready for new scan.\nPoint camera at an object and ask a question.");
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

            final StringBuilder accumulatedResponse = new StringBuilder();

            MessageCallback callback = new MessageCallback() {
                @Override
                public void onMessage(@NonNull Message message) {
                    String chunk = extractTextFromMessage(message);
                    accumulatedResponse.append(chunk);
                    runOnUiThread(() -> {
                        resultText.setText(accumulatedResponse.toString());
                    });
                }

                @Override
                public void onDone() {
                    isInferenceRunning = false;
                    isFirstTurnOfScan = false;
                    triggerHaptic(HapticEvent.ANSWER_COMPLETED);
                    speakText(accumulatedResponse.toString());
                    runOnUiThread(() -> {
                        loadingProgressBar.setVisibility(View.GONE);
                        askButton.setEnabled(true);
                        newScanButton.setEnabled(true);
                        askButton.setText("ASK FOLLOW-UP");
                        questionInput.setText("");
                    });
                }

                @Override
                public void onError(@NonNull Throwable throwable) {
                    isInferenceRunning = false;
                    triggerHaptic(HapticEvent.ERROR);
                    runOnUiThread(() -> {
                        loadingProgressBar.setVisibility(View.GONE);
                        askButton.setEnabled(true);
                        newScanButton.setEnabled(true);
                        resultText.setText("Inference error:\n" + throwable.getMessage());
                    });
                }
            };

            if (isFirstTurn && bitmap != null) {
                // Write image to a temporary cache file for LiteRT-LM
                File cacheImage = new File(getCacheDir(), "vlm_input.jpg");
                try (FileOutputStream fos = new FileOutputStream(cacheImage)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                }

                // Create multimodal message (Image + Question) for Turn 1
                Content imageContent = new Content.ImageFile(cacheImage.getAbsolutePath());
                Content textContent = new Content.Text(question);
                Contents contents = Contents.Companion.of(imageContent, textContent);

                gemmaConversation.sendMessageAsync(contents, callback);
            } else {
                // Follow-up message sends text only to the active conversation
                gemmaConversation.sendMessageAsync(question, callback);
            }

        } catch (Exception e) {
            isInferenceRunning = false;
            triggerHaptic(HapticEvent.ERROR);
            runOnUiThread(() -> {
                loadingProgressBar.setVisibility(View.GONE);
                askButton.setEnabled(true);
                newScanButton.setEnabled(true);
                resultText.setText("Failed to process question:\n" + e.getMessage());
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
            micButton.setText("🎤");
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