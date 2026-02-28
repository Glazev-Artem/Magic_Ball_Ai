package com.glazev.magicball

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.glazev.magicball.ui.theme.MagicBallTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * App modes representing different user interactions.
 */
enum class AppMode { PREDICTION, QUESTION, JOKE, DAILY, NONE }

/**
 * App states for managing the main UI flow.
 */
enum class AppState { IDLE, RECORDING, WAITING_FOR_SHAKE, ANIMATING, SHOWING_RESULT }

/**
 * UI Colors and styling constants.
 */
object AppDesign {
    val PrimaryBlue = Color(0xFF00B4D8)
    val DarkNavy = Color(0xFF001D3D)
    val CardBackground = Color(0xFF051B3D)
    val InputBackground = Color(0xFF0071AE)
    const val TextAlphaSecondary = 0.95f
    const val PlaceholderAlpha = 0.2f
}

val ChevinFontFamily = FontFamily(
    Font(R.font.chevincyrillic_light, FontWeight.Light),
    Font(R.font.chevincyrillic_light_italic, FontWeight.Light, FontStyle.Italic),
    Font(R.font.chevincyrillic_bold, FontWeight.Bold),
    Font(R.font.chevincyrillic_bold_italic, FontWeight.Bold, FontStyle.Italic)
)

data class HistoryItem(val mode: AppMode, val text: String, val timestamp: Long = System.currentTimeMillis())
data class ChatMessage(val text: String, val isFromUser: Boolean)

class MainActivity : ComponentActivity() {
    private lateinit var sensorManager: SensorManager
    private val tiltX = mutableFloatStateOf(0f)
    private val tiltY = mutableFloatStateOf(0f)
    private var onShakeCallback: (() -> Unit)? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var lastRecognizedText = ""
    private var lastUpdate: Long = 0
    private var lastX: Float = 0f; private var lastY: Float = 0f; private var lastZ: Float = 0f
    private val shakeThreshold = 800

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                // Smooth tilt calculation for 3D effect
                val rawTiltX = tiltX.floatValue * 0.88f + (-event.values[0] * 7.5f) * 0.12f
                val rawTiltY = tiltY.floatValue * 0.88f + (event.values[1] * 7.5f) * 0.12f
                tiltX.floatValue = rawTiltX.coerceIn(-15f, 15f)
                tiltY.floatValue = rawTiltY.coerceIn(-15f, 15f)

                // Shake detection logic
                val curTime = System.currentTimeMillis()
                if ((curTime - lastUpdate) > 100) {
                    val diffTime = curTime - lastUpdate; lastUpdate = curTime
                    val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                    val speed = abs(x + y + z - lastX - lastY - lastZ) / diffTime * 10000
                    if (speed > shakeThreshold) onShakeCallback?.invoke()
                    lastX = x; lastY = y; lastZ = z
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAudioPermission()
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.registerListener(sensorListener, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        
        setContent {
            MagicBallTheme {
                val currentDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(currentDensity.density, fontScale = 1f)
                ) {
                    MagicBallApp(
                        tiltX = tiltX.floatValue, tiltY = tiltY.floatValue,
                        onStartListening = { startListening() }, onStopListening = { stopListening() },
                        onRegisterShake = { onShake -> onShakeCallback = onShake }, onUnregisterShake = { onShakeCallback = null },
                        getLastVoiceText = { lastRecognizedText }, onVibrate = { vibrate(it) }
                    )
                }
            }
        }
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) initSpeechRecognizer() }.launch(Manifest.permission.RECORD_AUDIO)
        } else { initSpeechRecognizer() }
    }

    private fun vibrate(duration: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun initSpeechRecognizer() {
        runOnUiThread {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) { Log.e("MagicBallAI", "Mic Error: $error") }
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) lastRecognizedText = matches[0]
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) lastRecognizedText = matches[0]
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            } catch (_: Exception) {}
        }
    }

    private fun startListening() {
        lastRecognizedText = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        runOnUiThread { try { speechRecognizer?.startListening(intent) } catch (_: Exception) { initSpeechRecognizer() } }
    }

    private fun stopListening() {
        runOnUiThread { try { speechRecognizer?.stopListening() } catch (_: Exception) {} }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(sensorListener)
        speechRecognizer?.destroy()
    }
}

