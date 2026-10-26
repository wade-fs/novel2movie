package com.novel.reader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.novel.reader.R
import com.novel.reader.model.*
import com.novel.reader.parser.NovelChapterParser
import com.novel.reader.repository.NovelRepository
import com.novel.reader.ui.MainActivity
import com.novel.reader.util.ReadingProgressManager
import kotlinx.coroutines.*
import java.util.*

class TtsReadingService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "novel_tts_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.novel.reader.ACTION_PLAY"
        const val ACTION_PAUSE = "com.novel.reader.ACTION_PAUSE"
        const val ACTION_PREV_CHAPTER = "com.novel.reader.ACTION_PREV_CHAPTER"
        const val ACTION_NEXT_CHAPTER = "com.novel.reader.ACTION_NEXT_CHAPTER"
        const val ACTION_STOP = "com.novel.reader.ACTION_STOP"

        val TAIWAN_LOCALE = Locale("zh", "TW")
    }

    enum class PlaybackState {
        IDLE, PLAYING, PAUSED
    }

    inner class LocalBinder : Binder() {
        fun getService(): TtsReadingService = this@TtsReadingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set

    lateinit var repository: NovelRepository
        private set
    lateinit var progressManager: ReadingProgressManager
        private set
    lateinit var audioManager: AudioManager
        private set
    lateinit var mediaSession: MediaSessionCompat
        private set

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isNoisyReceiverRegistered = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (playbackState == PlaybackState.PLAYING) {
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playbackState == PlaybackState.PLAYING) {
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 音訊焦點恢復
            }
        }
    }

    // 當耳機被拔出或藍牙耳機斷開時，自動暫停朗讀避免外放擴音
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (playbackState == PlaybackState.PLAYING) {
                    pause()
                }
            }
        }
    }

    // 監聽藍牙耳機及系統音量按鈕動作（長按或連續觸發）
    private var lastVolumeChangeTime = 0L
    private var lastVolumeDirection = 0
    private var volumeBurstCount = 0
    private var lastChapterSwitchTime = 0L
    private var isVolumeReceiverRegistered = false

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val currentVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastVolumeChangeTime

                    val direction = when {
                        currentVol > prevVol -> 1
                        currentVol < prevVol -> -1
                        currentVol == maxVol && prevVol == maxVol -> 1
                        currentVol == 0 && prevVol == 0 -> -1
                        else -> 0
                    }

                    if (direction != 0) {
                        // 當在 500ms 內連續收到同方向的音量變更（藍牙耳機長按音量鍵或快速連擊）
                        if (direction == lastVolumeDirection && timeDiff < 500) {
                            volumeBurstCount++
                            if (volumeBurstCount >= 2) {
                                volumeBurstCount = 0
                                lastVolumeChangeTime = 0L
                                if (direction == 1) {
                                    playNextChapter()
                                } else {
                                    playPreviousChapter()
                                }
                            }
                        } else {
                            lastVolumeDirection = direction
                            volumeBurstCount = 1
                        }
                        lastVolumeChangeTime = now
                    }
                }
            }
        }
    }

    var chapters: List<Chapter> = emptyList()
        private set
    var currentChapterIndex: Int = 0
        private set
    var currentContent: ChapterContent? = null
        private set
    var currentSegments: List<TtsSegment> = emptyList()
        private set
    var currentSegmentIndex: Int = 0
        private set

    var playbackState: PlaybackState = PlaybackState.IDLE
        private set

    var settings: ReadingSettings = ReadingSettings()
        private set

    // 回呼介面供 UI 即時更新狀態
    interface PlaybackListener {
        fun onPlaybackStateChanged(state: PlaybackState)
        fun onChapterLoaded(chapter: Chapter, content: ChapterContent, segments: List<TtsSegment>, initialSegmentIndex: Int)
        fun onSegmentChanged(segmentIndex: Int, segment: TtsSegment?)
        fun onTtsReady(availableVoices: List<Voice>)
    }

    private val listeners = mutableListOf<PlaybackListener>()

    fun addListener(listener: PlaybackListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            if (isTtsReady) {
                listener.onTtsReady(getAvailableChineseVoices())
            }
            val content = currentContent
            if (content != null && currentChapterIndex in chapters.indices) {
                listener.onChapterLoaded(chapters[currentChapterIndex], content, currentSegments, currentSegmentIndex)
            }
            listener.onPlaybackStateChanged(playbackState)
            if (currentSegmentIndex in currentSegments.indices) {
                listener.onSegmentChanged(currentSegmentIndex, currentSegments[currentSegmentIndex])
            }
        }
    }

    fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        repository = NovelRepository(applicationContext)
        progressManager = ReadingProgressManager(applicationContext)
        settings = progressManager.loadSettings()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        setupMediaSession()
        tts = TextToSpeech(applicationContext, this)

        val volumeFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        registerReceiver(volumeChangeReceiver, volumeFilter)
        isVolumeReceiverRegistered = true
    }

    private fun setupMediaSession() {
        val mediaButtonReceiverComponent = ComponentName(applicationContext, MediaButtonReceiver::class.java)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = mediaButtonReceiverComponent
        }
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(
            this,
            "NovelReaderMediaSession",
            mediaButtonReceiverComponent,
            mediaButtonPendingIntent
        ).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    this@TtsReadingService.play()
                }

                override fun onPause() {
                    this@TtsReadingService.pause()
                }

                override fun onSkipToNext() {
                    this@TtsReadingService.playNextChapter()
                }

                override fun onSkipToPrevious() {
                    this@TtsReadingService.playPreviousChapter()
                }

                override fun onFastForward() {
                    this@TtsReadingService.playNextChapter()
                }

                override fun onRewind() {
                    this@TtsReadingService.playPreviousChapter()
                }

                override fun onStop() {
                    this@TtsReadingService.stop()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                play()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                pause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                if (playbackState == PlaybackState.PLAYING) pause() else play()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                                playNextChapter()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND,
                            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                                playPreviousChapter()
                                return true
                            }
                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                if (keyEvent.isLongPress || keyEvent.repeatCount > 0) {
                                    playNextChapter()
                                    return true
                                }
                            }
                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                if (keyEvent.isLongPress || keyEvent.repeatCount > 0) {
                                    playPreviousChapter()
                                    return true
                                }
                            }
                            KeyEvent.KEYCODE_MEDIA_STOP -> {
                                stop()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }
        updateMediaSessionPlaybackState()
    }

    fun updateMediaSessionPlaybackState() {
        if (!::mediaSession.isInitialized) return
        val state = when (playbackState) {
            PlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackState.IDLE -> PlaybackStateCompat.STATE_STOPPED
        }
        val playbackStateCompat = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackStateCompat)

        val chapter = chapters.getOrNull(currentChapterIndex)
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapter?.title ?: "小說閱讀")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, repository.currentNovelTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "第 ${currentChapterIndex + 1} 章")
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            isNoisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (_: Exception) {}
            isNoisyReceiverRegistered = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 預設強制使用台灣繁體中文 (zh-TW)
            val langResult = tts?.setLanguage(TAIWAN_LOCALE)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.TRADITIONAL_CHINESE)
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    serviceScope.launch {
                        handleUtteranceDone()
                    }
                }

                override fun onError(utteranceId: String?) {
                    serviceScope.launch {
                        handleUtteranceDone()
                    }
                }
            })
            isTtsReady = true

            // 預設自動綁定台灣發音人
            selectDefaultTaiwanVoice()
            applyTtsParameters()
            notifyTtsReady()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_PREV_CHAPTER -> playPreviousChapter()
            ACTION_NEXT_CHAPTER -> playNextChapter()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    /**
     * 載入並設定章節清單，並可指定初始章節與段落
     */
    fun setChapterList(
        list: List<Chapter>,
        initialIndex: Int = 0,
        initialSegmentIndex: Int = 0,
        startPlay: Boolean = false
    ) {
        chapters = list
        loadChapter(initialIndex, startPlay, initialSegmentIndex)
    }

    /**
     * 載入特定章節
     */
    fun loadChapter(index: Int, autoPlay: Boolean = false, targetSegmentIndex: Int = 0) {
        if (index !in chapters.indices) return
        currentChapterIndex = index
        val chapter = chapters[index]

        serviceScope.launch {
            val content = repository.loadChapterContent(chapter)
            currentContent = content
            regenerateSegments()
            currentSegmentIndex = targetSegmentIndex.coerceIn(0, maxOf(0, currentSegments.size - 1))

            notifyChapterLoaded(chapter, content, currentSegments, currentSegmentIndex)
            saveCurrentProgress()
            updateMediaSessionPlaybackState()
            updateNotification()

            if (autoPlay) {
                play()
            } else if (playbackState == PlaybackState.PLAYING) {
                speakCurrentSegment()
            }
        }
    }

    /**
     * 重新根據 FilterConfig 計算要朗讀的 segments
     */
    fun regenerateSegments() {
        val content = currentContent ?: return
        currentSegments = NovelChapterParser.buildTtsSegments(content, settings.filterConfig)
        if (currentSegmentIndex >= currentSegments.size) {
            currentSegmentIndex = 0
        }
    }

    /**
     * 開始 / 繼續朗讀
     */
    fun play() {
        if (currentSegments.isEmpty()) {
            regenerateSegments()
        }
        if (currentSegments.isEmpty()) return

        requestAudioFocus()
        registerNoisyReceiver()

        playbackState = PlaybackState.PLAYING
        startForeground(NOTIFICATION_ID, buildNotification())
        notifyPlaybackState(playbackState)
        updateMediaSessionPlaybackState()
        speakCurrentSegment()
    }

    /**
     * 暫停朗讀 (儲存進度)
     */
    fun pause() {
        playbackState = PlaybackState.PAUSED
        tts?.stop()
        saveCurrentProgress()
        notifyPlaybackState(playbackState)
        updateMediaSessionPlaybackState()
        updateNotification()
        unregisterNoisyReceiver()
    }

    /**
     * 停止朗讀
     */
    fun stop() {
        playbackState = PlaybackState.IDLE
        tts?.stop()
        saveCurrentProgress()
        currentSegmentIndex = 0
        notifyPlaybackState(playbackState)
        updateMediaSessionPlaybackState()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * 跳至指定 UI 位置 (點擊段落直接開始朗讀)
     */
    fun seekToUiPosition(uiPos: Int) {
        val targetIdx = currentSegments.indexOfFirst { it.uiPosition == uiPos }
        if (targetIdx != -1) {
            currentSegmentIndex = targetIdx
            if (playbackState != PlaybackState.PLAYING) {
                playbackState = PlaybackState.PLAYING
                startForeground(NOTIFICATION_ID, buildNotification())
                notifyPlaybackState(playbackState)
            }
            speakCurrentSegment()
        }
    }

    /**
     * 上一段
     */
    fun previousSegment() {
        if (currentSegmentIndex > 0) {
            currentSegmentIndex--
            speakCurrentSegment()
        } else {
            playPreviousChapter()
        }
    }

    /**
     * 下一段
     */
    fun nextSegment() {
        if (currentSegmentIndex < currentSegments.size - 1) {
            currentSegmentIndex++
            speakCurrentSegment()
        } else {
            playNextChapter()
        }
    }

    /**
     * 上一章
     */
    fun playPreviousChapter() {
        val now = System.currentTimeMillis()
        if (now - lastChapterSwitchTime < 1000) return
        lastChapterSwitchTime = now
        if (currentChapterIndex > 0) {
            loadChapter(currentChapterIndex - 1, autoPlay = true)
        }
    }

    /**
     * 下一章 (要求必須自動換下一章)
     */
    fun playNextChapter() {
        val now = System.currentTimeMillis()
        if (now - lastChapterSwitchTime < 1000) return
        lastChapterSwitchTime = now
        if (currentChapterIndex < chapters.size - 1) {
            loadChapter(currentChapterIndex + 1, autoPlay = true)
        } else {
            stop()
        }
    }

    /**
     * 更新語速、語調、性別及過濾設定，並持久化至 SharedPreferences
     */
    fun updateSettings(newSettings: ReadingSettings) {
        this.settings = newSettings
        progressManager.saveSettings(newSettings)

        applyTtsParameters()
        val curSeg = currentSegments.getOrNull(currentSegmentIndex)
        regenerateSegments()
        if (curSeg != null) {
            val newIdx = currentSegments.indexOfFirst { it.id == curSeg.id || it.uiPosition == curSeg.uiPosition }
            currentSegmentIndex = if (newIdx != -1) newIdx else 0
        }
        val chapter = chapters.getOrNull(currentChapterIndex)
        val content = currentContent
        if (chapter != null && content != null) {
            notifyChapterLoaded(chapter, content, currentSegments, currentSegmentIndex)
        }
    }

    private fun applyTtsParameters() {
        val t = tts ?: return
        if (!isTtsReady) return

        t.setSpeechRate(settings.speechRate)

        var effectivePitch = settings.pitch
        when (settings.gender) {
            VoiceGender.MALE -> {
                effectivePitch = settings.pitch * 0.88f
                selectVoiceByGender(isFemale = false)
            }
            VoiceGender.FEMALE -> {
                effectivePitch = settings.pitch * 1.12f
                selectVoiceByGender(isFemale = true)
            }
            VoiceGender.DEFAULT -> {
                if (settings.selectedVoiceName != null) {
                    selectVoiceByName(settings.selectedVoiceName!!)
                } else {
                    selectDefaultTaiwanVoice()
                }
            }
        }
        t.setPitch(effectivePitch)
    }

    /**
     * 優先選取台灣 (zh-TW / cmn-tw) 口音的男聲或女聲
     */
    private fun selectVoiceByGender(isFemale: Boolean) {
        val voices = getAvailableChineseVoices()

        val targetVoice = if (isFemale) {
            // 1. 優先找台灣女聲 (cmn-tw-x-ctd, female, etc.)
            voices.find { v ->
                val n = v.name.lowercase()
                val isTw = isTaiwanVoice(v)
                val isFem = n.contains("female") || n.contains("woman") || n.contains("ctd") || n.contains("cta")
                isTw && isFem
            } ?: voices.find { v -> isTaiwanVoice(v) } // 2. 任何台灣語音
            ?: voices.find { v ->
                val n = v.name.lowercase()
                n.contains("female") || n.contains("woman") || n.contains("ctd")
            } ?: voices.firstOrNull()
        } else {
            // 1. 優先找台灣男聲 (cmn-tw-x-ctc, male, etc.)
            voices.find { v ->
                val n = v.name.lowercase()
                val isTw = isTaiwanVoice(v)
                val isMale = n.contains("male") || n.contains("man") || n.contains("ctc") || n.contains("ctb")
                isTw && isMale
            } ?: voices.find { v -> isTaiwanVoice(v) } // 2. 任何台灣語音
            ?: voices.find { v ->
                val n = v.name.lowercase()
                n.contains("male") || n.contains("man") || n.contains("ctc")
            } ?: voices.firstOrNull()
        }

        if (targetVoice != null) {
            tts?.voice = targetVoice
        }
    }

    /**
     * 預設選用台灣標準語音 (以台灣女聲 cmn-tw-x-ctd 或繁體中文優先)
     */
    fun selectDefaultTaiwanVoice() {
        val voices = getAvailableChineseVoices()
        val twVoice = voices.find { v ->
            val n = v.name.lowercase()
            isTaiwanVoice(v) && (n.contains("ctd") || n.contains("female") || n.contains("cta"))
        } ?: voices.find { v -> isTaiwanVoice(v) }

        if (twVoice != null) {
            tts?.voice = twVoice
        }
    }

    private fun isTaiwanVoice(v: Voice): Boolean {
        val n = v.name.lowercase()
        return v.locale == TAIWAN_LOCALE ||
                v.locale.country.equals("TW", true) ||
                v.locale.toLanguageTag().contains("TW", true) ||
                n.contains("tw") || n.contains("taiwan")
    }

    fun selectVoiceByName(name: String) {
        val voices = getAvailableChineseVoices()
        val v = voices.find { it.name == name }
        if (v != null) {
            tts?.voice = v
            settings.selectedVoiceName = name
            progressManager.saveSettings(settings)
        }
    }

    /**
     * 取得所有中文語音，並將 🇹🇼 台灣語音排在最前面
     */
    fun getAvailableChineseVoices(): List<Voice> {
        val t = tts ?: return emptyList()
        return try {
            val all = t.voices?.filter { v ->
                val lang = v.locale.language.lowercase()
                lang.startsWith("zh") || lang.startsWith("cmn") || v.locale == Locale.CHINESE || v.locale == TAIWAN_LOCALE
            } ?: emptyList()

            // 台灣口音排在最前面，其餘依序排列
            all.sortedWith(compareByDescending<Voice> { isTaiwanVoice(it) }.thenBy { it.name })
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun speakCurrentSegment() {
        if (!isTtsReady || currentSegments.isEmpty()) return
        if (currentSegmentIndex !in currentSegments.indices) {
            if (settings.autoNextChapter && currentChapterIndex < chapters.size - 1) {
                playNextChapter()
            } else {
                stop()
            }
            return
        }

        val segment = currentSegments[currentSegmentIndex]
        saveCurrentProgress()
        notifySegmentChanged(currentSegmentIndex, segment)
        updateNotification()

        val utteranceId = "seg_${currentChapterIndex}_$currentSegmentIndex"
        tts?.speak(segment.speechText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun handleUtteranceDone() {
        if (playbackState != PlaybackState.PLAYING) return

        if (currentSegmentIndex < currentSegments.size - 1) {
            currentSegmentIndex++
            speakCurrentSegment()
        } else {
            if (settings.autoNextChapter && currentChapterIndex < chapters.size - 1) {
                playNextChapter()
            } else {
                stop()
            }
        }
    }

    fun saveCurrentProgress() {
        val chapter = chapters.getOrNull(currentChapterIndex) ?: return
        val segment = currentSegments.getOrNull(currentSegmentIndex)
        progressManager.saveProgress(
            novelId = repository.currentNovelId,
            novelTitle = repository.currentNovelTitle,
            chapterIndex = currentChapterIndex,
            chapterNumber = chapter.chapterNumber,
            chapterTitle = chapter.title,
            segmentIndex = currentSegmentIndex,
            uiPosition = segment?.uiPosition ?: 0
        )
    }

    // --- 通知列相關 ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "小說有聲朗讀",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "提供背景朗讀與通知列播放控制"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val chapterTitle = chapters.getOrNull(currentChapterIndex)?.title ?: "小說閱讀"
        val segmentText = currentSegments.getOrNull(currentSegmentIndex)?.displayText ?: "朗讀中..."

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getService(
            this, 1, Intent(this, TtsReadingService::class.java).apply { action = ACTION_PREV_CHAPTER },
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseActionIntent = if (playbackState == PlaybackState.PLAYING) {
            PendingIntent.getService(
                this, 2, Intent(this, TtsReadingService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2, Intent(this, TtsReadingService::class.java).apply { action = ACTION_PLAY },
                PendingIntent.FLAG_IMMUTABLE
            )
        }
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, TtsReadingService::class.java).apply { action = ACTION_NEXT_CHAPTER },
            PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (playbackState == PlaybackState.PLAYING) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (playbackState == PlaybackState.PLAYING) "暫停" else "朗讀"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(chapterTitle)
            .setContentText(segmentText)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "上一章", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseActionIntent)
            .addAction(android.R.drawable.ic_media_next, "下一章", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(playbackState == PlaybackState.PLAYING)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (playbackState != PlaybackState.IDLE) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun notifyPlaybackState(state: PlaybackState) {
        listeners.forEach { it.onPlaybackStateChanged(state) }
    }

    private fun notifyChapterLoaded(chapter: Chapter, content: ChapterContent, segments: List<TtsSegment>, initialSegmentIndex: Int) {
        listeners.forEach { it.onChapterLoaded(chapter, content, segments, initialSegmentIndex) }
    }

    private fun notifySegmentChanged(segmentIndex: Int, segment: TtsSegment?) {
        listeners.forEach { it.onSegmentChanged(segmentIndex, segment) }
    }

    private fun notifyTtsReady() {
        val voices = getAvailableChineseVoices()
        listeners.forEach { it.onTtsReady(voices) }
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentProgress()
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        if (isVolumeReceiverRegistered) {
            try {
                unregisterReceiver(volumeChangeReceiver)
            } catch (_: Exception) {}
            isVolumeReceiverRegistered = false
        }
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
    }
}
