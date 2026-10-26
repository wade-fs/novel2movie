package com.novel.reader.model

enum class SegmentType {
    CHAPTER_TITLE,
    BODY_PARAGRAPH,
    WORK_RECORD_HEADER,
    WORK_RECORD_CONTENT
}

/**
 * 送入 TTS 朗讀佇列的最小單元
 */
data class TtsSegment(
    val id: String,                     // 唯一識別碼
    val type: SegmentType,
    val category: WorkRecordCategory? = null,
    val displayText: String,            // 畫面顯示的文字
    val speechText: String,             // 經文字清洗後適合語音合成朗讀的文字
    val uiPosition: Int                 // 對應 RecyclerView adapter 的位置
)
