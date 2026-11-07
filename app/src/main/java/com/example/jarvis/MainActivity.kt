package com.example.jarvis

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.speech.*
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()
private val jsonType = "application/json; charset=utf-8".toMediaType()
private val sections = listOf("AI Chat", "Video Generator", "App Builder", "Website Builder", "Code Assistant", "Image Generator", "My Projects", "Settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { JarvisHome() } }
}

@Composable
fun JarvisHome() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("assistant_name", "JARVIS") ?: "JARVIS") }
    var assistantEdit by remember { mutableStateOf(name) }
    var selected by remember { mutableStateOf("AI Chat") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Namaste! Main JARVIS hoon. Hindi, English ya Hinglish mein poochhein.") }
    var loading by remember { mutableStateOf(false) }
    var city by remember { mutableStateOf("Delhi") }
    var seconds by remember { mutableStateOf("5") }
    var ratio by remember { mutableStateOf("9:16") }
    var quality by remember { mutableStateOf("standard") }
    var imageUrl by remember { mutableStateOf("") }
    var filesJson by remember { mutableStateOf("[]") }
    var videoId by remember { mutableStateOf("") }
    var videoStatus by remember { mutableStateOf("") }
    var videoUrl by remember { mutableStateOf("") }
    var zipBytes by remember { mutableStateOf<ByteArray?>(null) }
    var speech by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var listening by remember { mutableStateOf(false) }

    val zipSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = zipBytes
        if (uri != null && bytes != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open destination") }
                output = "ZIP saved successfully. Open it with a file manager to inspect the project."
            } catch (e: Exception) { output = "Could not save ZIP: ${e.message}" }
            finally { zipBytes = null }
        } else if (uri == null) { zipBytes = null; output = "ZIP save cancelled." }
    }

    DisposableEffect(Unit) {
        speech = if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null
        tts = TextToSpeech(ctx) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale("en", "IN") }
        onDispose { speech?.destroy(); tts?.shutdown() }
    }

    fun apiUrl(path: String) = BuildConfig.API_BASE_URL.trimEnd('/') + path
    fun requestJson(path: String, data: JSONObject, callback: (JSONObject) -> Unit) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(apiUrl(path)).post(data.toString().toRequestBody(jsonType)).build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val parsed = runCatching { JSONObject(body) }.getOrElse { JSONObject().put("error", body.ifBlank { "Invalid server response" }) }
                        if (!response.isSuccessful) error(parsed.optString("error", "Request failed (${response.code})"))
                        parsed
                    }
                }
                callback(result)
            } catch (e: Exception) { output = "Request failed: ${e.message ?: "Could not reach backend"}. Check backend URL, network and provider setup." }
            finally { loading = false }
        }
    }

    fun saveVideo(url: String) {
        if (!url.startsWith("https://", true)) { output = "The provider did not return a secure HTTPS video URL."; return }
        try {
            val req = DownloadManager.Request(Uri.parse(url)).setTitle("JARVIS generated video")
                .setDescription("Downloading generated video")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "jarvis-${System.currentTimeMillis()}.mp4")
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            output = "Video download started. Check Downloads/notification for progress."
        } catch (e: Exception) { output = "Could not start video download: ${e.message}" }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) { output = "Microphone permission denied. Enable it from Android Settings to use voice input."; return@rememberLauncherForActivityResult }
        val recognizer = speech
        if (recognizer == null) { output = "Speech recognition is not available on this device."; return@rememberLauncherForActivityResult }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; output = "Speech recognition failed (code $error). Check your language/voice service and try again." }
            override fun onResults(results: Bundle?) { listening = false; input = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try { recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())) }
        catch (e: Exception) { listening = false; output = "Could not start speech recognition: ${e.message}" }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF53DDF7), background = Color(0xFF06111D), surface = Color(0xFF102333))) {
        Column(Modifier.fillMaxSize().background(Color(0xFF06111D)).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(name, color = Color(0xFF9AF0FF), fontSize = 25.sp, fontWeight = FontWeight.Bold); Text(if (loading) "PROCESSING…" else "PERSONAL AI SYSTEM • READY", color = Color(0xFF7296A9), fontSize = 9.sp, letterSpacing = 1.sp) }
                Orb(loading || listening, Modifier.size(48.dp))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) { items(sections) { s -> FilterChip(selected == s, { selected = s }, label = { Text(s, maxLines = 1) }) } }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (selected) {
                    "Settings" -> {
                        Field("Assistant display name", assistantEdit) { assistantEdit = it }
                        Action("Save settings") { name = assistantEdit.trim().ifBlank { "JARVIS" }; prefs.edit().putString("assistant_name", name).apply(); output = "Assistant name saved: $name" }
                        Info("Backend: ${BuildConfig.API_BASE_URL}\nKeep API keys on backend only. Video/image providers may charge.")
                        Action("Check backend") {
                            if (loading) return@Action
                            loading = true
                            scope.launch { try { output = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(apiUrl("/health")).get().build()).execute().use { r -> if (!r.isSuccessful) error("Health check failed (${r.code})"); r.body?.string().orEmpty() } } } catch (e: Exception) { output = "Backend check failed: ${e.message}" } finally { loading = false } }
                        }
                    }
                    "AI Chat" -> {
                        Info("Chat requests go to your configured AI backend. Usage may incur provider charges.")
                        FeatureInput(input) { input = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("Send") { val prompt = input.trim(); if (prompt.isNotEmpty()) requestJson("/api/chat", JSONObject().put("message", prompt).put("history", org.json.JSONArray())) { output = it.optString("reply", "No reply received."); val locale = if (output.any { c -> c in 'ऀ'..'ॿ' }) Locale("hi", "IN") else Locale("en", "IN"); tts?.language = locale; tts?.speak(output, TextToSpeech.QUEUE_FLUSH, null, "reply") } }
                            Action(if (listening) "Listening…" else "Speak") { if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) mic.launch(Manifest.permission.RECORD_AUDIO) else mic.launch(Manifest.permission.RECORD_AUDIO) }
                        }
                        Action("Time and battery") { val pct = (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); output = "${java.text.SimpleDateFormat("hh:mm a, EEEE", Locale.getDefault()).format(java.util.Date())} • battery $pct%" }
                    }
                    "Video Generator" -> {
                        Info("Requires an external async video API configured on backend (VIDEO_API_URL/VIDEO_API_KEY). Charges and supported options vary by provider.")
                        Field("Video prompt (Hindi or English)", input, 3) { input = it }
                        Field("Optional public HTTPS source image URL", imageUrl) { imageUrl = it }
                        Field("Duration (1–60 seconds)", seconds) { seconds = it }
                        Field("Aspect ratio (e.g. 9:16, 16:9, 1:1)", ratio) { ratio = it }
                        Field("Quality", quality) { quality = it }
                        Action("Generate video") {
                            if (input.isBlank()) { output = "Enter a video prompt first." }
                            else requestJson("/api/video", JSONObject().put("prompt", input).put("duration", seconds.toIntOrNull() ?: 0).put("aspect_ratio", ratio).put("quality", quality).put("image_url", imageUrl)) {
                                videoId = it.optString("id"); videoStatus = it.optString("status", "submitted"); videoUrl = it.optString("output_url")
                                output = if (videoId.isBlank()) "Video provider returned no job ID. Check backend provider response contract." else "Video job $videoId: $videoStatus"
                                if (videoUrl.isNotBlank()) output += "\nVideo ready. Use Preview or Save below."
                            }
                        }
                        if (videoId.isNotBlank()) {
                            Text("Job $videoId • $videoStatus", color = Color.Cyan, modifier = Modifier.padding(vertical = 8.dp))
                            Action("Check status now") { pollVideo(videoId, ::requestJson, { status, url, err -> videoStatus = status; if (url.isNotBlank()) videoUrl = url; output = err.ifBlank { "Video status: $status${if (videoUrl.isNotBlank()) "\nVideo is ready." else ""}" } }) }
                            Action("Poll until ready") {
                                scope.launch {
                                    repeat(60) {
                                        pollVideoSuspend(videoId, apiUrl)?.let { (status, url, err) ->
                                            videoStatus = status; if (url.isNotBlank()) videoUrl = url
                                            output = err.ifBlank { "Video status: $status" }
                                            if (status.lowercase() in listOf("completed", "complete", "succeeded", "success", "ready", "failed", "error", "cancelled", "canceled") || videoUrl.isNotBlank()) return@launch
                                        } ?: run { output = "Could not retrieve video job status."; return@launch }
                                        delay(5000)
                                    }
                                    output = "Still processing after 5 minutes. Check status again later."
                                }
                            }
                            if (videoUrl.isNotBlank()) {
                                Action("Preview video") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { output = "Unable to open video preview: ${it.message}" } }
                                Action("Save video to Downloads") { saveVideo(videoUrl) }
                            }
                        }
                    }
                    "App Builder", "Website Builder", "Code Assistant" -> {
                        val web = selected == "Website Builder"; val code = selected == "Code Assistant"
                        Info(when { web -> "Generate website project files. Review and test code before publishing."; code -> "Coding helper for Kotlin, Java, HTML, CSS, JavaScript and Python."; else -> "Generate Android Kotlin/Compose project files. Open generated files in Android Studio and build/test them." })
                        Field(if (code) "Code question, task or error" else "Describe your project", input, 5) { input = it }
                        Action(if (code) "Ask coding assistant" else if (web) "Generate website project" else "Generate Android app project") {
                            if (input.isBlank()) output = "Enter a description first."
                            else if (code) requestJson("/api/code", JSONObject().put("prompt", input)) { output = it.optString("answer", "No answer returned."); filesJson = it.optJSONArray("files")?.toString(2) ?: "[]" }
                            else requestJson("/api/projects/generate", JSONObject().put("kind", if (web) "website" else "android").put("prompt", input)) { val files = it.optJSONArray("files"); filesJson = files?.toString(2) ?: "[]"; output = "${it.optString("summary")}\nGenerated ${files?.length() ?: 0} files. Go to My Projects to inspect and export a ZIP." }
                        }
                    }
                    "Image Generator" -> {
                        Info("Requires a configured image-capable AI provider and may incur charges.")
                        Field("Describe the image", input, 4) { input = it }
                        Action("Generate image") { requestJson("/api/image", JSONObject().put("prompt", input)) { val url = it.optString("image_url"); output = if (url.startsWith("https://")) "Generated image: $url" else "Provider returned no secure image URL."; if (url.startsWith("https://")) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } }
                    }
                    "My Projects" -> {
                        Info("Project files generated in this session. JSON is retained while this screen is open. Export ZIP downloads through Android's Save dialog.")
                        Field("Project files JSON", filesJson, 8) { filesJson = it }
                        Action("Download project ZIP") {
                            val files = try { org.json.JSONArray(filesJson) } catch (_: Exception) { output = "Project file list is invalid JSON."; null }
                            if (files != null) {
                                loading = true
                                scope.launch { try {
                                    val bytes = withContext(Dispatchers.IO) {
                                        val req = Request.Builder().url(apiUrl("/api/projects/zip")).post(JSONObject().put("files", files).toString().toRequestBody(jsonType)).build()
                                        http.newCall(req).execute().use { r -> val b = r.body?.bytes() ?: byteArrayOf(); if (!r.isSuccessful) error(runCatching { JSONObject(b.toString(Charsets.UTF_8)).optString("error") }.getOrDefault("ZIP export failed (${r.code})")); if (b.isEmpty()) error("Server returned an empty ZIP"); b }
                                    }
                                    zipBytes = bytes; zipSaver.launch("jarvis-project.zip")
                                } catch (e: Exception) { output = "ZIP download failed: ${e.message}" } finally { loading = false } }
                            }
                        }
                    }
                    else -> {
                        Info("Weather uses Open-Meteo. App shortcuts open installed apps or their web pages.")
                        Field("Weather city", city) { city = it }
                        Action("Get weather") { if (loading) return@Action; loading = true; scope.launch { try { output = withContext(Dispatchers.IO) { val url = apiUrl("/api/weather?city=${URLEncoder.encode(city, "UTF-8")}"); http.newCall(Request.Builder().url(url).get().build()).execute().use { r -> val body = r.body?.string().orEmpty(); if (!r.isSuccessful) error(runCatching { JSONObject(body).optString("error") }.getOrDefault("Weather failed")); JSONObject(body).optString("summary", body) } } } catch (e: Exception) { output = "Weather error: ${e.message}" } finally { loading = false } } }
                        FlowRowCompat { listOf("YouTube", "WhatsApp", "Instagram", "Chrome", "Google").forEach { app -> Action(app) { openApp(ctx, app) } } }
                    }
                }
            }
            Text(if (loading) "WORKING…" else "JARVIS RESPONSE", color = Color(0xFF57D9F3), fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 180.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF102333)).verticalScroll(rememberScrollState()).padding(14.dp)) { SelectionContainer { Text(output, color = Color(0xFFE2F6FF), fontSize = 14.sp) } }
        }
    }
}

