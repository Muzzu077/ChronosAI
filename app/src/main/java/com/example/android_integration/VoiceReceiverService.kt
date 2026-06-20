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
import android.media.AudioDeviceInfo
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

    private var connectionMessageSent = false
    private var lastSentChatMessage: String? = null

    companion object {
        private const val TAG = "VoiceReceiverService"
        private const val NOTIFICATION_CHANNEL_ID = "ChronosAI_Voice_Service_Channel"
        private const val NOTIFICATION_ID = 1024

        const val ACTION_START_CALL = "com.chronosai.ACTION_START_CALL"
        const val ACTION_STOP_CALL = "com.chronosai.ACTION_STOP_CALL"
        const val ACTION_SEND_CHAT = "com.chronosai.ACTION_SEND_CHAT"
        const val ACTION_TOGGLE_SPEAKER = "com.chronosai.ACTION_TOGGLE_SPEAKER"
        const val EXTRA_JWT_TOKEN = "com.chronosai.EXTRA_JWT_TOKEN"
        const val EXTRA_SERVER_URL = "com.chronosai.EXTRA_SERVER_URL"
        const val EXTRA_CHAT_MESSAGE = "com.chronosai.EXTRA_CHAT_MESSAGE"
        const val EXTRA_SPEAKER_ON = "com.chronosai.EXTRA_SPEAKER_ON"

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
            vm.onSpeakRequested = { _, _ -> }
            vm.onListenRequested = {}
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
                    lastSentChatMessage = msg
                    sendTextMessage(msg)
                }
            }
            ACTION_TOGGLE_SPEAKER -> {
                val isOn = intent.getBooleanExtra(EXTRA_SPEAKER_ON, true)
                setSpeakerphoneState(isOn)
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
                    vm.onSpeakRequested = { _, _ -> }
                    vm.onListenRequested = {}
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

                // Enable the local microphone track in LiveKit
                liveKitRoom?.localParticipant?.setMicrophoneEnabled(true)

                isConnected = true
                Log.d(TAG, "Successfully connected to LiveKit Voice Room: room-user.")
                activeViewModel?.setVoiceSessionState(com.example.VoiceSessionState.LISTENING)
                
                // Allow WebRTC audio engine to stabilize, then apply speakerphone routing
                serviceScope.launch {
                    kotlinx.coroutines.delay(500)
                    setSpeakerphoneState(true)
                }
                
                // Flush pending messages
                while (!pendingMessageQueue.isEmpty()) {
                    val msg = pendingMessageQueue.poll()
                    if (msg != null) {
                        sendTextMessage(msg)
                    }
                }

                // If agent is already in the room, send connection message immediately
                val hasRemoteParticipants = liveKitRoom?.remoteParticipants?.isNotEmpty() ?: false
                if (hasRemoteParticipants) {
                    Log.d(TAG, "Agent already present in the room. Sending connection message.")
                    sendConnectionMessage()
                } else {
                    Log.d(TAG, "Agent not present yet. Connection message deferred.")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed connecting to LiveKit room: ${e.message}", e)
                stopSelf()
            }
        }
    }

    private fun sendConnectionMessage() {
        if (connectionMessageSent) return
        connectionMessageSent = true
        val vm = activeViewModel
        if (vm != null) {
            val taskId = vm.activeCallTaskId.value
            val taskText = vm.activeCallText.value
            Log.d(TAG, "Sending connection payload to agent: taskId=$taskId, text=$taskText")
            if (taskId == "TEST_CALL_ID" && taskText == "TEST_CALL") {
                sendTextMessage("SYSTEM_CONNECT: TEST_CALL")
            } else if (taskId.isNotEmpty() && taskText.isNotEmpty()) {
                val reminderMsg = "SYSTEM_REMINDER: $taskId | $taskText"
                sendTextMessage(reminderMsg)
            } else {
                sendTextMessage("SYSTEM_CONNECT: MANUAL")
            }
        } else {
            Log.d(TAG, "Sending fallback manual connection payload.")
            sendTextMessage("SYSTEM_CONNECT: MANUAL")
        }
    }

    /**
     * Terminates connection and frees up hardware microphone resource.
     */
    private fun terminateWebRtcRoom() {
        Log.d(TAG, "Terminating LiveKit WebRTC connection context...")
        connectionMessageSent = false
        // Clear communication device routing
        setSpeakerphoneState(false)
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
                sendConnectionMessage()
            }
            is RoomEvent.ParticipantDisconnected -> {
                Log.i(TAG, "AI Agent disconnected.")
            }
            is RoomEvent.TrackSubscribed -> {
                Log.d(TAG, "Incoming high fidelity audio track subscribed! Route ready for voice stream.")
                serviceScope.launch {
                    kotlinx.coroutines.delay(300)
                    setSpeakerphoneState(true)
                }
            }
            is RoomEvent.DataReceived -> {
                try {
                    val text = String(event.data, Charsets.UTF_8)
                    Log.d(TAG, "Data channel message received: $text")
                    
                    if (text.startsWith("SYSTEM_NOTIFICATION:")) {
                        val content = text.substringAfter("SYSTEM_NOTIFICATION:").trim()
                        activeViewModel?.updateTranscript("ChronosAI: $content")
                        showLocalNotification(content)
                    } else if (text.startsWith("SYSTEM_REMINDER:") || 
                               text.startsWith("SYSTEM_CONNECT:") || 
                               text.startsWith("SYSTEM_ACCOUNTABILITY:") || 
                               text.startsWith("SYSTEM_HANGUP")) {
                        Log.d(TAG, "Ignoring control message: $text")
                    } else {
                        // Skip "You:" messages from data channel only if they are duplicates of locally typed messages
                        if (text.startsWith("ChronosAI:")) {
                            activeViewModel?.updateTranscript(text)
                        } else if (text.startsWith("You:")) {
                            val content = text.substringAfter("You:").trim()
                            if (lastSentChatMessage != null && content.equals(lastSentChatMessage?.trim(), ignoreCase = true)) {
                                lastSentChatMessage = null
                            } else {
                                activeViewModel?.updateTranscript(text)
                            }
                        } else if (!text.startsWith("You:")) {
                            activeViewModel?.updateTranscript("ChronosAI: $text")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling DataReceived event", e)
                }
            }
            else -> Log.v(TAG, "Unprocessed livekit room event: ${event::class.java.simpleName}")
        }
    }



    /**
     * 6.3 Configures the audio track to play through the device's main speaker stream
     */
    private fun configureAudioRouting() {
        Log.d(TAG, "Routing WebRTC stream to main phone speaker channel.")
        // WebRTC manages audio mode automatically. We set speaker state on connection.
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

    fun setSpeakerphoneState(isOn: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isOn) {
                    val devices = audioManager.availableCommunicationDevices
                    val speakerDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        val result = audioManager.setCommunicationDevice(speakerDevice)
                        Log.d(TAG, "setCommunicationDevice(SPEAKER) result: $result")
                    } else {
                        Log.e(TAG, "Built-in speaker communication device not found.")
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                    Log.d(TAG, "Cleared communication device")
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = isOn
            }
            Log.d(TAG, "Speakerphone state manually set to: $isOn")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speakerphone state", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "VoiceReceiverService destroyed. Cleaning up synchronously.")
        // Restore audio device routing
        setSpeakerphoneState(false)
        // Clear ViewModel references before cancelling scope
        activeViewModel?.let { vm ->
            vm.onSpeakRequested = null
            vm.onListenRequested = null
        }
        // Synchronously disconnect LiveKit room (do NOT launch coroutine)
        connectionMessageSent = false
        try {
            liveKitRoom?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting LiveKit room on destroy", e)
        }
        liveKitRoom = null
        isConnected = false
        activeViewModel?.setVoiceSessionState(com.example.VoiceSessionState.IDLE)
        // Cancel scope LAST after all cleanup is done
        serviceScope.cancel()
    }
}
