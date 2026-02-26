package com.glazev.magicball

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognitionService
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
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.glazev.magicball.ui.theme.MagicBallTheme
import kotlinx.coroutines.CoroutineScope
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

enum class AppMode { PREDICTION, QUESTION, JOKE, DAILY, NONE }
enum class AppState { IDLE, RECORDING, WAITING_FOR_SHAKE, ANIMATING, SHOWING_RESULT }

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
                val rawTiltX = tiltX.floatValue * 0.88f + (-event.values[0] * 7.5f) * 0.12f
                val rawTiltY = tiltY.floatValue * 0.88f + (event.values[1] * 7.5f) * 0.12f
                tiltX.floatValue = rawTiltX.coerceIn(-15f, 15f)
                tiltY.floatValue = rawTiltY.coerceIn(-15f, 15f)

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) initSpeechRecognizer() }.launch(Manifest.permission.RECORD_AUDIO)
        } else { initSpeechRecognizer() }
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.registerListener(sensorListener, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        setContent {
            MagicBallTheme {
                MagicBallApp(
                    tiltX = tiltX.floatValue, tiltY = tiltY.floatValue,
                    onStartListening = { startListening() }, onStopListening = { stopListening() },
                    onRegisterShake = { onShake -> onShakeCallback = onShake }, onUnregisterShake = { onShakeCallback = null },
                    getLastVoiceText = { lastRecognizedText }, onVibrate = { vibrate(it) }
                )
            }
        }
    }

    private fun vibrate(duration: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else { vibrator.vibrate(duration) }
    }

    private fun initSpeechRecognizer() {
        runOnUiThread {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
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
            } catch (e: Exception) {}
        }
    }

    private fun startListening() {
        lastRecognizedText = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        runOnUiThread { try { speechRecognizer?.startListening(intent) } catch (e: Exception) { initSpeechRecognizer() } }
    }

    private fun stopListening() {
        runOnUiThread { try { speechRecognizer?.stopListening() } catch (e: Exception) {} }
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
            prefs.edit().putString("last_date", today).putInt("daily_count_v4", 0).apply()
        }
    }

    BackHandler(enabled = showInfo || showHistory || showChat || showDaily) {
        showInfo = false
        showHistory = false
        showChat = false
        showDaily = false
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
                Box(modifier = Modifier.fillMaxWidth().padding(top = 0.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.info), 
                        contentDescription = null, 
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp).size(28.dp).align(Alignment.TopStart).clickable { showInfo = true }
                    )
                    
                    Image(
                        painter = painterResource(id = R.drawable.button_chat), 
                        contentDescription = null, 
                        modifier = Modifier.padding(end = 16.dp, top = 16.dp).size(56.dp).align(Alignment.TopEnd).clickable { showChat = true }
                    )
                }

                BallArea(
                    tiltX = tiltX, tiltY = tiltY, currentState = currentState, currentMode = currentMode, resultText = resultText,
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

            AnimatedVisibility(visible = showDaily, enter = fadeIn(), exit = fadeOut()) {
                DailyPredictionOverlay(
                    prefs = prefs,
                    onClose = { showDaily = false },
                    todayDate = today
                )
            }

            AnimatedVisibility(visible = showChat, enter = fadeIn(), exit = fadeOut()) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f))) {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 24.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("AI АГЕНТ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = ChevinFontFamily)
                            Text("ЗАКРЫТЬ", color = Color(0xFF00B4D8), modifier = Modifier.clickable { showChat = false }, fontFamily = ChevinFontFamily)
                        }
                        val listState = rememberLazyListState()
                        LazyColumn(modifier = Modifier.weight(1f).padding(vertical = 16.dp), state = listState) {
                            items(chatMessages) { msg ->
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (msg.isFromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                                    Text(msg.text, color = Color.White, modifier = Modifier.background(if (msg.isFromUser) Color(0xFF00B4D8).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(12.dp), fontFamily = ChevinFontFamily)
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp)) {
                            var isRec by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.size(70.dp).clip(CircleShape).background(if (isRec) Color.Red else Color(0xFF00B4D8)).pointerInput(Unit) {
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

            if (showInfo) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable { showInfo = false }, contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.padding(32.dp).background(Color(0xFF001D3D), RoundedCornerShape(16.dp)).padding(24.dp)) {
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
                        Text("ЛАВКА ПРИЛОЖЕНИЙ\nАВТОР: ПУТИЛОВ ДЕНИС, ГЛАЗЬЕВ АРТЕМ\nВЕРСИЯ 1.7.0", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, textAlign = TextAlign.Center, fontFamily = ChevinFontFamily)
                    }
                }
            }
            if (showHistory) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable { showHistory = false }, contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight(0.7f).fillMaxWidth(0.85f).background(Color(0xFF001D3D), RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Column {
                        Text("ИСТОРИЯ", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = ChevinFontFamily, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn { items(history) { Text(it.text, color = Color.White, modifier = Modifier.padding(8.dp), fontFamily = ChevinFontFamily) } }
                    }
                }
            }
        }
    }
}

