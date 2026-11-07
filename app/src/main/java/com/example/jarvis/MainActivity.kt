package com.example.jarvis

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
private val jsonType = "application/json; charset=utf-8".toMediaType()
private val sections = listOf("AI Chat", "Video Generator", "App Builder", "Website Builder", "Code Assistant", "Image Generator", "My Projects", "Settings")
class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { JarvisHome() } } }

@Composable fun JarvisHome() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("assistant_name", "JARVIS") ?: "JARVIS") }
    var selected by remember { mutableStateOf("AI Chat") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Namaste! Main JARVIS hoon. Hindi, English ya Hinglish mein poochhein.") }
    var loading by remember { mutableStateOf(false) }
    var city by remember { mutableStateOf("Delhi") }
    var seconds by remember { mutableStateOf("5") }
    var ratio by remember { mutableStateOf("9:16") }
    var quality by remember { mutableStateOf("standard") }
    var imageUrl by remember { mutableStateOf("") }
    var projectFiles by remember { mutableStateOf("") }
    var assistantEdit by remember { mutableStateOf(name) }
    var speech by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        speech = if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null
        tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale("en", "IN") }
        onDispose { speech?.destroy(); tts?.shutdown() }
    }
    fun request(path: String, data: JSONObject, callback: (JSONObject) -> Unit) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + path).post(data.toString().toRequestBody(jsonType)).build()
                    client.newCall(req).execute().use { r ->
                        val raw = r.body?.string().orEmpty()
                        val obj = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", raw.ifBlank { "Invalid server response" }) }
                        if (!r.isSuccessful) throw IllegalStateException(obj.optString("error", "HTTP ${r.code}"))
                        obj
                    }
                }
                callback(result)
            } catch (e: Exception) { output = "Error: ${e.message ?: "Could not reach backend"}. Check backend URL, network and provider setup." }
            finally { loading = false }
        }
    }
    fun sendChat(prompt: String) = request("/api/chat", JSONObject().put("message", prompt).put("history", org.json.JSONArray())) {
        output = it.optString("reply", "No reply received.")
        val locale = if (output.any { ch -> ch in 'ऀ'..'ॿ' }) Locale("hi", "IN") else Locale("en", "IN")
        tts?.language = locale; tts?.speak(output, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed || speech == null) return@rememberLauncherForActivityResult
        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(e: Int) { listening = false }
            override fun onResults(b: Bundle?) { listening = false; input = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty() }
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        speech?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF53DDF7), background = Color(0xFF06111D), surface = Color(0xFF102333))) {
        Column(Modifier.fillMaxSize().background(Color(0xFF06111D)).statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(name, color = Color(0xFF9AF0FF), fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("PERSONAL AI SYSTEM • ${if (loading) "WORKING" else "READY"}", color = Color(0xFF7296A9), fontSize = 10.sp, letterSpacing = 1.5.sp) }
                Orb(loading || listening, Modifier.size(52.dp))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(sections) { section -> FilterChip(selected == section, { selected = section }, label = { Text(section, maxLines = 1) }) }
            }
            when (selected) {
                "Settings" -> {
                    Field("Assistant display name", assistantEdit) { assistantEdit = it }
                    Button(onClick = { name = assistantEdit.trim().ifBlank { "JARVIS" }; prefs.edit().putString("assistant_name", name).apply(); output = "Assistant name saved: $name" }) { Text("Save settings") }
                    Text("Backend URL: ${BuildConfig.API_BASE_URL}\nAI provider keys are stored only on the backend. Video/image services may require paid API access.", color = Color.LightGray, modifier = Modifier.padding(top = 12.dp))
                    Action("Check backend") { request("/health", JSONObject()) { output = "Backend is online" } }
                }
                "My Projects" -> {
                    Text("Generated project source", color = Color.Cyan, fontSize = 18.sp)
                    Text("Projects are generated in App Builder / Website Builder. Copy the generated file JSON below and use Export ZIP.", color = Color.LightGray, modifier = Modifier.padding(vertical = 10.dp))
                    Field("Project files JSON", projectFiles, 8) { projectFiles = it }
                    Action("Export project ZIP") { request("/api/projects/zip", JSONObject().put("files", runCatching { org.json.JSONArray(projectFiles) }.getOrElse { org.json.JSONArray() })) { output = "ZIP generated by backend (${it.optInt("bytes")} bytes). Configure a downloadable deployment link for production." } }
                }
                "AI Chat" -> { Text("Ask JARVIS", color = Color.Cyan, fontSize = 18.sp); FeatureInput(input) { input = it }; Row { Action("Send") { if (input.isNotBlank()) sendChat(input) }; Spacer(Modifier.width(8.dp)); Action(if (listening) "Listening…" else "Speak") { if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) mic.launch(Manifest.permission.RECORD_AUDIO) else mic.launch(Manifest.permission.RECORD_AUDIO) } }; Action("Time / battery") { val pct = (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); output = "${java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date())} • battery $pct%" } }
                "Video Generator" -> {
                    Info("Text-to-video and image-to-video use your configured external video provider. Charges and model limits depend on provider.")
                    Field("Prompt (Hindi or English)", input, 4) { input = it }; Field("Optional public HTTPS image URL", imageUrl) { imageUrl = it }
                    Row { Field("Seconds", seconds) { seconds = it }; Spacer(Modifier.width(8.dp)); Field("Aspect ratio", ratio) { ratio = it } }
                    Field("Quality", quality) { quality = it }
                    Action("Submit video generation") { request("/api/video", JSONObject().put("prompt", input).put("duration", seconds.toIntOrNull() ?: 5).put("aspect_ratio", ratio).put("quality", quality).put("image_url", imageUrl)) { output = "Video job: ${it.optString("id")} • ${it.optString("status")}\nPoll job ID in backend status endpoint. Result: ${it.optString("output_url", "pending")}" } }
                }
                "App Builder", "Website Builder", "Code Assistant" -> {
                    val website = selected == "Website Builder"
                    val code = selected == "Code Assistant"
                    Info(when { website -> "Generate responsive website source files. Review generated code before deployment."; code -> "Ask for code, explanations, debugging, or safe project changes."; else -> "Generate Kotlin/Jetpack Compose project source. Review, open in Android Studio, build and test." })
                    Field(if (code) "Code question or error" else "Describe what to build", input, 5) { input = it }
                    if (code) Action("Ask coding assistant") { request("/api/code", JSONObject().put("prompt", input)) { output = it.optString("answer"); projectFiles = it.optJSONArray("files")?.toString(2) ?: projectFiles } }
                    else Action(if (website) "Generate website project" else "Generate Android app project") { request("/api/projects/generate", JSONObject().put("kind", if (website) "website" else "android").put("prompt", input)) { val files = it.optJSONArray("files"); projectFiles = files?.toString(2) ?: "[]"; output = "Generated ${files?.length() ?: 0} files. Save them in My Projects; inspect before running or deploying." } }
                }
                "Image Generator" -> { Info("Requires an image generation model and provider billing. Returns an image URL when supported."); Field("Image prompt", input, 4) { input = it }; Action("Generate image") { request("/api/image", JSONObject().put("prompt", input)) { output = it.optString("image_url", it.toString()) } } }
                else -> {
                    Info("Weather lookup uses Open-Meteo (internet required). Search and shortcuts open external apps.")
                    Field("City", city) { city = it }; Action("Get weather") { scope.launch { try { val result = withContext(Dispatchers.IO) { val url = BuildConfig.API_BASE_URL.trimEnd('/') + "/api/weather?city=" + Uri.encode(city); client.newCall(Request.Builder().url(url).get().build()).execute().use { it.body?.string().orEmpty() } }; output = JSONObject(result).optString("summary", result) } catch (e: Exception) { output = "Weather error: ${e.message}" } } }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("YouTube", "WhatsApp", "Instagram", "Chrome", "Google").forEach { app -> Action(app) { openApp(ctx, app) } } }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (loading) "PROCESSING…" else "JARVIS RESPONSE", color = Color(0xFF57D9F3), fontSize = 11.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF102333)).verticalScroll(rememberScrollState()).padding(14.dp)) { SelectionContainer { Text(output, color = Color(0xFFE2F6FF), fontSize = 14.sp) } }
        }
    }
}

