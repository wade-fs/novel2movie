package com.novel.reader.model

/**
 * 朗讀過濾設定：
 * 預設每個字都讀 (全文朗讀)；
 * 可設定要不要讀章名、或章後工作記錄的史實依據、待核事項、合理推演、小說虛構等，或是設定只讀正文。
 */
data class ReadingFilterConfig(
    var readChapterTitle: Boolean = true,
    var readBody: Boolean = true,
    var readWorkRecords: Boolean = true,
    var readHistoricalFacts: Boolean = true,
    var readPendingVerification: Boolean = true,
    var readInference: Boolean = true,
    var readFiction: Boolean = true,
    var readContinuity: Boolean = true,
    var readTransition: Boolean = true,
    var readOtherWorkRecords: Boolean = true
) {
    /**
     * 檢查特定工作記錄類別是否啟用朗讀
     */
    fun isCategoryEnabled(category: WorkRecordCategory): Boolean {
        if (!readWorkRecords) return false
        return when (category) {
            WorkRecordCategory.HISTORICAL_FACTS -> readHistoricalFacts
            WorkRecordCategory.PENDING_VERIFICATION -> readPendingVerification
            WorkRecordCategory.INFERENCE -> readInference
            WorkRecordCategory.FICTION -> readFiction
            WorkRecordCategory.CONTINUITY -> readContinuity
            WorkRecordCategory.TRANSITION -> readTransition
            WorkRecordCategory.OTHER -> readOtherWorkRecords
        }
    }

    fun setCategoryEnabled(category: WorkRecordCategory, enabled: Boolean) {
        when (category) {
            WorkRecordCategory.HISTORICAL_FACTS -> readHistoricalFacts = enabled
            WorkRecordCategory.PENDING_VERIFICATION -> readPendingVerification = enabled
            WorkRecordCategory.INFERENCE -> readInference = enabled
            WorkRecordCategory.FICTION -> readFiction = enabled
            WorkRecordCategory.CONTINUITY -> readContinuity = enabled
            WorkRecordCategory.TRANSITION -> readTransition = enabled
            WorkRecordCategory.OTHER -> readOtherWorkRecords = enabled
        }
    }

    /**
     * 預設模式：全文朗讀 (所有字都讀)
     */
    fun setFullMode() {
        readChapterTitle = true
        readBody = true
        readWorkRecords = true
        readHistoricalFacts = true
        readPendingVerification = true
        readInference = true
        readFiction = true
        readContinuity = true
        readTransition = true
        readOtherWorkRecords = true
    }

    /**
     * 模式：只讀正文 (不讀章名、不讀章後工作記錄)
     */
    fun setBodyOnlyMode() {
        readChapterTitle = false
        readBody = true
        readWorkRecords = false
    }

    /**
     * 模式：讀章名 + 正文 (不讀章後工作記錄)
     */
    fun setTitleAndBodyMode() {
        readChapterTitle = true
        readBody = true
        readWorkRecords = false
    }

    /**
     * 判斷當前是否為「只讀正文」
     */
    val isBodyOnly: Boolean
        get() = !readChapterTitle && readBody && !readWorkRecords

    /**
     * 判斷當前是否為「全文朗讀 (預設)」
     */
    val isFull: Boolean
        get() = readChapterTitle && readBody && readWorkRecords &&
                readHistoricalFacts && readPendingVerification && readInference &&
                readFiction && readContinuity && readTransition && readOtherWorkRecords
}