@Composable
fun DailyPredictionOverlay(prefs: android.content.SharedPreferences, onClose: () -> Unit, todayDate: String) {
    var birthDate by remember { mutableStateOf(prefs.getString("birth_date", "") ?: "") }
    var birthTime by remember { mutableStateOf(prefs.getString("birth_time", "") ?: "") }
    var birthCity by remember { mutableStateOf(prefs.getString("birth_city", "") ?: "") }
    var prediction by remember { mutableStateOf(prefs.getString("daily_pred_$todayDate", "") ?: "") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hasData = birthDate.isNotBlank() && birthTime.isNotBlank() && birthCity.isNotBlank()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f)) {
            val backgroundRes = if (prediction.isNotBlank()) R.drawable.bg_daily_prediction else R.drawable.bg_daily_card
            
            Image(
                painter = painterResource(id = backgroundRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("ЗАКРЫТЬ", color = Color(0xFF0F6B9D), fontSize = 14.sp, modifier = Modifier.clickable { onClose() }, fontFamily = ChevinFontFamily)
                }

                if (prediction.isNotBlank()) {
                    Spacer(modifier = Modifier.height(100.dp))
                    Text(prediction, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center, fontFamily = ChevinFontFamily, lineHeight = 26.sp)
                } else {
                    Spacer(modifier = Modifier.height(100.dp)) 
                    InputFieldMasked(birthDate, "00.00.0000", KeyboardType.Number, 40.sp) { input ->
                        val digits = input.filter { it.isDigit() }.take(8)
                        val filtered = StringBuilder()
                        for (i in digits.indices) {
                            val digit = digits[i]
                            when (i) {
                                0 -> if (digit <= '3') filtered.append(digit)
                                1 -> {
                                    if (filtered.isNotEmpty()) {
                                        val d1 = filtered[0]
                                        if (d1 < '3' || (d1 == '3' && digit <= '1')) {
                                            if (!(d1 == '0' && digit == '0')) filtered.append(digit)
                                        }
                                    } else {
                                        filtered.append(digit)
                                    }
                                }
                                2 -> if (digit <= '1') filtered.append(digit)
                                3 -> {
                                    if (filtered.length >= 3) {
                                        val m1 = filtered[2]
                                        if (m1 == '0' && digit > '0' || m1 == '1' && digit <= '2') filtered.append(digit)
                                    } else {
                                        filtered.append(digit)
                                    }
                                }
                                else -> filtered.append(digit)
                            }
                        }
                        val resultDigits = filtered.toString()
                        birthDate = buildString {
                            for (i in resultDigits.indices) {
                                append(resultDigits[i])
                                if ((i == 1 || i == 3) && i != resultDigits.lastIndex) append(".")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(75.dp))
                    InputFieldMasked(birthTime, "00:00", KeyboardType.Number, 40.sp) { input ->
                        val digits = input.filter { it.isDigit() }.take(4)
                        val filtered = StringBuilder()
                        for (i in digits.indices) {
                            val digit = digits[i]
                            when (i) {
                                0 -> if (digit <= '2') filtered.append(digit)
                                1 -> {
                                    if (filtered.isNotEmpty()) {
                                        val h1 = filtered[0]
                                        if (h1 < '2' || (h1 == '2' && digit <= '3')) filtered.append(digit)
                                    } else {
                                        filtered.append(digit)
                                    }
                                }
                                2 -> if (digit <= '5') filtered.append(digit)
                                3 -> filtered.append(digit)
                            }
                        }
                        val resultDigits = filtered.toString()
                        birthTime = buildString {
                            for (i in resultDigits.indices) {
                                append(resultDigits[i])
                                if (i == 1 && i != resultDigits.lastIndex) append(":")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(75.dp))
                    InputFieldMasked(birthCity, "Город", KeyboardType.Text, 28.sp) { birthCity = it }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    if (isLoading) {
                        Text("ЗВЁЗДЫ СОВЕЩАЮТСЯ...", color = Color(0xFF00B4D8), fontFamily = ChevinFontFamily, modifier = Modifier.padding(bottom = 60.dp))
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.button_stars_speak),
                            contentDescription = null,
                            modifier = Modifier
                                .width(220.dp)
                                .padding(bottom = 120.dp)
                                .clickable {
                                    if (hasData) {
                                        isLoading = true
                                        prefs.edit().putString("birth_date", birthDate).putString("birth_time", birthTime).putString("birth_city", birthCity).apply()
                                        scope.launch {
                                            val res = getDailyNumerologyResponse(birthDate, birthTime, birthCity)
                                            prediction = res
                                            prefs.edit().putString("daily_pred_$todayDate", res).apply()
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

@Composable
fun InputFieldMasked(value: String, placeholder: String, keyboardType: KeyboardType, fontSize: TextUnit, onValueChange: (String) -> Unit) {
    var textFieldValueState by remember(value) {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }

    Box(modifier = Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
        if (value.isEmpty()) {
            Text(placeholder, color = Color.White.copy(alpha = 0.1f), fontSize = 20.sp, fontFamily = ChevinFontFamily)
        }
        BasicTextField(
            value = textFieldValueState,
            onValueChange = {
                textFieldValueState = it
                onValueChange(it.text)
            },
            textStyle = TextStyle(color = Color.White, fontSize = fontSize, textAlign = TextAlign.Center, fontFamily = ChevinFontFamily),
            cursorBrush = SolidColor(Color(0xFF00B4D8)),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
        )
    }
}

suspend fun getDailyNumerologyResponse(date: String, time: String, city: String): String = withContext(Dispatchers.IO) {
    val today = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).format(Date())
    val prompt = "Ты профессиональный нумеролог и астролог. Данные пользователя: родился $date в $time, город $city. Сегодня: $today. Дай краткое предсказание-гороскоп на сегодня (250-300 символов). Пиши лаконично, без воды, только суть. На русском языке."
    
    val models = listOf("google/gemini-2.0-flash-001", "stepfun/step-1-flash", "liquid/lfm-2.5-1.2b-instruct")
    for (modelId in models) {
        try {
            val json = JSONObject().apply { 
                put("model", modelId)
                put("messages", JSONArray().put(JSONObject().apply { put("role", "user"); put("content", prompt) }))
                put("max_tokens", 400) 
            }
            val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "YOUR_OPENROUTER_API_KEY").header("HTTP-Referer", "https://github.com/glazev/magicball").header("X-Title", "Magic Ball AI").post(json.toString().toRequestBody("application/json".toMediaType())).build()
            aiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext JSONObject(response.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                }
            }
        } catch (e: Exception) {}
    }
    return@withContext "Звёзды сегодня туманны... Попробуй позже."
}

@Composable
fun BallArea(tiltX: Float, tiltY: Float, currentState: AppState, currentMode: AppMode, resultText: String, modifier: Modifier = Modifier, onAnimationFinished: () -> Unit) {
    val context = LocalContext.current
    val animationFrames = remember { (1..125).map { i -> context.resources.getIdentifier("ball_anim_$i", "drawable", context.packageName) }.filter { it != 0 } }
    
    Box(modifier = modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .offset(x = tiltX.dp, y = (tiltY - 40).dp), 
        contentAlignment = Alignment.Center
    ) {
        var frame by remember { mutableIntStateOf(0) }
        LaunchedEffect(currentState) { if (currentState == AppState.ANIMATING) { for (i in animationFrames.indices) { frame = i; delay(17) }; onAnimationFinished() } }
        
        Image(
            painter = if (currentState == AppState.ANIMATING && animationFrames.isNotEmpty()) painterResource(id = animationFrames[frame]) else painterResource(id = R.drawable.ball_base), 
            contentDescription = null, 
            modifier = Modifier.fillMaxSize().scale(1.4f) 
        )
        
        if (currentState == AppState.WAITING_FOR_SHAKE) {
            val transition = rememberInfiniteTransition()
            val offsetX by transition.animateFloat(initialValue = -10f, targetValue = 10f, animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painter = painterResource(id = R.drawable.arrow_left), contentDescription = null, modifier = Modifier.offset(x = offsetX.dp).size(38.dp))
                Image(painter = painterResource(id = R.drawable.plashka_vstriahni), contentDescription = null, modifier = Modifier.width(184.dp))
                Image(painter = painterResource(id = R.drawable.arrow_right), contentDescription = null, modifier = Modifier.offset(x = (-offsetX).dp).size(38.dp))
            }
        }

        Box(modifier = Modifier
            .size(185.dp) 
            .offset(y = (-105).dp) 
            .padding(12.dp), 
            contentAlignment = Alignment.Center
        ) {
            when (currentState) {
                AppState.RECORDING -> {
                    val transition = rememberInfiniteTransition()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(5) { i ->
                            val h by transition.animateFloat(initialValue = 10f, targetValue = 50f, animationSpec = infiniteRepeatable(tween(400 + i * 100), RepeatMode.Reverse))
                            Box(modifier = Modifier.size(6.dp, h.dp).background(Color(0xFF00B4D8), RoundedCornerShape(3.dp)))
                        }
                    }
                }
                AppState.SHOWING_RESULT -> {
                    val fontSize = when {
                        resultText.length <= 3 -> 54.sp
                        resultText.length <= 15 -> 28.sp
                        resultText.length <= 30 -> 20.sp
                        resultText.length <= 50 -> 16.sp
                        else -> 13.sp
                    }
                    Text(
                        text = resultText, 
                        color = Color.White.copy(alpha = 0.95f), 
                        fontSize = fontSize, 
                        lineHeight = (fontSize.value * 1.15).sp, 
                        textAlign = TextAlign.Center, 
                        fontWeight = FontWeight.Bold, 
                        fontFamily = ChevinFontFamily,
                        overflow = TextOverflow.Visible,
                        softWrap = true 
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
fun BottomButtons(onModeSelected: (AppMode) -> Unit, onRecordingStateChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Image(painter = painterResource(id = R.drawable.button_daily), contentDescription = null, modifier = Modifier.clickable { onModeSelected(AppMode.DAILY) }.size(70.dp))
        Image(painter = painterResource(id = R.drawable.button_vopros), contentDescription = null, modifier = Modifier.pointerInput(Unit) { detectTapGestures(onPress = { onModeSelected(AppMode.QUESTION); onRecordingStateChanged(true); tryAwaitRelease(); onRecordingStateChanged(false) }) }.size(70.dp))
        Image(painter = painterResource(id = R.drawable.button_predskaz), contentDescription = null, modifier = Modifier.clickable { onModeSelected(AppMode.PREDICTION) }.size(70.dp))
        Image(painter = painterResource(id = R.drawable.button_18), contentDescription = null, modifier = Modifier.clickable { onModeSelected(AppMode.JOKE) }.size(70.dp))
    }
}

val aiClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

suspend fun getChatAiResponse(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
    val date = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
    val models = listOf("google/gemini-2.0-flash-001", "google/gemini-2.0-flash-lite-preview-02-05", "stepfun/step-1-flash", "liquid/lfm-2.5-1.2b-instruct", "meta-llama/llama-3.1-8b-instruct:free")
    val messages = JSONArray().apply {
        put(JSONObject().apply { put("role", "system"); put("content", "Ты мудрый AI агент. Сегодня: $date. Отвечай кратко на русском.") })
        history.takeLast(6).forEach { put(JSONObject().apply { put("role", if (it.isFromUser) "user" else "assistant"); put("content", it.text) }) }
    }
    for (modelId in models) {
        try {
            val json = JSONObject().apply { put("model", modelId); put("messages", messages); put("max_tokens", 250) }
            val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "YOUR_OPENROUTER_API_KEY").header("HTTP-Referer", "https://github.com/glazev/magicball").header("X-Title", "Magic Ball AI").post(json.toString().toRequestBody("application/json".toMediaType())).build()
            aiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext JSONObject(response.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                }
            }
        } catch (e: Exception) {}
    }
    return@withContext "Связь прервана... Попробуй позже."
}

suspend fun getAiResponse(mode: AppMode, q: String): String? = withContext(Dispatchers.IO) {
    if (mode == AppMode.QUESTION && (q.isBlank() || q.length < 3)) return@withContext "Я НЕ ПОНЯЛ"
    if (mode == AppMode.DAILY || mode == AppMode.NONE) return@withContext null

    val models = listOf("google/gemini-2.0-flash-001", "stepfun/step-1-flash", "liquid/lfm-2.5-1.2b-instruct", "meta-llama/llama-3.1-8b-instruct:free")
    val prompt = when(mode) { 
        AppMode.QUESTION -> "Ты Магический Шар. Тебе задают вопрос: '$q'. Твоя задача — ответить СТРОГО одним словом: ДА или НЕТ. Даже если это утверждение, воспринимай его как вопрос. Только если ввод — полная бессмыслица, ответь Я НЕ ПОНЯЛ."
        AppMode.PREDICTION -> "Дай ОДНО универсальное предсказание (СТРОГО 4-7 слов) на русском про успех, внутренний голос, звезды, перемены, возможности и удачу. Только текст, без вариантов и пояснений."
        AppMode.JOKE -> "Постебись над пользователем САРКАСТИЧНО И ЖЕСТКО (5-7 слов). Будь циничным и острым на язык. Ответь на русском."
        else -> "" 
    }
    for (modelId in models) {
        try {
            val json = JSONObject().apply { put("model", modelId); put("messages", JSONArray().put(JSONObject().apply { put("role", "user"); put("content", prompt) })); put("max_tokens", 50) }
            val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "YOUR_OPENROUTER_API_KEY").header("HTTP-Referer", "https://github.com/glazev/magicball").header("X-Title", "Magic Ball AI").post(json.toString().toRequestBody("application/json".toMediaType())).build()
            aiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val raw = JSONObject(response.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().uppercase()
                    return@withContext if (mode == AppMode.QUESTION) {
                        if (raw.contains("ДА")) "ДА" else if (raw.contains("НЕТ")) "НЕТ" else "Я НЕ ПОНЯЛ"
                    } else raw
                }
            }
        } catch (e: Exception) {}
    }
    return@withContext null
}

fun getResultForMode(mode: AppMode): String = when(mode) { 
    AppMode.QUESTION -> if (Math.random() > 0.5) "ДА" else "НЕТ"
    AppMode.PREDICTION -> listOf(
        "УДАЧА ЖДЕТ ТЕБЯ", "СКОРО ВСЕ ИЗМЕНИТСЯ", "ВЕРЬ В СВОИ СИЛЫ", "ВРЕМЯ ДЛЯ РЕШЕНИЙ", 
        "ЖДИ ПРИЯТНЫХ ВЕСТЕЙ", "ПУТЬ ОТКРЫТ ПЕРЕД ТОБОЙ", "СЕРДЦЕ ПОДСКАЖЕТ ВЕРНО", 
        "НЕ БОЙСЯ ПЕРЕМЕН", "ЗВЕЗДЫ БЛАГОСКЛОННЫ", "ДЕНЬ БУДЕТ ЯРКИМ", "ДЕЙСТВУЙ СМЕЛО",
        "ТВОЙ ЧАС НАСТАЛ", "ВСЕ СЛОЖИТСЯ НАИЛУЧШИМ ОБРАЗОМ", "ДОВЕРЬСЯ ИНТУИЦИИ",
        "БУДЬ ГОТОВ К НОВОМУ", "ТЫ СМОЖЕШЬ ВСЕ", "СЛУШАЙ СВОЕ СЕРДЦЕ", "ВРЕМЯ ДЛЯ ЧУДА",
        "УСПЕХ УЖЕ БЛИЗКО", "ТВОЙ ПУТЬ ВЕРЕН"
    ).random()
    AppMode.JOKE -> listOf("ЗРЯ ТЫ ВООБЩЕ РОДИЛСЯ", "ТВОЙ ИНТЕЛЛЕКТ — ОШИБКА ПРИРОДЫ", "ДАЖЕ НЕ НАДЕЙСЯ, НЕУДАЧНИК", "ТЫ — ГЛАВНЫЙ КЛОУН ЭТОГО ДНЯ", "МОЖЕТ, ПРОСТО ПОМОЛЧИШЬ?", "ТВОЙ МАКСИМУМ — ЭТО НИЧЕГО").random()
    else -> "ДА"
}
