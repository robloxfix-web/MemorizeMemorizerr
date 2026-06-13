package com.example.personalmemorizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.VideoView
import androidx.core.app.NotificationCompat
import java.io.File

class MemorizerService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var videoView: VideoView? = null
    
    private var silentTrack: AudioTrack? = null
    private var path1: String? = null
    private var path2: String? = null
    private var isTurnForFirst = true

    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    // ===== الفاصل الزمني الثابت الذي يحدده المستخدم =====
    private var fixedIntervalMs: Long = 30_000L  // القيمة الافتراضية 30 ثانية

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:VideoLock")
        wakeLock?.setReferenceCounted(false)
        
        setupSilentAudio()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            killService()
            return START_NOT_STICKY
        }

        if (intent?.action == "ACTION_START") {
            path1 = intent.getStringExtra("filePath1")
            path2 = intent.getStringExtra("filePath2")

            // ===== استقبال الفاصل الزمني من MainActivity =====
            fixedIntervalMs = intent.getLongExtra("intervalMs", 30_000L)

            if (path1 != null) {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                wakeLock?.acquire(24 * 60 * 60 * 1000L)
                isTurnForFirst = true
                
                createNotificationChannel()
                startForeground(1, buildNotification("Video Service Active"))
                
                playRealVideo()
            }
        }
        return START_STICKY
    }

    private fun playRealVideo() {
        val fileToPlay = if (isTurnForFirst || path2 == null) path1 else path2
        if (fileToPlay == null) return

        pauseSilentAudio()
        updateNotification("🎬 Playing Video...")

        try {
            requestFocusCall()
            handler.post {
                showFloatingWindow(fileToPlay)
            }
        } catch (e: Exception) {
            startWaitingPeriod()
        }
    }

    private fun showFloatingWindow(filePath: String) {
        removeFloatingWindow()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.layout_floating_video, null)
        videoView = floatingView?.findViewById(R.id.floatingVideoView)

        videoView?.setVideoPath(filePath)
        
        videoView?.setOnPreparedListener { mp ->
            mp.start()
        }

        videoView?.setOnCompletionListener {
            removeFloatingWindow()
            abandonFocusCall()
            if (path2 != null) isTurnForFirst = !isTurnForFirst
            startWaitingPeriod()
        }

        videoView?.setOnErrorListener { _, _, _ ->
            removeFloatingWindow()
            startWaitingPeriod()
            true
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            startWaitingPeriod()
        }
    }

    private fun removeFloatingWindow() {
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {}
            floatingView = null
        }
    }

    private fun startWaitingPeriod() {
        // ===== استخدام الفاصل الثابت الذي اختاره المستخدم =====
        val displayText = if (fixedIntervalMs < 60_000L) {
            "${fixedIntervalMs / 1000}s"
        } else {
            "${fixedIntervalMs / 60_000}min"
        }
        updateNotification("⏳ Next video in: $displayText")
        playSilentAudio()

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            playRealVideo()
        }, fixedIntervalMs)
    }

    private fun killService() {
        try {
            handler.removeCallbacksAndMessages(null)
            removeFloatingWindow()
            silentTrack?.release()
            silentTrack = null
            if (wakeLock?.isHeld == true) wakeLock?.release()
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {}
    }

    private fun setupSilentAudio() {
        try {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            silentTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val silentData = ByteArray(bufferSize)
            silentTrack?.write(silentData, 0, silentData.size)
            silentTrack?.setLoopPoints(0, bufferSize / 2, -1)
        } catch (e: Exception) {}
    }

    private fun playSilentAudio() {
        try {
            if (silentTrack?.state == AudioTrack.STATE_INITIALIZED && silentTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                silentTrack?.play()
            }
        } catch (e: Exception) {}
    }

    private fun pauseSilentAudio() {
        try {
            if (silentTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                silentTrack?.pause()
            }
        } catch (e: Exception) {}
    }

    private fun requestFocusCall() {
        val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
        } else null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonFocusCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
             audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun buildNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "memorizer_video")
            .setContentTitle("Memorizer Video")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("memorizer_video", "Memorizer Video Service", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        killService()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
