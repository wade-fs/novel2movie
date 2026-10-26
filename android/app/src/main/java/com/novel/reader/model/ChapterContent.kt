package com.novel.reader.model

/**
 * 單一工作記錄項目 (例如：史實骨架下的內容或條目)
 */
data class WorkRecordItem(
    val category: WorkRecordCategory,
    val headerText: String,             // 例: "【史實骨架】"
    val contentLines: List<String>       // 該項目下的詳細說明文字
)

/**
 * 解析後章節的完整內容模型
 */
data class ChapterContent(
    val title: String,                  // 章節標題
    val bodyParagraphs: List<String>,   // 小說正文段落列表
    val workRecords: List<WorkRecordItem>, // 章後工作記錄清單
    val rawMarkdown: String
)
