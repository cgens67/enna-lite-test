/*
 * ArchiveTune Project Original (2026)
 * Chartreux Westia (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 * Don't remove this copyright holder!
 */

@file:Suppress("DEPRECATION")

package com.enna.lite.playback

import android.app.PendingIntent
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.enna.lite.innertube.YouTube
import com.enna.lite.innertube.models.SongItem
import com.enna.lite.lyrics.LyricsPreloadManager
import com.enna.lite.innertube.models.WatchEndpoint
import com.enna.lite.MainActivity
import com.enna.lite.R
import com.enna.lite.constants.*
import com.enna.lite.constants.MediaSessionConstants.CommandToggleLike
import com.enna.lite.constants.MediaSessionConstants.CommandToggleStartRadio
import com.enna.lite.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.enna.lite.constants.MediaSessionConstants.CommandToggleShuffle
import com.enna.lite.db.MusicDatabase
import com.enna.lite.db.entities.*
import com.enna.lite.extensions.*
import com.enna.lite.lyrics.LyricsHelper
import com.enna.lite.models.PersistQueue
import com.enna.lite.models.PersistPlayerState
import com.enna.lite.utils.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import timber.log.Timber

@AndroidEntryPoint
class MusicService : MediaLibraryService(), Player.Listener, PlaybackStatsListener.Callback {
    @Inject lateinit var database: MusicDatabase
    @Inject lateinit var lyricsHelper: LyricsHelper
    @Inject lateinit var syncUtils: SyncUtils

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var pauseOnDeviceMuteEnabled = false
    private var wasAutoPausedByDeviceMute = false
    private var hasAudioFocus = false
    private var duckingRecoveryJob: Job? = null
    private var autoStartOnBluetoothEnabled = false
    private var bluetoothReceiverRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakelockEnabled = false
    private var audioDeviceCallbackRegistered = false
    private var audioRouteRecoveryJob: Job? = null
    private var lastAudioOutputDeviceSignature: String? = null
    private var lastAudioRouteRecoveryRealtimeMs = 0L

    private var scopeJob = Job()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val binder = MusicBinder()
    private var hasBoundClients = false
    private var idleStopJob: Job? = null

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private val playbackUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
    private val contentLengthCache = ConcurrentHashMap<String, Long>()

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null
    private val persistentStateLock = Any()
    @Volatile private var isRestoringPersistentState = false
    @Volatile private var suppressAutoPlayback = false
    @Volatile private var lastPresenceUpdateTime = 0L
    @Volatile private var lastLoginRecoveryPrompt: Pair<String, Long>? = null
    private val playbackStreamRecoveryTracker = PlaybackStreamRecoveryTracker()
    
    // History Tracking Variables
    private var nextHistorySessionToken = 0L
    private var currentHistorySessionToken = 0L
    private var currentHistoryMediaId: String? = null
    private var currentHistoryAccumulatedPlayMs = 0L
    private var currentHistoryStartedAtElapsedMs: Long? = null
    private var currentHistoryEventId: Long? = null
    private var currentHistoryRemoteRegistered = false
    private var currentHistoryImmediateAttempted = false
    private var currentHistorySessionQueued = false
    private var historyThresholdJob: Job? = null
    private val pendingHistoryFinalizations = mutableMapOf<String, MutableList<PendingHistoryFinalization>>()
    private val historyRecordingJobs = ConcurrentHashMap<Long, Deferred<ImmediateHistoryResult>>()

    val currentMediaMetadata = MutableStateFlow<com.enna.lite.models.MediaMetadata?>(null)
    val queueRestoreCompleted = MutableStateFlow(false)
    val infiniteQueueLoading = MutableStateFlow(false)

    private val normalizeFactor = MutableStateFlow(1f)
    var playerVolume = MutableStateFlow(1f)
    private val audioFocusVolumeFactor = MutableStateFlow(1f)
    private var crossfadeAudioProcessor: CrossfadeAudioProcessor? = null
    private var lyricsPreloadManager: LyricsPreloadManager? = null

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    lateinit var sleepTimer: SleepTimer

    private var isAudioEffectSessionOpened = false
    private var openedAudioSessionId: Int? = null
    val eqCapabilities = MutableStateFlow<EqCapabilities?>(null)
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val currentSong = currentMediaMetadata
        .flatMapLatest { database.song(it?.id) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, null)

    private val currentFormat = currentMediaMetadata
        .flatMapLatest { database.format(it?.id) }
        .flowOn(Dispatchers.IO)