@Composable
fun MagicBallApp(
    tiltX: Float, tiltY: Float,
    onStartListening: () -> Unit, onStopListening: () -> Unit,
    onRegisterShake: (() -> Unit) -> Unit, onUnregisterShake: () -> Unit,
    getLastVoiceText: () -> String, onVibrate: (Long) -> Unit
) {
    val context = LocalContext.current
    var currentMode by remember { mutableStateOf(AppMode.NONE) }
    var currentState by remember { mutableStateOf(AppState.IDLE) }
    var resultText by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showDaily by remember { mutableStateOf(false) }
    
    val history = remember { mutableStateListOf<HistoryItem>() }
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }
    val prefs = remember { context.getSharedPreferences("MagicBallPrefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    LaunchedEffect(Unit) {
        if (today != prefs.getString("last_date", "")) {
            prefs.edit {
                putString("last_date", today)
                putInt("daily_count_v4", 0)
            }
        }
    }

    BackHandler(enabled = showInfo || showHistory || showChat || showDaily) {
        showInfo = false; showHistory = false; showChat = false; showDaily = false
    }

    LaunchedEffect(currentState) {
        if (currentState == AppState.WAITING_FOR_SHAKE) {
            onRegisterShake { onVibrate(50); currentState = AppState.ANIMATING }
        } else { onUnregisterShake() }
    }

    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Color.Transparent) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Image(painter = painterResource(id = R.drawable.bg_main), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)

            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                TopBarIcons(onInfoClick = { showInfo = true }, onChatClick = { showChat = true })

                BallArea(
                    tiltX = tiltX, tiltY = tiltY, currentState = currentState, resultText = resultText,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                    onAnimationFinished = {
                        scope.launch {
                            onVibrate(150)
                            val voiceText = getLastVoiceText()
                            val aiResponse = getAiResponse(currentMode, voiceText)
                            val finalResult = aiResponse ?: getResultForMode(currentMode)
                            resultText = finalResult
                            history.add(0, HistoryItem(currentMode, finalResult))
                            currentState = AppState.SHOWING_RESULT
                        }
                    }
                )

                BottomButtons(
                    currentMode = currentMode,
                    currentState = currentState,
                    onModeSelected = { mode -> 
                        if (mode == AppMode.DAILY) {
                            showDaily = true
                        } else {
                            currentMode = mode
                            if (mode != AppMode.QUESTION) currentState = AppState.WAITING_FOR_SHAKE
                            resultText = ""
                            onVibrate(20)
                        }
                    },
                    onRecordingStateChanged = { if (currentMode == AppMode.QUESTION) { if (it) onStartListening() else onStopListening(); currentState = if (it) AppState.RECORDING else AppState.WAITING_FOR_SHAKE } },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            OverlayScreens(
                showDaily = showDaily, showChat = showChat, showInfo = showInfo, showHistory = showHistory,
                prefs = prefs, todayDate = today, history = history, chatMessages = chatMessages,
                onCloseDaily = { showDaily = false }, onCloseChat = { showChat = false }, 
                onCloseInfo = { showInfo = false }, onCloseHistory = { showHistory = false },
                onStartListening = onStartListening, onStopListening = onStopListening, 
                getLastVoiceText = getLastVoiceText, onVibrate = onVibrate
            )
        }
    }
}

@Composable
fun TopBarIcons(onInfoClick: () -> Unit, onChatClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 0.dp)) {
        Image(
            painter = painterResource(id = R.drawable.info), 
            contentDescription = "Instruction", 
            modifier = Modifier.padding(start = 16.dp, top = 16.dp).size(28.dp).align(Alignment.TopStart).clickable { onInfoClick() }
        )
        Image(
            painter = painterResource(id = R.drawable.button_chat), 
            contentDescription = "Chat AI", 
            modifier = Modifier.padding(end = 16.dp, top = 16.dp).size(42.dp).align(Alignment.TopEnd).clickable { onChatClick() }
        )
    }
}

