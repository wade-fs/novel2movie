package com.novel.reader.model

/**
 * 章後工作紀錄的分類列舉 (對應 chapter_writer_rules.md 規範)
 */
enum class WorkRecordCategory(val displayName: String, val keyword: String) {
    HISTORICAL_FACTS("史實依據 / 史實骨架", "史實"),
    PENDING_VERIFICATION("待核事項", "待核"),
    INFERENCE("合理推演", "合理推演"),
    FICTION("小說虛構", "小說虛構"),
    CONTINUITY("連續性變更", "連續性"),
    TRANSITION("後續銜接", "後續銜接"),
    OTHER("其他工作記錄", "其他");

    companion object {
        fun matchCategory(line: String): WorkRecordCategory? {
            val clean = line.replace("【", "").replace("】", "").replace("*", "").trim()
            return when {
                clean.contains("史實骨架") || clean.contains("史實依據") || clean.contains("史實") -> HISTORICAL_FACTS
                clean.contains("待核事項") || clean.contains("待核") || clean.contains("待考") -> PENDING_VERIFICATION
                clean.contains("合理推演") -> INFERENCE
                clean.contains("小說虛構") -> FICTION
                clean.contains("連續性變更") || clean.contains("連續性") -> CONTINUITY
                clean.contains("後續銜接") -> TRANSITION
                clean.contains("章後工作紀錄") || clean.contains("工作紀錄") || clean.contains("章後紀錄") -> OTHER
                else -> null
            }
        }
    }
}