    @Volatile private var hasCalledStartForeground = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink }) onAudioOutputDeviceChanged()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink }) onAudioOutputDeviceChanged()
        }
    }

    private data class PendingHistoryFinalization(
        val sessionToken: Long,
        val eventId: Long?,
        val remoteRegistered: Boolean,
    )

    private data class ImmediateHistoryResult(
        val eventId: Long?,
        val remoteRegistered: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        ensureScopesActive()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.music_player), NotificationManager.IMPORTANCE_LOW))
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setRenderersFactory(createRenderersFactory { crossfadeAudioProcessor = it })
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(playbackAudioAttributes(), false)
            .build()
            .apply {
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
            }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocusRequest()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, android.os.Handler(mainLooper))
        audioDeviceCallbackRegistered = true

        mediaSession = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {})
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setBitmapLoader(CoilBitmapLoader(this, scope))
            .build()

        connectivityObserver = NetworkConnectivityObserver(this)
        
        scope.launch {
            connectivityObserver.networkStatus.collect { isNetworkConnected.value = it }
        }

        combine(playerVolume, normalizeFactor, audioFocusVolumeFactor) { vol, norm, focus ->
            calculateEffectivePlayerVolume(vol, norm, focus)
        }.collectLatest(scope) { player.volume = it }

        // Start lyrics pre-load manager
        lyricsPreloadManager = LyricsPreloadManager(this, database, connectivityObserver)

        // Restore state
        scope.launch(Dispatchers.IO) {
            if (dataStore.get(PersistentQueueKey, true)) {
                val persistedQueue = readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                if (persistedQueue != null) restorePersistentQueue(persistedQueue)
            }
            queueRestoreCompleted.value = true
        }
    }

    private fun ensureScopesActive() {
        if (!scopeJob.isActive) scopeJob = Job()
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.Main + scopeJob)
        if (!ioScope.isActive) ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        beginHistorySession(mediaItem?.mediaId, forceNew = true)
        
        val currentIndex = player.currentMediaItemIndex
        val queue = player.mediaItems.mapNotNull { it.metadata }
        if (queue.isNotEmpty()) {
            lyricsPreloadManager?.onSongChanged(currentIndex, queue)
        }
        currentMediaMetadata.value = player.currentMetadata
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        updateHistoryTrackingPlaybackState()
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            enqueueCurrentHistorySessionForFinalization()
        }
    }

    // Logic for History Recording
    private fun beginHistorySession(mediaId: String?, forceNew: Boolean = false) {
        if (!forceNew && currentHistoryMediaId == mediaId) return
        enqueueCurrentHistorySessionForFinalization()
        currentHistorySessionToken = ++nextHistorySessionToken
        currentHistoryMediaId = mediaId
        currentHistoryAccumulatedPlayMs = 0L
        currentHistoryStartedAtElapsedMs = null
        updateHistoryTrackingPlaybackState()
    }

    private fun updateHistoryTrackingPlaybackState() {
        if (player.isPlaying) {
            if (currentHistoryStartedAtElapsedMs == null) {
                currentHistoryStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            }
        } else {
            flushCurrentHistoryPlayedTime()
        }
    }

    private fun flushCurrentHistoryPlayedTime() {
        val now = android.os.SystemClock.elapsedRealtime()
        currentHistoryAccumulatedPlayMs += currentHistoryStartedAtElapsedMs?.let { now - it } ?: 0L
        currentHistoryStartedAtElapsedMs = null
    }

    private fun enqueueCurrentHistorySessionForFinalization() {
        val mediaId = currentHistoryMediaId ?: return
        pendingHistoryFinalizations.getOrPut(mediaId) { mutableListOf() }.add(
            PendingHistoryFinalization(currentHistorySessionToken, currentHistoryEventId, currentHistoryRemoteRegistered)
        )
    }

    override fun onDestroy() {
        scopeJob.cancel()
        if (audioDeviceCallbackRegistered) audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession
    
    override fun onPlaybackStatsReady(eventTime: AnalyticsListener.EventTime, playbackStats: PlaybackStats) {
        // Stats implementation
    }

    private fun setupAudioFocusRequest() { /* ... */ }
    private fun onAudioOutputDeviceChanged() { /* ... */ }
    private fun playbackAudioAttributes() = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build()
    private fun calculateEffectivePlayerVolume(v: Float, n: Float, f: Float) = (v * n * f).coerceIn(0f, 1.5f)
    private fun createMediaSourceFactory() = DefaultMediaSourceFactory(this)
    private fun createRenderersFactory(cb: (CrossfadeAudioProcessor) -> Unit) = DefaultRenderersFactory(this)
    private suspend fun restorePersistentQueue(q: PersistQueue) { /* ... */ }
    private inline fun <reified T> readPersistentObject(f: String): T? = null 

    inner class MusicBinder : Binder() {
        val service: MusicService get() = this@MusicService
    }

    companion object {
        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
    }
}