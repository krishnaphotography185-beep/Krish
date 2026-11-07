# JARVIS Android AI Assistant

## Review status

The source changes implement Android 8.0+ support (minSdk 26), network calls, AI project generation, ZIP transfer through Android's document picker, and video job polling/preview/download for providers following the API contract below. **This repository has not been built or device-tested in this environment. Do not treat this commit as a verified release until you run the checks below.**

## Build and install debug APK (itel P40)

1. Install Android Studio stable, Android SDK Platform 35, and JDK 17. Install Node.js 20+ for backend use.
2. Clone/download this repository and open its root folder in Android Studio. Wait for Gradle sync; install any prompted SDK components.
3. Configure the backend URL in root `local.properties` (Android Studio can create it):

   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   JARVIS_API_BASE_URL=https://your-backend.example.com
   ```

   For Android emulator only, `http://10.0.2.2:3000` is the default. For local phone testing, use the computer's LAN IP and same Wi-Fi; the phone cannot use computer `localhost`. Use HTTPS for production.

4. Build the backend if using AI/weather: in `backend`, copy `.env.example` to `.env`, configure secrets, then run `npm install` and `npm start`. Do not commit `.env` or ship provider keys in the APK.
5. In Android Studio choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**, or run `gradle :app:assembleDebug` from the root (with Android SDK/JDK configured).
6. APK path: `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the itel P40. On the phone, open the APK using Files/Downloads, allow **Install unknown apps** for that app when prompted, and install. Alternatively enable USB debugging and use `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
7. Verify on device: launch JARVIS, test backend health, text chat, weather, mic permission/voice recognition, app shortcuts and project generation/ZIP save. Test video only with a real compatible provider account.

The itel P40 uses Android 12 in common regional variants; the app's minSdk 26 also covers Android 8.0+ devices. Actual installation depends on device storage, OS build, ABI, and successful Gradle compilation.

## AI backend

```bash
cd backend
cp .env.example .env
# Set AI_API_KEY; optionally change AI_MODEL and AI_BASE_URL
npm install
npm start
```

The backend stores the provider key, not the Android client. AI and image usage may be billed. The sample backend has rate limiting but no user authentication; add authentication/user quotas before public deployment.

## Video provider contract

Configure `VIDEO_API_URL` (job creation endpoint) and `VIDEO_API_KEY` on the backend. Creation must accept JSON `{prompt,duration,aspect_ratio,quality,image_url?}` with bearer auth and return `{id,status?,output_url?}`. Status polling calls `GET VIDEO_API_URL/{id}` with bearer auth; return `{status,output_url?,video_url?,error?}`. Output video must be reachable over HTTPS. Image-to-video requires a public HTTPS image URL. API-specific duration, ratios, image input, quality and billing vary; provider errors are shown in the app.

## API endpoint checks

- `GET /health`
- `POST /api/chat` `{message,history?}`
- `POST /api/code` `{prompt}`
- `POST /api/projects/generate` `{kind:"android"|"website",prompt}`
- `POST /api/projects/zip` `{files:[{path,content}]}` returns `application/zip`
- `POST /api/image` `{prompt}` (provider must support images)
- `POST /api/video` `{prompt,duration,aspect_ratio,quality,image_url?}`
- `GET /api/video/:id`
- `GET /api/weather?city=Delhi`

No CI/device build was run by this code edit. Run `node --check backend/server.js` and `npm install`/`npm start`, then test endpoints. Run `gradle :app:assembleDebug` or Android Studio Build APK before claiming a successful build. Generated code is untrusted and must be reviewed before compiling/deploying.