@Composable
fun OverlayScreens(
    showDaily: Boolean, showChat: Boolean, showInfo: Boolean, showHistory: Boolean,
    prefs: SharedPreferences, todayDate: String, history: List<HistoryItem>, chatMessages: MutableList<ChatMessage>,
    onCloseDaily: () -> Unit, onCloseChat: () -> Unit, onCloseInfo: () -> Unit, onCloseHistory: () -> Unit,
    onStartListening: () -> Unit, onStopListening: () -> Unit, getLastVoiceText: () -> String, onVibrate: (Long) -> Unit
) {
    AnimatedVisibility(visible = showDaily, enter = fadeIn(), exit = fadeOut()) {
        DailyPredictionOverlay(prefs = prefs, onClose = onCloseDaily, todayDate = todayDate)
    }

    AnimatedVisibility(visible = showChat, enter = fadeIn(), exit = fadeOut()) {
        ChatOverlay(chatMessages, onCloseChat, onStartListening, onStopListening, getLastVoiceText, onVibrate)
    }

    if (showInfo) InstructionOverlay(onCloseInfo)
    
    if (showHistory) HistoryOverlay(history, onCloseHistory)
}

@Composable
fun ChatOverlay(
    chatMessages: MutableList<ChatMessage>, onClose: () -> Unit, 
    onStartListening: () -> Unit, onStopListening: () -> Unit,
    getLastVoiceText: () -> String, onVibrate: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f))) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("AI АГЕНТ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = ChevinFontFamily)
                Text("ЗАКРЫТЬ", color = AppDesign.PrimaryBlue, modifier = Modifier.clickable { onClose() }, fontFamily = ChevinFontFamily)
            }
            val listState = rememberLazyListState()
            LazyColumn(modifier = Modifier.weight(1f).padding(vertical = 16.dp), state = listState) {
                items(chatMessages) { msg ->
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (msg.isFromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(msg.text, color = Color.White, modifier = Modifier.background(if (msg.isFromUser) AppDesign.PrimaryBlue.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(12.dp), fontFamily = ChevinFontFamily)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp)) {
                var isRec by remember { mutableStateOf(false) }
                Box(modifier = Modifier.size(70.dp).clip(CircleShape).background(if (isRec) Color.Red else AppDesign.PrimaryBlue).pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        isRec = true; onVibrate(30); onStartListening(); tryAwaitRelease()
                        isRec = false; onStopListening()
                        scope.launch {
                            delay(600); val q = getLastVoiceText()
                            if (q.isNotBlank()) {
                                chatMessages.add(ChatMessage(q, true))
                                val ans = getChatAiResponse(chatMessages)
                                chatMessages.add(ChatMessage(ans, false))
                                listState.animateScrollToItem(chatMessages.size - 1)
                            }
                        }
                    })
                }, contentAlignment = Alignment.Center) { Text(if (isRec) "..." else "🎤", fontSize = 30.sp) }
                Text("УДЕРЖИВАЙТЕ, ЧТОБЫ СПРОСИТЬ", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp), fontFamily = ChevinFontFamily)
            }
        }
    }
}

@Composable
fun InstructionOverlay(onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.padding(32.dp).background(AppDesign.DarkNavy, RoundedCornerShape(16.dp)).padding(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ИНСТРУКЦИЯ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = ChevinFontFamily)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "1. ВЫБЕРИТЕ РЕЖИМ ВНИЗУ И ВСТРЯХНИТЕ ШАР ДЛЯ ОТВЕТА.\n\n" +
                    "2. 'ЗАДАТЬ ВОПРОС': УДЕРЖИВАЙТЕ КНОПКУ, ГОВОРИТЕ И ТРЯСИТЕ ТЕЛЕФОН ДЛЯ ОТВЕТА ДА/НЕТ.\n\n" +
                    "3. 'ПРЕДСКАЗАНИЕ': ПОЛУЧИТЕ МИСТИЧЕСКИЙ СОВЕТ ОТ ЗВЕЗД.\n\n" +
                    "4. 'СТЕБ': ПРИГОТОВЬТЕСЬ К ЖЕСТКОМУ САРКАЗМУ.\n\n" +
                    "5. 'ДАТА РОЖДЕНИЯ': ВВЕДИТЕ ДАННЫЕ ДЛЯ ПЕРСОНАЛЬНОГО ГОРОСКОПА НА ДЕНЬ.\n\n" +
                    "6. 'ЧАТ С ИИ': СВОБОДНОЕ ОБЩЕНИЕ С УМНЫМ АГЕНТОМ (ИКОНКА ВВЕРХУ).",
                    color = Color.White, textAlign = TextAlign.Center, fontFamily = ChevinFontFamily, fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text("ЛАВКА ПРИЛОЖЕНИЙ\nАВТОР: ПУТИЛОВ ДЕНИС, ГЛАЗЬЕВ АРТЕМ\nВЕРСИЯ 1.8.0", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, textAlign = TextAlign.Center, fontFamily = ChevinFontFamily)
            }
        }
    }
}

