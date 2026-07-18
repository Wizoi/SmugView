# 📷 SmugView Android App

SmugView is a premium, high-performance Android application designed for browsing public SmugMug galleries. Built with Kotlin and modern Jetpack Compose, the app delivers a fluid, gesture-driven media exploration experience, support for secure password-protected folders and albums, and advanced client-side tag filtering.

---

## 🚀 First-Time Setup & API Configuration

To keep credentials secure, this project uses a build-time configuration system that reads credentials from a local file and compiles them into a secure, non-version-controlled class (`BuildConfig`). Follow these steps to set up the project:

### Step 1: Obtain a SmugMug API Key
1. Go to the [SmugMug Developer Portal](https://api.smugmug.com/api/developer/apply).
2. Register your application to obtain a developer **API Key** (also known as a consumer key).

### Step 2: Set up `local.properties`
1. Locate the [local.properties.example](../local.properties.example) template file in the project root.
2. Duplicate this file and rename the copy to `local.properties` in the project root directory.
3. Open the newly created `local.properties` and fill in your details:
   ```properties
   # Your SmugMug API Key
   smugmug.api.key=YOUR_API_KEY_HERE

   # Your target SmugMug nickname/profile to load on launch
   smugmug.nickname=YOUR_NICKNAME_HERE
   ```
4. *Security Check:* The root `local.properties` and `app/local.properties` are pre-configured in [../.gitignore](../.gitignore) to ensure they are **never** accidentally pushed to GitHub.

---

## 🛠️ Build and Compilation

The project uses Gradle 8.13 (see `gradle/wrapper/gradle-wrapper.properties`) and is configured with standard Android build wrappers.

### Building via Android Studio
1. Open Android Studio (Iguana / Jellyfish or newer recommended).
2. Select **Open** and choose the root directory of this project (`SmugView`).
3. Let Gradle sync and compile the project. Android Studio will automatically resolve your local Android SDK location (`sdk.dir`) in `local.properties`.
4. Click the **Run** button to deploy the app to an emulator or physical device.

### Building via Command Line
Run the following build command from the root directory. Note that you must have `JAVA_HOME` configured or use the bundled Java runtime from Android Studio:
* **Windows (PowerShell):**
  If `JAVA_HOME` is not set in your environment:
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  .\gradlew.bat assembleDebug
  ```
* **Windows (CMD):**
  ```cmd
  set JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  gradlew.bat assembleDebug
  ```
* **macOS / Linux:**
  ```bash
  chmod +x gradlew
  ./gradlew assembleDebug
  ```

---

## 📚 Key Project Documentation

For deeper details on design, mechanics, and integration details, consult the following documents:

*   **[DESIGN.md](DESIGN.md)**: Details the UX/UI layout specifications, custom client-side tag exclusion/inclusion formulas, and the Room database schema for custom offline collections.
*   **[SMUGMUG.md](SMUGMUG.md)**: Documents API endpoints, expansion parameters (to prevent N+1 queries), password-unlock logic via POST requests, and Retrofit/OkHttp cookie persistence implementations.
*   **[PUBLISH.md](PUBLISH.md)**: Google Play store listing content (title/descriptions/graphics/screenshots), the Play Console steps that can't be scripted (data safety, content rating, closed-testing gate), and the publish commands themselves.
*   **[PRIVACY_POLICY.md](PRIVACY_POLICY.md)**: The public-facing privacy policy required by Play Console's App Content section. Keep this in sync if data handling (storage, permissions, third-party SDKs) ever changes.
*   **[.gitignore](.gitignore)**: Standard Android ignore file ensuring cache files, IDE outputs, and credentials stay off public repositories.

---

## ⚙️ Architecture & Technology Stack
*   **UI Framework**: Jetpack Compose with Material 3 (custom dark aesthetic).
*   **Networking**: Retrofit 2 & OkHttp 4, with an automatic 429/5xx retry+backoff interceptor.
*   **Data Caching**: Room DB for offline lists/collections & Coil for smart image loading.
*   **Concurrency & Flow**: Kotlin Coroutines & Flows for reactive state management.
*   **Media & Casting**: `androidx.media3`/ExoPlayer for in-gallery video, Google Cast framework for Chromecast, plus a local HTTP server ("Web Companion") for Roku/Fire TV casting — see the "Casting & the Web Companion Server" section in [DESIGN.md](DESIGN.md).

For the fuller picture beyond this quick-reference list — including the ViewModel/controller split, the folder update-indicator feature, and the casting subsystem — see [DESIGN.md](DESIGN.md).