@Composable private fun Info(text: String) { Text(text, color = Color(0xFF9CBAC8), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
@Composable private fun Field(label: String, value: String, lines: Int = 1, change: (String) -> Unit) { OutlinedTextField(value, change, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) }, minLines = lines, maxLines = if (lines == 1) 1 else 6) }
@Composable private fun FeatureInput(value: String, change: (String) -> Unit) = Field("Message in Hindi / English / Hinglish", value, 3, change)
@Composable private fun Action(label: String, click: () -> Unit) { Button(onClick = click, modifier = Modifier.padding(vertical = 5.dp)) { Text(label, maxLines = 1) } }
@Composable private fun Orb(active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb"); val pulse by t.animateFloat(.8f, 1.08f, infiniteRepeatable(tween(if (active) 450 else 1400), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) { val c = androidx.compose.ui.geometry.Offset(size.width/2, size.height/2); val r = size.minDimension*.35f*pulse; drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF43DDF8), Color(0x443399CC), Color.Transparent), c, r*2.5f), r*2.5f, c); drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF54DEFF), Color(0xFF09639A)), c, r), r, c) }
}
@Composable private fun SelectionContainer(content: @Composable () -> Unit) { androidx.compose.foundation.text.selection.SelectionContainer(content = content) }
private fun openApp(ctx: Context, app: String) {
    val (pkg, url) = when(app) { "YouTube" -> "com.google.android.youtube" to "https://youtube.com"; "WhatsApp" -> "com.whatsapp" to "https://whatsapp.com"; "Instagram" -> "com.instagram.android" to "https://instagram.com"; "Chrome" -> "com.android.chrome" to "https://google.com"; else -> "com.google.android.googlequicksearchbox" to "https://google.com" }
    runCatching { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