@Composable
fun HistoryOverlay(history: List<HistoryItem>, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxHeight(0.7f).fillMaxWidth(0.85f).background(AppDesign.DarkNavy, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Column {
                Text("ИСТОРИЯ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = ChevinFontFamily, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn { items(history) { Text(it.text, color = Color.White, modifier = Modifier.padding(8.dp), fontFamily = ChevinFontFamily) } }
            }
        }
    }
}

@Composable
fun DailyPredictionOverlay(prefs: SharedPreferences, onClose: () -> Unit, todayDate: String) {
    var birthDate by remember { mutableStateOf(TextFieldValue(prefs.getString("birth_date", "") ?: "")) }
    var birthTime by remember { mutableStateOf(TextFieldValue(prefs.getString("birth_time", "") ?: "")) }
    var birthCity by remember { mutableStateOf(TextFieldValue(prefs.getString("birth_city", "") ?: "")) }
    var prediction by remember { mutableStateOf(prefs.getString("daily_pred_$todayDate", "") ?: "") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hasData = birthDate.text.isNotBlank() && birthTime.text.isNotBlank() && birthCity.text.isNotBlank()
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f).clip(RoundedCornerShape(24.dp)).background(AppDesign.CardBackground)) {
            if (prediction.isNotBlank()) {
                Image(painter = painterResource(id = R.drawable.bg_daily_prediction), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("ЗАКРЫТЬ", color = AppDesign.PrimaryBlue, fontSize = 16.sp, modifier = Modifier.clickable { onClose() }, fontFamily = ChevinFontFamily, fontWeight = FontWeight.Bold)
                }

                if (prediction.isNotBlank()) {
                    Spacer(modifier = Modifier.height(80.dp))
                    Text(prediction, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center, fontFamily = ChevinFontFamily, lineHeight = 26.sp, modifier = Modifier.padding(horizontal = 16.dp))
                } else {
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Text("ДАТА РОЖДЕНИЯ", color = Color.White, fontSize = 14.sp, fontFamily = ChevinFontFamily, modifier = Modifier.padding(bottom = 6.dp))
                    InputFieldMasked(birthDate, "00.00.0000", KeyboardType.Number) { input ->
                        val newRawText = input.filter { it.isDigit() }.take(8)
                        if (validateDateInput(newRawText, currentYear)) {
                            birthDate = TextFieldValue(text = formatAsDate(newRawText), selection = TextRange(newRawText.length + (if (newRawText.length > 2) 1 else 0) + (if (newRawText.length > 4) 1 else 0)))
                        } else {
                            birthDate = TextFieldValue("")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("ВРЕМЯ РОЖДЕНИЯ", color = Color.White, fontSize = 14.sp, fontFamily = ChevinFontFamily, modifier = Modifier.padding(bottom = 6.dp))
                    InputFieldMasked(birthTime, "00:00", KeyboardType.Number) { input ->
                        val digits = input.filter { it.isDigit() }.take(4)
                        if (validateTimeInput(digits)) {
                            birthTime = TextFieldValue(text = formatAsTime(digits), selection = TextRange(digits.length + (if (digits.length > 2) 1 else 0)))
                        } else {
                            birthTime = TextFieldValue("")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("МЕСТО РОЖДЕНИЯ (ГОРОД)", color = Color.White, fontSize = 14.sp, fontFamily = ChevinFontFamily, modifier = Modifier.padding(bottom = 6.dp))
                    InputFieldMasked(birthCity, "Город", KeyboardType.Text, KeyboardCapitalization.Words) { input ->
                        val formatted = input.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        birthCity = TextFieldValue(text = formatted, selection = TextRange(formatted.length))
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    if (isLoading) {
                        Text("ЗВЁЗДЫ СОВЕЩАЮТСЯ...", color = AppDesign.PrimaryBlue, fontFamily = ChevinFontFamily, modifier = Modifier.padding(bottom = 60.dp))
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.button_stars_speak),
                            contentDescription = "Submit",
                            modifier = Modifier.width(200.dp).padding(bottom = 60.dp).clickable {
                                if (hasData) {
                                    isLoading = true
                                    prefs.edit { putString("birth_date", birthDate.text); putString("birth_time", birthTime.text); putString("birth_city", birthCity.text) }
                                    scope.launch {
                                        val res = getDailyNumerologyResponse(birthDate.text, birthTime.text, birthCity.text)
                                        prediction = res
                                        prefs.edit { putString("daily_pred_$todayDate", res) }
                                        isLoading = false
                                    }
                                }
                            },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}

private fun validateDateInput(rawText: String, currentYear: Int): Boolean {
    if (rawText.length >= 2) {
        val day = rawText.substring(0, 2).toIntOrNull() ?: 0
        if (day !in 1..31) return false
    }
    if (rawText.length >= 4) {
        val month = rawText.substring(2, 4).toIntOrNull() ?: 0
        if (month !in 1..12) return false
        val day = rawText.substring(0, 2).toInt()
        val maxDays = when (month) {
            2 -> 29; 4, 6, 9, 11 -> 30; else -> 31
        }
        if (day > maxDays) return false
    }
    if (rawText.length == 8) {
        val year = rawText.substring(4, 8).toInt()
        if (year !in 1900..currentYear) return false
        val month = rawText.substring(2, 4).toInt()
        val day = rawText.substring(0, 2).toInt()
        if (month == 2 && day == 29) {
            val isLeap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
            if (!isLeap) return false
        }
    }
    return true
}

private fun formatAsDate(rawText: String) = buildString {
    rawText.forEachIndexed { index, c ->
        append(c)
        if ((index == 1 || index == 3) && index < rawText.lastIndex) append('.')
    }
}

private fun validateTimeInput(digits: String): Boolean {
    if (digits.length >= 2) {
        val hour = digits.substring(0, 2).toIntOrNull() ?: 0
        if (hour !in 0..23) return false
    }
    if (digits.length == 4) {
        val minute = digits.substring(2, 4).toIntOrNull() ?: 0
        if (minute !in 0..59) return false
    }
    return true
}

private fun formatAsTime(digits: String) = buildString {
    digits.forEachIndexed { index, c ->
        append(c)
        if (index == 1 && index < digits.lastIndex) append(':')
    }
}

@Composable
fun InputFieldMasked(value: TextFieldValue, placeholder: String, keyboardType: KeyboardType, capitalization: KeyboardCapitalization = KeyboardCapitalization.None, onValueChange: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).background(AppDesign.InputBackground), contentAlignment = Alignment.Center) {
        if (value.text.isEmpty()) {
            Text(placeholder, color = Color.White.copy(alpha = AppDesign.PlaceholderAlpha), fontSize = 24.sp, fontFamily = ChevinFontFamily)
        }
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.text) },
            textStyle = TextStyle(color = Color.White, fontSize = if (keyboardType == KeyboardType.Number) 36.sp else 24.sp, textAlign = TextAlign.Center, fontFamily = ChevinFontFamily),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization)
        )
    }
}

suspend fun getDailyNumerologyResponse(date: String, time: String, city: String): String = withContext(Dispatchers.IO) {
    val today = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).format(Date())
    val prompt = "Ты профессиональный нумеролог и астролог. Данные пользователя: родился $date в $time, город $city. Сегодня: $today. Дай краткое предсказание основываясь на данных о пользователе-гороскоп на сегодня (250-300 символов). Пиши лаконично, без воды, только суть. На русском языке."
    val models = listOf("google/gemini-2.0-flash-001", "stepfun/step-1-flash", "liquid/lfm-2.5-1.2b-instruct")
    for (modelId in models) {
        try {
            val json = JSONObject().apply { 
                put("model", modelId)
                put("messages", JSONArray().put(JSONObject().apply { put("role", "user"); put("content", prompt) }))
                put("max_tokens", 400) 
            }
            val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "Api_key").header("HTTP-Referer", "https://github.com/glazev/magicball").header("X-Title", "Magic Ball AI").post(json.toString().toRequestBody("application/json".toMediaType())).build()
            aiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext JSONObject(response.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                }
            }
        } catch (_: Exception) {}
    }
    return@withContext "Звёзды сегодня туманны... Попробуй позже."
}

