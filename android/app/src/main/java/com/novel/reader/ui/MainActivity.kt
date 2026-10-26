package com.novel.reader.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.speech.tts.Voice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.novel.reader.R
import com.novel.reader.databinding.ActivityMainBinding
import com.novel.reader.model.*
import com.novel.reader.repository.NovelRepository
import com.novel.reader.service.TtsReadingService
import com.novel.reader.util.ReadingProgressManager
import com.novel.reader.util.SavedReadingProgress
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), TtsReadingService.PlaybackListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var contentAdapter: NovelContentAdapter
    private lateinit var repository: NovelRepository
    private lateinit var progressManager: ReadingProgressManager

    private var ttsService: TtsReadingService? = null
    private var isBound = false
    private var availableVoices: List<Voice> = emptyList()
    private var pendingSavedProgress: SavedReadingProgress? = null
    private var isImmersiveFullScreen: Boolean = true

    // 長按音量鍵控制章節
    private val keyHandler = Handler(Looper.getMainLooper())
    private var isVolumeUpLongPressed = false
    private var isVolumeDownLongPressed = false

    private val volumeUpLongPressRunnable = Runnable {
        isVolumeUpLongPressed = true
        vibrateFeedback()
        ttsService?.playNextChapter()
        Toast.makeText(this, "長按大聲：切換至下一章", Toast.LENGTH_SHORT).show()
    }

    private val volumeDownLongPressRunnable = Runnable {
        isVolumeDownLongPressed = true
        vibrateFeedback()
        ttsService?.playPreviousChapter()
        Toast.makeText(this, "長按小聲：切換至上一章", Toast.LENGTH_SHORT).show()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsReadingService.LocalBinder
            ttsService = binder.getService()
            isBound = true
            ttsService?.addListener(this@MainActivity)

            if (ttsService?.chapters.isNullOrEmpty()) {
                loadBuiltinNovels()
            } else {
                updatePlaybackControls(ttsService?.playbackState ?: TtsReadingService.PlaybackState.IDLE)
                updateSettingsBadges()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService?.removeListener(this@MainActivity)
            ttsService = null
            isBound = false
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // 開啟外部資料夾 (SAF)
    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            lifecycleScope.launch {
                val externalChapters = repository.loadCustomDirectoryChapters(treeUri)
                if (externalChapters.isNotEmpty()) {
                    ttsService?.setChapterList(externalChapters, 0, startPlay = true)
                    binding.toolbar.title = repository.currentNovelTitle
                    Toast.makeText(this@MainActivity, "成功載入外部目錄：共 ${externalChapters.size} 章", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "所選目錄中未找到小說章節檔案 (.md / .txt)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 開啟 ZIP / TAR 壓縮檔
    private val openArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { archiveUri ->
        if (archiveUri != null) {
            val fileName = queryDisplayName(archiveUri) ?: "novel.zip"
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "正在讀取壓縮檔：$fileName...", Toast.LENGTH_SHORT).show()
                val chapters = repository.loadArchiveChapters(archiveUri, fileName)
                if (chapters.isNotEmpty()) {
                    ttsService?.setChapterList(chapters, 0, startPlay = true)
                    binding.toolbar.title = repository.currentNovelTitle
                    Toast.makeText(this@MainActivity, "成功解開壓縮檔：共 ${chapters.size} 章", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "無法從壓縮檔中讀取到章節檔案", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        // 啟用 Edge-to-Edge 全螢幕佈局，隱藏頂部 status bar 與底部 navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        applyImmersiveSystemBars(hide = true)

        repository = NovelRepository(this)
        progressManager = ReadingProgressManager(this)

        checkNotificationPermission()
        setupRecyclerView()
        setupListeners()
        bindTtsService()
        checkResumeProgress()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isImmersiveFullScreen) {
            applyImmersiveSystemBars(hide = true)
        }
    }

    private fun setupWindowInsets() {
        // 處理頂部 AppBarLayout 內邊距，避免與暫時呼出的狀態列或螢幕挖孔/瀏海重疊
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            val topPadding = maxOf(statusBar, cutout)
            v.setPadding(v.paddingLeft, topPadding, v.paddingRight, v.paddingBottom)
            insets
        }

        // 處理底部播放卡片外邊距，當暫時呼出導航列時自動浮起避免遮蔽
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomPlayerCard) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val baseMargin = (12 * resources.displayMetrics.density).toInt()
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.setMargins(baseMargin, baseMargin, baseMargin, baseMargin + navBar)
                v.layoutParams = lp
            }
            insets
        }
    }

    private fun applyImmersiveSystemBars(hide: Boolean) {
        isImmersiveFullScreen = hide
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (hide) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            binding.btnToggleFullScreen.setIconResource(R.drawable.ic_fullscreen)
            binding.btnToggleFullScreen.contentDescription = getString(R.string.desc_exit_fullscreen)
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            binding.btnToggleFullScreen.setIconResource(R.drawable.ic_fullscreen_exit)
            binding.btnToggleFullScreen.contentDescription = getString(R.string.desc_enter_fullscreen)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun bindTtsService() {
        val intent = Intent(this, TtsReadingService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupRecyclerView() {
        contentAdapter = NovelContentAdapter(
            onItemClick = { uiPosition ->
                ttsService?.seekToUiPosition(uiPosition)
            },
            onPrevChapterClick = {
                ttsService?.playPreviousChapter()
            },
            onNextChapterClick = {
                ttsService?.playNextChapter()
            }
        )
        binding.rvContent.layoutManager = LinearLayoutManager(this)
        binding.rvContent.adapter = contentAdapter
    }

    private fun setupListeners() {
        // 點擊頂部章節資訊橫條直接開啟「直達指定章節」彈窗
        binding.tvCurrentChapterInfo.setOnClickListener {
            showJumpChapterDialog()
        }

        // 匯入小說 (GitHub 網址 / 本機 ZIP / 本機目錄)
        binding.btnImportNovel.setOnClickListener {
            val sheet = ImportBottomSheetDialogFragment(
                onImportSuccess = { chapters, novelTitle, _ ->
                    ttsService?.setChapterList(chapters, 0, startPlay = true)
                    binding.toolbar.title = novelTitle
                },
                onPickArchive = {
                    openArchiveLauncher.launch(arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/x-tar",
                        "application/gzip",
                        "application/x-gzip",
                        "*/*"
                    ))
                },
                onPickDirectory = {
                    openDocumentTreeLauncher.launch(null)
                }
            )
            sheet.show(supportFragmentManager, "ImportSheet")
        }

        // 播放 / 暫停 大按鈕
        binding.fabPlayPause.setOnClickListener {
            val service = ttsService ?: return@setOnClickListener
            if (service.playbackState == TtsReadingService.PlaybackState.PLAYING) {
                service.pause()
            } else {
                service.play()
            }
        }

        // 快速切換：上一章
        binding.btnPrevChapter.setOnClickListener {
            ttsService?.playPreviousChapter()
        }

        // 快速切換：下一章
        binding.btnNextChapter.setOnClickListener {
            ttsService?.playNextChapter()
        }

        // 上一段
        binding.btnPrevParagraph.setOnClickListener {
            ttsService?.previousSegment()
        }

        // 下一段
        binding.btnNextParagraph.setOnClickListener {
            ttsService?.nextSegment()
        }

        // 章節目錄 BottomSheet
        binding.btnChapterList.setOnClickListener {
            val service = ttsService ?: return@setOnClickListener
            val sheet = ChapterListBottomSheetDialogFragment(
                chapters = service.chapters,
                currentIndex = service.currentChapterIndex
            ) { selectedIndex ->
                service.loadChapter(selectedIndex, autoPlay = true)
            }
            sheet.show(supportFragmentManager, "ChapterListSheet")
        }

        // 設定 BottomSheet
        binding.btnSettings.setOnClickListener {
            val service = ttsService ?: return@setOnClickListener
            val sheet = SettingsBottomSheetDialogFragment(
                settings = service.settings,
                availableVoices = availableVoices
            ) { newSettings ->
                service.updateSettings(newSettings)
                updateSettingsBadges()
            }
            sheet.show(supportFragmentManager, "SettingsSheet")
        }

        // 切換沉浸全螢幕 (隱藏 / 顯示頂部 status bar 與底部 navigation bar)
        binding.btnToggleFullScreen.setOnClickListener {
            applyImmersiveSystemBars(hide = !isImmersiveFullScreen)
        }

        // 續讀橫條按鈕
        binding.btnResumePlay.setOnClickListener {
            val progress = pendingSavedProgress ?: return@setOnClickListener
            val service = ttsService ?: return@setOnClickListener
            service.loadChapter(
                index = progress.chapterIndex,
                autoPlay = true,
                targetSegmentIndex = progress.segmentIndex
            )
            binding.cardResumeBanner.visibility = View.GONE
        }

        binding.btnCloseResumeBanner.setOnClickListener {
            binding.cardResumeBanner.visibility = View.GONE
        }
    }

    private fun showJumpChapterDialog() {
        val service = ttsService ?: return
        if (service.chapters.isEmpty()) return
        val sheet = JumpChapterBottomSheetDialogFragment(
            chapters = service.chapters,
            currentChapterIndex = service.currentChapterIndex
        ) { targetIndex ->
            service.loadChapter(targetIndex, autoPlay = true)
        }
        sheet.show(supportFragmentManager, "JumpChapterSheet")
    }

    private fun checkResumeProgress() {
        val saved = progressManager.getSavedProgress()
        if (saved != null) {
            pendingSavedProgress = saved
            binding.cardResumeBanner.visibility = View.VISIBLE
            binding.tvResumeProgressText.text = "上次朗讀至：第 ${saved.chapterNumber} 章 ${saved.chapterTitle} · 段落 ${saved.uiPosition + 1}"
        }
    }

    private fun loadBuiltinNovels() {
        lifecycleScope.launch {
            val chapters = repository.loadBuiltinNovelChapters()
            if (chapters.isNotEmpty()) {
                val saved = progressManager.getSavedProgress()
                val targetChapterIndex = if (saved != null && saved.novelId == repository.currentNovelId) {
                    saved.chapterIndex.coerceIn(chapters.indices)
                } else 0

                val targetSegmentIndex = if (saved != null && saved.novelId == repository.currentNovelId) {
                    saved.segmentIndex
                } else 0

                ttsService?.setChapterList(
                    list = chapters,
                    initialIndex = targetChapterIndex,
                    initialSegmentIndex = targetSegmentIndex,
                    startPlay = false
                )
            }
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                cursor.getString(nameIndex)
            } else null
        }
    }

    private fun updateSettingsBadges() {
        val service = ttsService ?: return
        val settings = service.settings

        val modeText = when {
            settings.filterConfig.isBodyOnly -> getString(R.string.mode_body_only)
            settings.filterConfig.isFull -> getString(R.string.mode_full)
            settings.filterConfig.readChapterTitle && !settings.filterConfig.readWorkRecords -> getString(R.string.mode_title_and_body)
            else -> getString(R.string.mode_custom)
        }
        binding.chipFilterBadge.text = modeText

        val genderStr = when (settings.gender) {
            VoiceGender.MALE -> "男聲"
            VoiceGender.FEMALE -> "女聲"
            VoiceGender.DEFAULT -> "預設"
        }
        val autoNextStr = if (settings.autoNextChapter) "連播" else "單章"
        binding.tvVoiceInfo.text = String.format("%.2fx · %s · %s", settings.speechRate, genderStr, autoNextStr)
    }

    private fun updatePlaybackControls(state: TtsReadingService.PlaybackState) {
        val icon = if (state == TtsReadingService.PlaybackState.PLAYING) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        binding.fabPlayPause.setImageResource(icon)

        val service = ttsService
        if (service != null) {
            binding.btnPrevChapter.isEnabled = service.currentChapterIndex > 0
            binding.btnNextChapter.isEnabled = service.currentChapterIndex < service.chapters.size - 1
        }
    }

    // --- TtsReadingService.PlaybackListener 回呼 ---

    override fun onPlaybackStateChanged(state: TtsReadingService.PlaybackState) {
        runOnUiThread {
            updatePlaybackControls(state)
            when (state) {
                TtsReadingService.PlaybackState.PLAYING -> {
                    binding.cardResumeBanner.visibility = View.GONE
                }
                TtsReadingService.PlaybackState.PAUSED -> {
                    binding.tvSpeakingStatus.text = "朗讀已暫停，點擊播放繼續"
                }
                TtsReadingService.PlaybackState.IDLE -> {
                    binding.tvSpeakingStatus.text = "準備就緒，點擊播放開始朗讀"
                    contentAdapter.setActiveUiPosition(-1)
                }
            }
        }
    }

    override fun onChapterLoaded(chapter: Chapter, content: ChapterContent, segments: List<TtsSegment>, initialSegmentIndex: Int) {
        runOnUiThread {
            val service = ttsService
            val hasPrev = (service?.currentChapterIndex ?: 0) > 0
            val hasNext = (service?.currentChapterIndex ?: 0) < (service?.chapters?.size ?: 0) - 1

            binding.tvCurrentChapterInfo.text = "${chapter.volumeName} · 第 ${chapter.chapterNumber} 章 ${content.title}  (點擊直達)"
            binding.toolbar.subtitle = "${chapter.volumeName} (第 ${chapter.chapterNumber}/${service?.chapters?.size ?: 0} 章)"
            contentAdapter.setContent(content, hasPrev = hasPrev, hasNext = hasNext)

            if (initialSegmentIndex in segments.indices) {
                val targetSegment = segments[initialSegmentIndex]
                contentAdapter.setActiveUiPosition(targetSegment.uiPosition)
                val itemIdx = contentAdapter.getItemIndexByUiPosition(targetSegment.uiPosition)
                if (itemIdx != -1) {
                    binding.rvContent.scrollToPosition(itemIdx)
                }
            } else {
                binding.rvContent.scrollToPosition(0)
            }
            updateSettingsBadges()
        }
    }

    override fun onSegmentChanged(segmentIndex: Int, segment: TtsSegment?) {
        runOnUiThread {
            if (segment != null) {
                contentAdapter.setActiveUiPosition(segment.uiPosition)
                val preview = segment.displayText.replace("\n", " ").trim()
                binding.tvSpeakingStatus.text = "正在朗讀：$preview"

                val itemIdx = contentAdapter.getItemIndexByUiPosition(segment.uiPosition)
                if (itemIdx != -1) {
                    val lm = binding.rvContent.layoutManager as? LinearLayoutManager
                    val firstVisible = lm?.findFirstVisibleItemPosition() ?: 0
                    val lastVisible = lm?.findLastVisibleItemPosition() ?: 0
                    if (itemIdx !in (firstVisible + 1)..(lastVisible - 1)) {
                        binding.rvContent.smoothScrollToPosition(itemIdx)
                    }
                }
            } else {
                contentAdapter.setActiveUiPosition(-1)
            }
        }
    }

    override fun onTtsReady(availableVoices: List<Voice>) {
        runOnUiThread {
            this.availableVoices = availableVoices
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isVolumeUpLongPressed = false
                    keyHandler.removeCallbacks(volumeUpLongPressRunnable)
                    keyHandler.postDelayed(volumeUpLongPressRunnable, 500)
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                keyHandler.removeCallbacks(volumeUpLongPressRunnable)
                if (!isVolumeUpLongPressed) {
                    // 短按：正常提高音量
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                isVolumeUpLongPressed = false
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isVolumeDownLongPressed = false
                    keyHandler.removeCallbacks(volumeDownLongPressRunnable)
                    keyHandler.postDelayed(volumeDownLongPressRunnable, 500)
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                keyHandler.removeCallbacks(volumeDownLongPressRunnable)
                if (!isVolumeDownLongPressed) {
                    // 短按：正常降低音量
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                isVolumeDownLongPressed = false
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Vibrator::class.java)
                vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(60)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        keyHandler.removeCallbacks(volumeUpLongPressRunnable)
        keyHandler.removeCallbacks(volumeDownLongPressRunnable)
        if (isBound) {
            ttsService?.removeListener(this)
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
