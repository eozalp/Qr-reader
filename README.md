# QR Live Calculator – Android Application

An augmented reality (AR) Android application that continuously scans multiple QR codes via CameraX and Google ML Kit, parses user-defined structured values, calculates customizable arithmetic formulas in real-time, and projects dynamic, CSS-styled visual overlays directly onto detected QR codes in the live camera preview.

## Architecture & Technology Stack

- **Platform:** Native Android (Min SDK 26, Target SDK 36)
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose & Material Design 3
- **Vision & AR Detection:** CameraX (`PreviewView`, `ImageAnalysis`) & Google ML Kit Barcode Scanning
- **Mathematical Engine:** Safe Recursive-Descent Expression Parser supporting `+`, `-`, `*`, `/`, `%`, `^`, `(...)`, `%` postfix, and functions (`abs`, `sqrt`, `min`, `max`, `avg`, `round`)
- **Spatial Tracking Engine:** Multi-QR tracking with Exponential Moving Average (EMA) coordinate smoothing and configurable disappearance fade timeout
- **Persistence:** AndroidX DataStore for user configurations, custom profiles, and formatting presets
- **Asynchronous Flow:** Kotlin Coroutines & StateFlow

## Key Features

1. **Automatic Full-Screen Scanner:**
   - Zero-tap scanning: opens camera preview and tracks QR codes instantly.
   - Live AR corner reticle and dynamic overlays attached to each QR code.
   - Smooth movement tracking without jitter or screen flicker.

2. **Customizable QR Parsing Engine:**
   - Configurable delimiters (`-`, `,`, `;`, `|`, `/`, space, or custom string).
   - Position-based variable extraction (`a`, `b`, `c`, `d`, or named aliases such as `price`, `tax`, `qty`).
   - Supports integers, floats, doubles, optional fields with default values, and min/max range limits.

3. **Arithmetic Formula Engine:**
   - Formula editor with variable picker chips and quick operator toolbar.
   - Comprehensive operator precedence and division-by-zero protection.
   - Live Formula Tester inside Settings for instant formula verification.

4. **Advanced CSS-Style Visual Overlay Editor:**
   - Anchor positioning (Center, Above, Below, Top-Left, Top-Right, Bottom-Left, Bottom-Right, Custom X/Y offsets).
   - Screen edge avoidance (auto-clamping to screen viewport).
   - Real-time interactive preview card in Settings.
   - Customizable typography, gradients, opacity, solid/dashed borders, drop shadows, and glow effects.

5. **Multi-QR Concurrent Tracking:**
   - Identifies and calculates multiple QR codes in the same frame simultaneously.
   - Smooth disappearance timeout keeps overlays stable during temporary camera obstruction.

6. **Configuration Profiles & Presets:**
   - Built-in presets: Quick Sum, Price & Tax Calculator, Discount Calculator (₺ TL), Package Volume (L-W-H).
   - Create, duplicate, rename, delete, and switch active profiles.
   - JSON export and import capabilities.

## Build & Run Instructions

```bash
# Build the debug APK
gradle assembleDebug

# Run unit and Robolectric tests
gradle testDebugUnitTest
```
