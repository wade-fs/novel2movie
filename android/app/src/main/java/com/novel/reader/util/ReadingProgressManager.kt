package com.novel.reader.util

import android.content.Context
import android.content.SharedPreferences
import com.novel.reader.model.ReadingFilterConfig
import com.novel.reader.model.ReadingSettings
import com.novel.reader.model.VoiceGender

data class SavedReadingProgress(
    val novelId: String,
    val novelTitle: String,
    val chapterIndex: Int,
    val chapterNumber: Int,
    val chapterTitle: String,
    val segmentIndex: Int,
    val uiPosition: Int,
    val timestamp: Long
)

class ReadingProgressManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("novel_reading_progress", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HAS_PROGRESS = "has_saved_progress"
        private const val KEY_NOVEL_ID = "novel_id"
        private const val KEY_NOVEL_TITLE = "novel_title"
        private const val KEY_CHAPTER_INDEX = "chapter_index"
        private const val KEY_CHAPTER_NUMBER = "chapter_number"
        private const val KEY_CHAPTER_TITLE = "chapter_title"
        private const val KEY_SEGMENT_INDEX = "segment_index"
        private const val KEY_UI_POSITION = "ui_position"
        private const val KEY_TIMESTAMP = "timestamp"

        // Settings keys
        private const val KEY_SPEED = "setting_speed"
        private const val KEY_PITCH = "setting_pitch"
        private const val KEY_GENDER = "setting_gender"
        private const val KEY_VOICE_NAME = "setting_voice_name"
        private const val KEY_AUTO_NEXT = "setting_auto_next"

        // Filter keys
        private const val KEY_FILTER_TITLE = "filter_read_title"
        private const val KEY_FILTER_BODY = "filter_read_body"
        private const val KEY_FILTER_WORK_RECORD = "filter_read_work_record"
        private const val KEY_FILTER_HISTORY = "filter_read_history"
        private const val KEY_FILTER_VERIFY = "filter_read_verify"
        private const val KEY_FILTER_INFERENCE = "filter_read_inference"
        private const val KEY_FILTER_FICTION = "filter_read_fiction"
        private const val KEY_FILTER_CONTINUITY = "filter_read_continuity"
        private const val KEY_FILTER_TRANSITION = "filter_read_transition"
        private const val KEY_FILTER_OTHER = "filter_read_other"
    }

    /**
     * 儲存目前的朗讀進度 (章節、段落、時間戳記)
     */
    fun saveProgress(
        novelId: String,
        novelTitle: String,
        chapterIndex: Int,
        chapterNumber: Int,
        chapterTitle: String,
        segmentIndex: Int,
        uiPosition: Int
    ) {
        prefs.edit()
            .putBoolean(KEY_HAS_PROGRESS, true)
            .putString(KEY_NOVEL_ID, novelId)
            .putString(KEY_NOVEL_TITLE, novelTitle)
            .putInt(KEY_CHAPTER_INDEX, chapterIndex)
            .putInt(KEY_CHAPTER_NUMBER, chapterNumber)
            .putString(KEY_CHAPTER_TITLE, chapterTitle)
            .putInt(KEY_SEGMENT_INDEX, segmentIndex)
            .putInt(KEY_UI_POSITION, uiPosition)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * 取得已儲存的朗讀進度
     */
    fun getSavedProgress(): SavedReadingProgress? {
        if (!prefs.getBoolean(KEY_HAS_PROGRESS, false)) return null
        return SavedReadingProgress(
            novelId = prefs.getString(KEY_NOVEL_ID, "") ?: "",
            novelTitle = prefs.getString(KEY_NOVEL_TITLE, "") ?: "",
            chapterIndex = prefs.getInt(KEY_CHAPTER_INDEX, 0),
            chapterNumber = prefs.getInt(KEY_CHAPTER_NUMBER, 1),
            chapterTitle = prefs.getString(KEY_CHAPTER_TITLE, "") ?: "",
            segmentIndex = prefs.getInt(KEY_SEGMENT_INDEX, 0),
            uiPosition = prefs.getInt(KEY_UI_POSITION, 0),
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        )
    }

    /**
     * 儲存語音與章節過濾偏好設定
     */
    fun saveSettings(settings: ReadingSettings) {
        val f = settings.filterConfig
        prefs.edit()
            .putFloat(KEY_SPEED, settings.speechRate)
            .putFloat(KEY_PITCH, settings.pitch)
            .putString(KEY_GENDER, settings.gender.name)
            .putString(KEY_VOICE_NAME, settings.selectedVoiceName)
            .putBoolean(KEY_AUTO_NEXT, settings.autoNextChapter)
            .putBoolean(KEY_FILTER_TITLE, f.readChapterTitle)
            .putBoolean(KEY_FILTER_BODY, f.readBody)
            .putBoolean(KEY_FILTER_WORK_RECORD, f.readWorkRecords)
            .putBoolean(KEY_FILTER_HISTORY, f.readHistoricalFacts)
            .putBoolean(KEY_FILTER_VERIFY, f.readPendingVerification)
            .putBoolean(KEY_FILTER_INFERENCE, f.readInference)
            .putBoolean(KEY_FILTER_FICTION, f.readFiction)
            .putBoolean(KEY_FILTER_CONTINUITY, f.readContinuity)
            .putBoolean(KEY_FILTER_TRANSITION, f.readTransition)
            .putBoolean(KEY_FILTER_OTHER, f.readOtherWorkRecords)
            .apply()
    }

    /**
     * 載入語音與章節過濾偏好設定
     */
    fun loadSettings(): ReadingSettings {
        val genderStr = prefs.getString(KEY_GENDER, VoiceGender.DEFAULT.name)
        val gender = try {
            VoiceGender.valueOf(genderStr ?: VoiceGender.DEFAULT.name)
        } catch (e: Exception) {
            VoiceGender.DEFAULT
        }

        val filter = ReadingFilterConfig(
            readChapterTitle = prefs.getBoolean(KEY_FILTER_TITLE, true),
            readBody = prefs.getBoolean(KEY_FILTER_BODY, true),
            readWorkRecords = prefs.getBoolean(KEY_FILTER_WORK_RECORD, true),
            readHistoricalFacts = prefs.getBoolean(KEY_FILTER_HISTORY, true),
            readPendingVerification = prefs.getBoolean(KEY_FILTER_VERIFY, true),
            readInference = prefs.getBoolean(KEY_FILTER_INFERENCE, true),
            readFiction = prefs.getBoolean(KEY_FILTER_FICTION, true),
            readContinuity = prefs.getBoolean(KEY_FILTER_CONTINUITY, true),
            readTransition = prefs.getBoolean(KEY_FILTER_TRANSITION, true),
            readOtherWorkRecords = prefs.getBoolean(KEY_FILTER_OTHER, true)
        )

        return ReadingSettings(
            speechRate = prefs.getFloat(KEY_SPEED, 1.0f),
            pitch = prefs.getFloat(KEY_PITCH, 1.0f),
            gender = gender,
            selectedVoiceName = prefs.getString(KEY_VOICE_NAME, null),
            autoNextChapter = prefs.getBoolean(KEY_AUTO_NEXT, true),
            filterConfig = filter
        )
    }
}
