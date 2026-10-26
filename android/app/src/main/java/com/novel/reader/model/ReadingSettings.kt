package com.novel.reader.model

enum class VoiceGender(val displayName: String) {
    DEFAULT("系統預設"),
    MALE("男聲"),
    FEMALE("女聲")
}

data class ReadingSettings(
    var speechRate: Float = 1.0f,            // 0.5f ~ 2.5f
    var pitch: Float = 1.0f,                 // 0.6f ~ 1.6f
    var gender: VoiceGender = VoiceGender.DEFAULT,
    var selectedVoiceName: String? = null,
    var autoNextChapter: Boolean = true,     // 必須自動換下一章
    var filterConfig: ReadingFilterConfig = ReadingFilterConfig()
)