private fun pollVideo(id: String, requestJson: (String, JSONObject, (JSONObject) -> Unit) -> Unit, done: (String, String, String) -> Unit) {
    requestJson("/api/video/${Uri.encode(id)}", JSONObject()) { done(it.optString("status", "unknown"), it.optString("output_url"), it.optString("error")) }
}
private suspend fun pollVideoSuspend(id: String, apiUrl: (String) -> String): Triple<String, String, String>? = withContext(Dispatchers.IO) {
    runCatching {
        http.newCall(Request.Builder().url(apiUrl("/api/video/${Uri.encode(id)}")).get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty(); val data = JSONObject(body)
            if (!r.isSuccessful) error(data.optString("error", "Status request failed (${r.code})"))
            Triple(data.optString("status", "unknown"), data.optString("output_url"), data.optString("error"))
        }
    }.getOrNull()
}

@Composable private fun Info(text: String) { Text(text, color = Color(0xFF9CBAC8), fontSize = 12.sp, modifier = Modifier.padding(vertical = 7.dp)) }
@Composable private fun Field(label: String, value: String, lines: Int = 1, change: (String) -> Unit) { OutlinedTextField(value, change, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) }, minLines = lines, maxLines = if (lines == 1) 1 else 8) }
@Composable private fun FeatureInput(value: String, change: (String) -> Unit) = Field("Message in Hindi / English / Hinglish", value, 3, change)
@Composable private fun Action(label: String, click: () -> Unit) { Button(onClick = click, modifier = Modifier.padding(vertical = 4.dp), enabled = label != "Listening…") { Text(label, maxLines = 1) } }
@Composable private fun FlowRowCompat(content: @Composable RowScope.() -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content) }
@Composable private fun Orb(active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(.8f, 1.08f, infiniteRepeatable(tween(if (active) 450 else 1400), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) { val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2); val radius = size.minDimension * .35f * pulse; drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF43DDF8), Color(0x443399CC), Color.Transparent), center, radius * 2.5f), radius * 2.5f, center); drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF54DEFF), Color(0xFF09639A)), center, radius), radius, center) }
}
private fun openApp(ctx: Context, app: String) {
    val (pkg, url) = when (app) { "YouTube" -> "com.google.android.youtube" to "https://youtube.com"; "WhatsApp" -> "com.whatsapp" to "https://whatsapp.com"; "Instagram" -> "com.instagram.android" to "https://instagram.com"; "Chrome" -> "com.android.chrome" to "https://google.com"; else -> "com.google.android.googlequicksearchbox" to "https://google.com" }
    runCatching { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { }
}