@Composable
fun BallArea(tiltX: Float, tiltY: Float, currentState: AppState, resultText: String, modifier: Modifier = Modifier, onAnimationFinished: () -> Unit) {
    val context = LocalContext.current
    val animationFrames = remember { (1..125).map { i -> context.resources.getIdentifier("ball_anim_$i", "drawable", context.packageName) }.filter { it != 0 } }
    
    BoxWithConstraints(modifier = modifier.fillMaxWidth().aspectRatio(1f).offset(x = tiltX.dp, y = (tiltY - 40).dp), contentAlignment = Alignment.Center) {
        val ballSize = maxWidth
        var frame by remember { mutableIntStateOf(0) }
        LaunchedEffect(currentState) { if (currentState == AppState.ANIMATING) { for (i in animationFrames.indices) { frame = i; delay(17) }; onAnimationFinished() } }
        
        Image(
            painter = if (currentState == AppState.ANIMATING && animationFrames.isNotEmpty()) painterResource(id = animationFrames[frame]) else painterResource(id = R.drawable.ball_base), 
            contentDescription = null, 
            modifier = Modifier.fillMaxSize().scale(1.4f) 
        )
        
        if (currentState == AppState.WAITING_FOR_SHAKE) ShakeHintAnimation()

        // Adaptive result and recording area
        Box(modifier = Modifier.width(ballSize * 0.48f).height(ballSize * 0.35f).offset(y = -(ballSize * 0.238f)).padding(ballSize * 0.07f), contentAlignment = Alignment.Center) {
            when (currentState) {
                AppState.RECORDING -> RecordingIndicator()
                AppState.SHOWING_RESULT -> ResultTextAnimation(resultText)
                else -> {}
            }
        }
    }
}

