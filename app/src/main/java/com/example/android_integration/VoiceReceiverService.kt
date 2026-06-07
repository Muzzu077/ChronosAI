package com.example.android_integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.RoomListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * VoiceReceiverService: Persistent Android Foreground Service dedicated to maintaining
 * the LiveKit WebRTC media socket connection alive, overriding Android Background Throttling rules.
 */
class VoiceReceiverService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val binder = LocalBinder()
    
    // LiveKit Room State instance
    private var liveKitRoom: Room? = null
    private var isConnected = false

    private val pendingMessageQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    // Local Speech and Listening engines
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isTtsReady = false
    private var pendingSpeak: Pair<String, Boolean>? = null

    companion object {
        private const val TAG = "VoiceReceiverService"
        private const val NOTIFICATION_CHANNEL_ID = "ChronosAI_Voice_Service_Channel"
        private const val NOTIFICATION_ID = 1024

        const val ACTION_START_CALL = "com.chronosai.ACTION_START_CALL"
        const val ACTION_STOP_CALL = "com.chronosai.ACTION_STOP_CALL"
        const val ACTION_SEND_CHAT = "com.chronosai.ACTION_SEND_CHAT"
        const val EXTRA_JWT_TOKEN = "com.chronosai.EXTRA_JWT_TOKEN"
        const val EXTRA_SERVER_URL = "com.chronosai.EXTRA_SERVER_URL"
        const val EXTRA_CHAT_MESSAGE = "com.chronosai.EXTRA_CHAT_MESSAGE"

        var activeViewModel: com.example.ChronosViewModel? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceReceiverService = this@VoiceReceiverService
    }

    /**
     * Send text message to the AI Agent via LiveKit Data Channel.
     */
    fun sendTextMessage(text: String) {
        if (!isConnected) {
            Log.d(TAG, "Queueing message: $text (waiting for connection)")
            pendingMessageQueue.add(text)
            return
        }
        serviceScope.launch {
            try {
                val participant = liveKitRoom?.localParticipant
                if (participant != null) {
                    val data = text.toByteArray(Charsets.UTF_8)
                    participant.publishData(
                        data = data,
                        reliability = io.livekit.android.room.track.DataPublishReliability.RELIABLE,
                        topic = "chat"
                    )
                    Log.d(TAG, "Sent text message to chat channel successfully: $text")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send text message", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service Bound by Client Context.")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VoiceReceiverService Initialized. Promoting to Foreground Service.")
        
        // Android 26+ requires explicit channel creation for notification promotion
        createNotificationChannel()
        
        // Promotes the service immediately using custom notification with speaker permission specifiers
        startForegroundServiceCompat()

        // Bind callbacks to active ViewModel
        activeViewModel?.let { vm ->
            vm.onSpeakRequested = { text, shouldListen ->
                speakAloud(text, shouldListen)
            }
            vm.onListenRequested = {
                startListening()
            }
        }

        // Initialize TTS
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(audioAttributes)
                    }
                    isTtsReady = true
                    pendingSpeak?.let { (text, shouldListen) ->
                        speakAloud(text, shouldListen)
                        pendingSpeak = null
                    }
                } else {
                    Log.e(TAG, "TTS Initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS in service", e)
        }

        // Initialize SpeechRecognizer
        try {
            if (SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            } else {
                Log.e(TAG, "SpeechRecognizer not available on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer in service", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand received action: $action")
        
        when (action) {
            ACTION_START_CALL -> {
                val token = intent.getStringExtra(EXTRA_JWT_TOKEN)
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
                if (token != null) {
                    initiateWebRtcRoom(token, serverUrl)
                } else {
                    Log.e(TAG, "ACTION_START_CALL failed: Transmitted target token is null.")
                }
            }
            ACTION_STOP_CALL -> {
                terminateWebRtcRoom()
            }
            ACTION_SEND_CHAT -> {
                val msg = intent.getStringExtra(EXTRA_CHAT_MESSAGE)
                if (msg != null) {
                    sendTextMessage(msg)
                }
            }
        }
        
        return START_NOT_STICKY
    }

    /**
     * Set up LiveKit Room and start connection loop.
     */
    private fun initiateWebRtcRoom(jwtToken: String, gatewayServerUrl: String?) {
        if (isConnected) {
            Log.w(TAG, "Initiation ignored: Room already connected.")
            return
        }

        serviceScope.launch {
            try {
                Log.d(TAG, "Spawning LiveKit WebRTC client room connection...")
                
                // Bind callbacks to active ViewModel
                activeViewModel?.let { vm ->
                    vm.onSpeakRequested = { text, shouldListen ->
                        speakAloud(text, shouldListen)
                    }
                    vm.onListenRequested = {
                        startListening()
                    }
                }
                
                // 1. Instantiate the LiveKit Android Room
                val context = applicationContext
                liveKitRoom = LiveKit.create(context).apply {
                    // Start listening to critical call events (Participant joined, tracks updated, etc.)
                    serviceScope.launch {
                        this@apply.events.collect { event ->
                            handleRoomEvents(event)
                        }
                    }
                }

                // 2. Route Audio output cleanly through the device's main speaker stream
                configureAudioRouting()

                // 3. Connect to the room using the fetched token
                val serverUrl = gatewayServerUrl ?: "wss://voice-call-aelv823z.livekit.cloud"
                Log.d(TAG, "Connecting to: $serverUrl")
                
                liveKitRoom?.connect(
                    url = serverUrl,
                    token = jwtToken
                )

                // Disable the local microphone track in LiveKit to prevent resource locking with local SpeechRecognizer
                liveKitRoom?.localParticipant?.setMicrophoneEnabled(false)

                isConnected = true
                Log.d(TAG, "Successfully connected to LiveKit Voice Room: room-user.")
                activeViewModel?.setVoiceSessionState(com.example.VoiceSessionState.LISTENING)
                
                // Start listening to user voice input immediately upon connection
                startListening()

                // Flush pending messages
                while (!pendingMessageQueue.isEmpty()) {
                    val msg = pendingMessageQueue.poll()
                    if (msg != null) {
                        sendTextMessage(msg)
                    }
                }

                // If this is a call triggered by a reminder, notify the agent immediately
                val vm = activeViewModel
                if (vm != null) {
                    val taskId = vm.activeCallTaskId.value
                    val taskText = vm.activeCallText.value
                    if (taskId == "TEST_CALL_ID" && taskText == "TEST_CALL") {
                        sendTextMessage("SYSTEM_CONNECT: TEST_CALL")
                    } else if (taskId.isNotEmpty() && taskText.isNotEmpty()) {
                        val reminderMsg = "SYSTEM_REMINDER: $taskId | $taskText"
                        sendTextMessage(reminderMsg)
                    } else {
                        sendTextMessage("SYSTEM_CONNECT: MANUAL")
                    }
                } else {
                    sendTextMessage("SYSTEM_CONNECT: MANUAL")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed connecting to LiveKit room: ${e.message}", e)
                stopSelf()
            }
        }
    }

    /**
     * Terminates connection and frees up hardware microphone resource.
     */
    private fun terminateWebRtcRoom() {
        Log.d(TAG, "Terminating LiveKit WebRTC connection context...")
        serviceScope.launch {
            try {
                // Send HUNGUP signal to backend
                sendTextMessage("SYSTEM_HANGUP")
                // Wait slightly for data transmission
                kotlinx.coroutines.delay(200)
                
                liveKitRoom?.disconnect()
                liveKitRoom = null
                isConnected = false
                Log.d(TAG, "Disconnected successfully on demand.")
            } catch (e: Exception) {
                Log.e(TAG, "Error executing LiveKit disconnect sequence", e)
            } finally {
                activeViewModel?.let { vm ->
                    vm.onSpeakRequested = null
                    vm.onListenRequested = null
                }
                activeViewModel?.setVoiceSessionState(com.example.VoiceSessionState.IDLE)
                stopSelf()
            }
        }
    }

    private fun handleRoomEvents(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected -> {
                Log.i(TAG, "AI Voice Agent joined the interface: ${event.participant.identity}")
            }
            is RoomEvent.ParticipantDisconnected -> {
                Log.i(TAG, "AI Agent disconnected.")
            }
            is RoomEvent.TrackSubscribed -> {
                Log.d(TAG, "Incoming high fidelity audio track subscribed! Route ready for voice stream.")
            }
            is RoomEvent.DataReceived -> {
                try {
                    val text = String(event.data, Charsets.UTF_8)
                    Log.d(TAG, "Data channel message received: $text")
                    
                    if (text.startsWith("SYSTEM_NOTIFICATION:")) {
                        val content = text.substringAfter("SYSTEM_NOTIFICATION:").trim()
                        activeViewModel?.updateTranscript("ChronosAI: $content")
                        showLocalNotification(content)
                    } else {
                        activeViewModel?.updateTranscript(text)
                        speakAloud(text, shouldListen = true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling DataReceived event", e)
                }
            }
            else -> Log.v(TAG, "Unprocessed livekit room event: ${event::class.java.simpleName}")
        }
    }

    private fun speakAloud(text: String, shouldListen: Boolean) {
        if (!isTtsReady) {
            Log.d(TAG, "TTS not ready yet, queueing: $text")
            pendingSpeak = Pair(text, shouldListen)
            return
        }
        // Run on Main Thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                cancelListening()
                
                val utteranceId = if (shouldListen) "LISTEN_AFTER_SPEAK" else "JUST_SPEAK"
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS Started speaking: $text")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS Finished speaking: $utteranceId")
                        if (utteranceId == "LISTEN_AFTER_SPEAK") {
                            startListening()
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS Error for: $utteranceId")
                    }
                })
                
                Log.d(TAG, "Speaking text: $text")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to speak aloud in service", e)
            }
        }
    }

    private fun startListening() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                if (!isConnected) return@post
                speechRecognizer?.cancel()
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SpeechRecognizer Ready")
                    }
                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer Beginning")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer End")
                    }
                    override fun onError(error: Int) {
                        Log.e(TAG, "SpeechRecognizer Error: $error")
                        if (isConnected) {
                            when (error) {
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                    Log.w(TAG, "SpeechRecognizer busy. Cancelling and restarting...")
                                    speechRecognizer?.cancel()
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        startListening()
                                    }, 300)
                                }
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                    Log.d(TAG, "SpeechRecognizer timeout/no-match. Restarting.")
                                    startListening()
                                }
                                else -> {
                                    Log.w(TAG, "SpeechRecognizer error: $error. Retrying in 1s to keep mic active...")
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        startListening()
                                    }, 1000)
                                }
                            }
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val spokenText = matches[0]
                            Log.d(TAG, "SpeechRecognizer Result: $spokenText")
                            activeViewModel?.updateTranscript("You: $spokenText")
                            sendTextMessage(spokenText)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                speechRecognizer?.startListening(recognizerIntent)
                Log.d(TAG, "Started listening to user mic in service...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition in service", e)
            }
        }
    }

    private fun cancelListening() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel speech recognition in service", e)
            }
        }
    }

    /**
     * 6.3 Configures the audio track to play through the device's main speaker stream
     */
    private fun configureAudioRouting() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        Log.d(TAG, "Routing WebRTC stream to main phone speaker channel.")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.isSpeakerphoneOn = true
        } else {
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ChronosAI AI Voice Link",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps persistent connections with ChronosAI core and schedules task notifications."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundServiceCompat() {
        val notificationIntent = Intent(this, VoiceReceiverService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ChronosAI Voice Agent Active")
            .setContentText("Neural Link fully established. Direct link to ChronosAI ready.")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        // Handle Android 14+ specific foreground service type checks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showLocalNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ChronosAI Task Reminder")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(999, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "VoiceReceiverService destroyed. Cancelling scopes.")
        activeViewModel?.let { vm ->
            vm.onSpeakRequested = null
            vm.onListenRequested = null
        }
        try {
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup of TTS/SpeechRecognizer failed", e)
        }
        terminateWebRtcRoom()
        serviceScope.cancel()
    }
}
