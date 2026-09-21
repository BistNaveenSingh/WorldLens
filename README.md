# World Lens

**See the world, hear it in Morse.**

World Lens is a premium Android application that uses on-device Vision-Language Models (Gemma) to analyze your surroundings through your phone's camera and instantly translate the visual context into a concise Morse code audio summary. 

## 🚀 Features

- **On-Device AI Vision**: Runs the Gemma VLM (`gemma-4-E2B-it.litertlm`) completely locally. No cloud APIs, no network latency, complete privacy.
- **Intelligent Summarization**: Parses complex visual scenes and distills them into a 7-word (or less) "Morse Summary Text" optimized for audio transmission.
- **Morse Code Engine**: Translates the AI's summary text into accurate International Morse Code (`.-.. ..- -...`) and plays it back using a custom `MorseSoundPlayer`.
- **Modern 3-State UI**: A sleek, accessible White Theme interface built with Material 3 components:
  1. **Camera State**: Real-time viewfinder with a prominent capture action.
  2. **Result State**: Displays the full detailed AI answer, the extracted concise summary, and the translated Morse code alongside playback controls.
  3. **Export State**: Beautifully designed cards to copy the exact synced result to the clipboard or export it as a Markdown report.
- **Accessibility First**: Includes dedicated accessibility toggles and high-contrast UI design.

## 🧠 How It Works (The Architecture)

World Lens operates on a strict data pipeline to ensure what you read is exactly what you hear and export:

```text
Gemma VLM (Local Inference)
   ↓
Full Descriptive Answer
   ↓
Compact Morse Summary Text (Max 7 words)
   ↓
Morse Encoder (Text -> Dots/Dashes)
   ↓
Morse Sound Player (Audio Generation)
   ↓
Export & Share (Clipboard / Markdown)
```

1. **Capture**: The user takes a photo using the `androidx.camera` API.
2. **Inference**: The image is fed into the local Gemma model with a prompt designed to enforce a strict two-part output format (`FULL ANSWER:` and `MORSE SUMMARY TEXT:`).
3. **Extraction**: `MainActivity.java` parses the LLM output, isolating the concise summary from the conversational answer.
4. **Encoding**: The `MorseEncoder` converts the short summary into Morse code characters.
5. **Playback**: The `MorseSoundPlayer` generates precise sine wave audio tones for the dots and dashes.
6. **Export**: The user can export a 1:1 match of the answer, summary, and Morse code.

## 🎨 UI & Design

World Lens features a bespoke "White Theme" emphasizing clarity, premium feel, and readability.

- **Primary**: Vibrant Purple/Blue (`#6B4EFF`)
- **Background**: Off-White (`#F8F9FA`)
- **Surfaces**: Pure White (`#FFFFFF`) with subtle strokes
- **Text**: High contrast dark gray (`#202124`)

## 🛠️ Tech Stack

- **Platform**: Android (Java)
- **UI Framework**: XML, Material Components (Material3)
- **AI / ML**: MediaPipe / LiteRT / Gemma VLM
- **Camera**: CameraX API
- **Audio**: AudioTrack API (Low-latency tone generation)

## 📦 Getting Started

1. Clone the repository.
2. Open the project in Android Studio.
3. Ensure you have the `gemma-4-E2B-it.litertlm` model file (this must be downloaded and placed in the appropriate assets/files directory or pushed to the device's `getFilesDir()` via ADB).
4. Build the project using Gradle (`./gradlew assembleDebug`).
5. Deploy to a physical device (Emulators may struggle with local VLM inference performance).