@Composable
fun ShakeHintAnimation() {
    val transition = rememberInfiniteTransition(label = "shake")
    val offsetX by transition.animateFloat(initialValue = -10f, targetValue = 10f, animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "shakeOffset")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource(id = R.drawable.arrow_left), contentDescription = null, modifier = Modifier.offset(x = offsetX.dp).size(38.dp))
        Image(painter = painterResource(id = R.drawable.plashka_vstriahni), contentDescription = null, modifier = Modifier.width(184.dp))
        Image(painter = painterResource(id = R.drawable.arrow_right), contentDescription = null, modifier = Modifier.offset(x = (-offsetX).dp).size(38.dp))
    }
}

@Composable
fun RecordingIndicator() {
    val transition = rememberInfiniteTransition(label = "recording")
    Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        repeat(5) { i ->
            val h by transition.animateFloat(initialValue = 10f, targetValue = 50f, animationSpec = infiniteRepeatable(tween(400 + i * 100), RepeatMode.Reverse), label = "barHeight")
            Box(modifier = Modifier.size(6.dp, h.dp).background(AppDesign.PrimaryBlue, RoundedCornerShape(3.dp)))
            if (i < 4) Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
fun ResultTextAnimation(resultText: String) {
    var currentFontSize by remember(resultText) { mutableStateOf(48.sp) }
    val scale = remember { Animatable(0.1f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(resultText) {
        scale.snapTo(0.1f); alpha.snapTo(0f)
        launch { scale.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
        launch { alpha.animateTo(1f, tween(800)) }
    }

    Text(
        text = resultText, color = Color.White.copy(alpha = AppDesign.TextAlphaSecondary), fontSize = currentFontSize, lineHeight = (currentFontSize.value * 1.1).sp, textAlign = TextAlign.Center, 
        fontWeight = FontWeight.Bold, fontFamily = ChevinFontFamily, overflow = TextOverflow.Visible, softWrap = true, modifier = Modifier.scale(scale.value).alpha(alpha.value),
        onTextLayout = { if (it.hasVisualOverflow && currentFontSize.value > 12f) currentFontSize = (currentFontSize.value * 0.9f).sp }
    )
}

@Composable
fun BottomButtons(currentMode: AppMode, currentState: AppState, onModeSelected: (AppMode) -> Unit, onRecordingStateChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val isProcessing = currentState != AppState.IDLE && currentState != AppState.SHOWING_RESULT
    Row(modifier = modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ModeButton(AppMode.DAILY, R.drawable.button_daily, currentMode, isProcessing) { onModeSelected(it) }
        ModeButton(AppMode.QUESTION, R.drawable.button_vopros, currentMode, isProcessing, isHoldAction = true, onRecordingStateChanged = onRecordingStateChanged) { onModeSelected(it) }
        ModeButton(AppMode.PREDICTION, R.drawable.button_predskaz, currentMode, isProcessing) { onModeSelected(it) }
        ModeButton(AppMode.JOKE, R.drawable.button_18, currentMode, isProcessing) { onModeSelected(it) }
    }
}

@Composable
fun ModeButton(mode: AppMode, resId: Int, currentMode: AppMode, isProcessing: Boolean, isHoldAction: Boolean = false, onRecordingStateChanged: (Boolean) -> Unit = {}, onClick: (AppMode) -> Unit) {
    val active = currentMode == mode && isProcessing
    val modifier = if (isHoldAction) {
        Modifier.pointerInput(Unit) { detectTapGestures(onPress = { onClick(mode); onRecordingStateChanged(true); tryAwaitRelease(); onRecordingStateChanged(false) }) }
    } else {
        Modifier.clickable { onClick(mode) }
    }
    Image(
        painter = painterResource(id = resId), contentDescription = null,
        modifier = modifier.size(70.dp).scale(if (active) 1.15f else 1f).alpha(if (active || !isProcessing) 1f else 0.5f)
    )
}

val aiClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()

suspend fun getChatAiResponse(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
    val date = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
    val messages = JSONArray().apply {
        put(JSONObject().apply { put("role", "system"); put("content", "Ты мудрый AI агент. Сегодня: $date. Отвечай кратко на русском.") })
        history.takeLast(6).forEach { put(JSONObject().apply { put("role", if (it.isFromUser) "user" else "assistant"); put("content", it.text) }) }
    }
    try {
        val json = JSONObject().apply { put("model", "google/gemini-2.0-flash-001"); put("messages", messages); put("max_tokens", 250) }
        val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "Api_key").header("HTTP-Referer", "https://github.com/glazev/magicball").header("X-Title", "Magic Ball AI").post(json.toString().toRequestBody("application/json".toMediaType())).build()
        aiClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) return@withContext JSONObject(response.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        }
    } catch (_: Exception) {}
    return@withContext "Связь прервана... Попробуй позже."
}

suspend fun getAiResponse(mode: AppMode, q: String): String? = withContext(Dispatchers.IO) {
    if (mode == AppMode.QUESTION && (q.isBlank() || q.length < 3)) return@withContext "Я НЕ ПОНЯЛ"
    if (mode == AppMode.DAILY || mode == AppMode.NONE) return@withContext null
    val prompt = when(mode) { 
        AppMode.QUESTION -> "Ты Магический Шар. Тебе задают вопрос: '$q'. Твоя задача — ответить СТРОГО одним словом: ДА или НЕТ."
        AppMode.PREDICTION -> "Дай ОДНО универсальное предсказание (СТРОГО 4-7 слов) на русском про успех и удачу."
        AppMode.JOKE -> "Постебись над пользователем САРКАСТИЧНО И ЖЕСТКО (5-7 слов). Ответь на русском."
        else -> "" 
    }
    try {
        val json = JSONObject().apply { put("model", "google/gemini-2.0-flash-001"); put("messages", JSONArray().put(JSONObject().apply { put("role", "user"); put("content", prompt) })); put("max_tokens", 50) }
        val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "Api_key").post(json.toString().toRequestBody("application/json".toMediaType())).build()
        aiClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val raw = JSONObject(response.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().uppercase()
                return@withContext if (mode == AppMode.QUESTION) (if (raw.contains("ДА")) "ДА" else if (raw.contains("НЕТ")) "НЕТ" else "Я НЕ ПОНЯЛ") else raw
            }
        }
    } catch (_: Exception) {}
    return@withContext null
}

fun getResultForMode(mode: AppMode): String = when(mode) { 
    AppMode.QUESTION -> if (Math.random() > 0.5) "ДА" else "НЕТ"
    AppMode.PREDICTION -> listOf("УДАЧА ЖДЕТ ТЕБЯ", "СКОРО ВСЕ ИЗМЕНИТСЯ", "ВЕРЬ В СВОИ СИЛЫ", "ВРЕМЯ ДЛЯ РЕШЕНИЙ").random()
    AppMode.JOKE -> listOf("ЗРЯ ТЫ ВООБЩЕ РОДИЛСЯ", "ДАЖЕ НЕ НАДЕЙСЯ, НЕУДАЧНИК").random()
    else -> "ДА"
}
