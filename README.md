# JARVIS Android AI Assistant

Native Kotlin/Jetpack Compose Android client plus a Node.js backend. Dashboard modules include AI Chat, Video Generator, App Builder, Website Builder, Code Assistant, Image Generator, My Projects and Settings. Generated app/site/code projects are AI-produced files that can be exported as ZIP. Video and image generation require configured external providers; these are not bundled or free by default.

## Development and run steps

1. Install Android Studio (stable), Android SDK 35, JDK 17, Node.js 20+.
2. In `backend`, copy `.env.example` to `.env`, set `AI_API_KEY` and model details, then `npm install && npm start`.
3. For emulator testing the default API is `http://10.0.2.2:3000`. For a phone set `JARVIS_API_BASE_URL` in the root `local.properties` to a reachable HTTPS backend URL (or your computer's LAN IP for local testing). Do not use computer `localhost` on the phone.
4. Open the root folder in Android Studio, sync, then run `app` on your Android device. Chat, coding, image, and project generation require the backend and AI provider.
5. Create APK: Android Studio > **Build > Build Bundle(s) / APK(s) > Build APK(s)**, or run `gradle :app:assembleDebug`. Output: `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the itel P40 and open it to install (allow installs from that file app when prompted). `adb install -r app/build/outputs/apk/debug/app-debug.apk` also works with USB debugging enabled.
6. For a distributable release choose **Build > Generate Signed Bundle / APK > APK**, create a signing key and keep it safe/off the repository. Debug builds are for testing.

## Feature setup and limitations

- **AI Chat / Code Assistant / App Builder / Website Builder:** OpenAI-compatible Chat Completions backend. Key stays in backend environment. Provider usage can incur charges. App/site source is generated text, not guaranteed to compile or be secure; review and test before use.
- **ZIP export:** backend assembles only safe, relative file paths and returns a ZIP. Keep generated projects untrusted until reviewed.
- **AI Video Generator:** set `VIDEO_API_URL` and `VIDEO_API_KEY` for a provider implementing the documented async request contract below. The API must accept JSON `{prompt,duration,aspect_ratio,quality,image_url?}` and return `{id,status?,output_url?}`. Poll `GET {VIDEO_API_URL}/{id}` and return `{status,output_url?,error?}`. Image input must be an HTTPS URL reachable by the provider. Provider usage is usually paid; provider/model availability and duration limits vary.
- **AI Image Generator:** uses the configured OpenAI-compatible `/images/generations` endpoint; the provider/model must support it and may charge per image.
- Weather uses Open-Meteo. Time/battery are on-device. Search and app links open external apps/pages.
- Speech uses Android speech recognition and installed TTS voices; Hindi/English availability depends on the device's recognition/TTS provider. Audio is not uploaded; recognized text is submitted as a chat prompt.

## Backend

```bash
cd backend
cp .env.example .env
# edit .env; never commit it
npm install
npm start
```

Endpoints: `GET /health`, `POST /api/chat`, `POST /api/code`, `POST /api/projects/generate`, `POST /api/projects/zip`, `POST /api/image`, `POST /api/video`, `GET /api/video/:id`, `GET /api/weather?city=Delhi`.

Before public deployment use HTTPS, authentication, user-level quotas, billing controls, request logging without secrets, and a secret manager. The built-in rate limiter is not authentication. Never put provider keys in Android resources or BuildConfig.
