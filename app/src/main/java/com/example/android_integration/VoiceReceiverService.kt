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
import android.os.IBinder
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

/**
 * VoiceReceiverService: Persistent Android Foreground Service dedicated to maintaining
 * the LiveKit WebRTC media socket connection alive, overriding Android Background Throttling rules.
 *
 * DROP-IN INSTRUCTIONS:
 * 1. Place this file inside your package.
 * 2. Ensure your build.gradle.kts has LiveKit Android SDK:
 *    `implementation("io.livekit:livekit-android:1.5.0")` (or appropriate version)
 * 3. Bind to this Service or start it with an Intent passing the token.
 */
class VoiceReceiverService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val binder = LocalBinder()
    
    // LiveKit Room State instance
    private var liveKitRoom: Room? = null
    private var isConnected = false

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
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceReceiverService = this@VoiceReceiverService
    }

    /**
     * Send text message to the AI Agent via LiveKit Data Channel.
     */
    fun sendTextMessage(text: String) {
        if (!isConnected) {
            Log.e(TAG, "Cannot send text message. Room is not connected.")
            return
        }
        serviceScope.launch {
            try {
                val participant = liveKitRoom?.localParticipant
                if (participant != null) {
                    // Send text to the 'chat' topic for the agent to receive
                    val data = text.toByteArray(Charsets.UTF_8)
                    participant.publishData(
                        data = data,
                        reliability = io.livekit.android.room.track.DataPublishReliability.RELIABLE,
                        topic = "chat"
                    )
                    Log.d(TAG, "Sent text message to chat channel successfully.")
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

                // Enable the local microphone to capture speech and send it to the AI agent
                liveKitRoom?.localParticipant?.setMicrophoneEnabled(true)

                isConnected = true
                Log.d(TAG, "Successfully connected to LiveKit Voice Room: room-user and enabled microphone.")
                
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
                liveKitRoom?.disconnect()
                liveKitRoom = null
                isConnected = false
                Log.d(TAG, "Disconnected successfully on demand.")
            } catch (e: Exception) {
                Log.e(TAG, "Error executing LiveKit disconnect sequence", e)
            } finally {
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
            else -> Log.v(TAG, "Unprocessed livekit room event: ${event::class.java.simpleName}")
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
            // Android 12+ Audio Routing API
            audioManager.isSpeakerphoneOn = true
        } else {
            // Android legacy API support
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "VoiceReceiverService destroyed. Cancelling scopes.")
        terminateWebRtcRoom()
        serviceScope.cancel()
    }
}
