package com.novel.reader.parser

import com.novel.reader.model.*

object NovelChapterParser {

    private val TITLE_REGEX = Regex("""^#\s+(.+)$""", RegexOption.MULTILINE)
    private val WORK_RECORD_HEADER_REGEX = Regex("""^##\s+.*(工作紀錄|工作记录|章後紀錄|章后记录).*$""", RegexOption.MULTILINE)

    /**
     * 解析 Markdown 文章全文為結構化的 ChapterContent
     */
    fun parse(rawMarkdown: String): ChapterContent {
        val lines = rawMarkdown.lines()
        var title = ""
        val bodyParagraphs = mutableListOf<String>()
        val workRecords = mutableListOf<WorkRecordItem>()

        var inWorkRecordSection = false
        var currentCategory: WorkRecordCategory? = null
        var currentCategoryHeader = ""
        var currentCategoryLines = mutableListOf<String>()

        val currentParagraphBuilder = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()

            // 1. 識別章節標題 (# 第一章 ...)
            if (title.isEmpty() && trimmed.startsWith("# ")) {
                val match = TITLE_REGEX.find(trimmed)
                title = cleanMarkdownSymbols(match?.groupValues?.get(1)?.trim() ?: trimmed.removePrefix("#").trim())
                continue
            }

            // 2. 識別章後工作紀錄分隔線或大標題
            if (trimmed == "---" || trimmed == "***" || WORK_RECORD_HEADER_REGEX.matches(trimmed)) {
                if (currentParagraphBuilder.isNotBlank()) {
                    bodyParagraphs.add(currentParagraphBuilder.toString().trim())
                    currentParagraphBuilder.clear()
                }
                inWorkRecordSection = true
                continue
            }

            if (!inWorkRecordSection) {
                // 正文部分
                if (trimmed.isEmpty()) {
                    if (currentParagraphBuilder.isNotBlank()) {
                        bodyParagraphs.add(currentParagraphBuilder.toString().trim())
                        currentParagraphBuilder.clear()
                    }
                } else {
                    if (currentParagraphBuilder.isNotEmpty()) {
                        currentParagraphBuilder.append("\n")
                    }
                    currentParagraphBuilder.append(trimmed)
                }
            } else {
                // 章後工作紀錄部分
                if (trimmed.isEmpty()) continue

                // 檢查是否為新分類標題 (如: - **【史實骨架】：**)
                val cleanedLine = cleanMarkdownSymbols(trimmed.removePrefix("-").removePrefix("*").trim())
                val matchedCat = WorkRecordCategory.matchCategory(cleanedLine)
                val isCategoryHeader = matchedCat != null && (
                        trimmed.startsWith("-") || trimmed.startsWith("*") ||
                        trimmed.contains("【") || trimmed.contains("：")
                ) && (
                        cleanedLine.endsWith("：") || cleanedLine.endsWith(":") ||
                        cleanedLine.startsWith("【") || cleanedLine.startsWith("史實") ||
                        cleanedLine.startsWith("待核") || cleanedLine.startsWith("合理推演") ||
                        cleanedLine.startsWith("小說虛構") || cleanedLine.startsWith("連續性") ||
                        cleanedLine.startsWith("後續銜接")
                )

                if (isCategoryHeader && matchedCat != null) {
                    // 儲存前一個分類
                    if (currentCategory != null && (currentCategoryHeader.isNotBlank() || currentCategoryLines.isNotEmpty())) {
                        workRecords.add(
                            WorkRecordItem(
                                category = currentCategory,
                                headerText = currentCategoryHeader,
                                contentLines = ArrayList(currentCategoryLines)
                            )
                        )
                        currentCategoryLines.clear()
                    }

                    currentCategory = matchedCat
                    val colonIndex = cleanedLine.indexOfFirst { it == '：' || it == ':' }
                    if (colonIndex != -1) {
                        currentCategoryHeader = cleanedLine.substring(0, colonIndex).replace("【", "").replace("】", "").trim()
                        val remainder = cleanedLine.substring(colonIndex + 1).trim()
                        if (remainder.isNotEmpty()) {
                            currentCategoryLines.add(remainder)
                        }
                    } else {
                        currentCategoryHeader = cleanedLine.replace("【", "").replace("】", "").trim()
                    }
                } else {
                    // 分類底下的條目內容 (如: - 《元史...》)
                    val cleaned = cleanMarkdownSymbols(trimmed.removePrefix("-").removePrefix("*").trim())
                    if (cleaned.isNotEmpty()) {
                        if (currentCategory == null) {
                            currentCategory = WorkRecordCategory.OTHER
                            currentCategoryHeader = "工作紀錄"
                        }
                        currentCategoryLines.add(cleaned)
                    }
                }
            }
        }

        // 收尾正文最後一段
        if (currentParagraphBuilder.isNotBlank()) {
            bodyParagraphs.add(currentParagraphBuilder.toString().trim())
        }

        // 收尾最後一個工作紀錄分類
        if (currentCategory != null && (currentCategoryHeader.isNotBlank() || currentCategoryLines.isNotEmpty())) {
            workRecords.add(
                WorkRecordItem(
                    category = currentCategory,
                    headerText = currentCategoryHeader,
                    contentLines = ArrayList(currentCategoryLines)
                )
            )
        }

        return ChapterContent(
            title = title.ifEmpty { "章節" },
            bodyParagraphs = bodyParagraphs,
            workRecords = workRecords,
            rawMarkdown = rawMarkdown
        )
    }

    /**
     * 依照使用者的 ReadingFilterConfig 生成 TTS 朗讀單元序列
     * 支援「預設每個字都讀」、「設定要不要讀章名」、「只讀正文」、「單獨開啟/關閉工作紀錄項目」
     */
    fun buildTtsSegments(
        chapterContent: ChapterContent,
        filterConfig: ReadingFilterConfig
    ): List<TtsSegment> {
        val segments = mutableListOf<TtsSegment>()
        var uiPos = 0

        // 1. 章名
        if (filterConfig.readChapterTitle && chapterContent.title.isNotBlank()) {
            val cleanTitle = cleanMarkdownSymbols(chapterContent.title)
            segments.add(
                TtsSegment(
                    id = "title",
                    type = SegmentType.CHAPTER_TITLE,
                    displayText = chapterContent.title,
                    speechText = cleanSpeechText(cleanTitle),
                    uiPosition = uiPos
                )
            )
        }
        uiPos++ // 章名佔據 UI 位置 0

        // 2. 正文各段落
        chapterContent.bodyParagraphs.forEachIndexed { index, paragraph ->
            if (filterConfig.readBody && paragraph.isNotBlank()) {
                val cleanPara = cleanMarkdownSymbols(paragraph)
                segments.add(
                    TtsSegment(
                        id = "body_$index",
                        type = SegmentType.BODY_PARAGRAPH,
                        displayText = paragraph,
                        speechText = cleanSpeechText(cleanPara),
                        uiPosition = uiPos
                    )
                )
            }
            uiPos++
        }

        // 3. 章後工作紀錄
        if (filterConfig.readWorkRecords && chapterContent.workRecords.isNotEmpty()) {
            // 工作紀錄總標題
            segments.add(
                TtsSegment(
                    id = "work_records_header",
                    type = SegmentType.WORK_RECORD_HEADER,
                    category = WorkRecordCategory.OTHER,
                    displayText = "章後工作紀錄",
                    speechText = "章後工作紀錄。",
                    uiPosition = uiPos
                )
            )
            uiPos++

            // 工作紀錄各分類項目
            chapterContent.workRecords.forEachIndexed { secIdx, item ->
                if (filterConfig.isCategoryEnabled(item.category)) {
                    // 分類小標 (如: 史實骨架)
                    val headerSpeech = "${item.headerText}。"
                    segments.add(
                        TtsSegment(
                            id = "work_${secIdx}_header",
                            type = SegmentType.WORK_RECORD_HEADER,
                            category = item.category,
                            displayText = item.headerText,
                            speechText = cleanSpeechText(headerSpeech),
                            uiPosition = uiPos
                        )
                    )
                    uiPos++

                    // 條目內容
                    item.contentLines.forEachIndexed { lineIdx, line ->
                        if (line.isNotBlank()) {
                            segments.add(
                                TtsSegment(
                                    id = "work_${secIdx}_line_$lineIdx",
                                    type = SegmentType.WORK_RECORD_CONTENT,
                                    category = item.category,
                                    displayText = line,
                                    speechText = cleanSpeechText(line),
                                    uiPosition = uiPos
                                )
                            )
                            uiPos++
                        }
                    }
                } else {
                    // 如果該分類被過濾，UI 上的條目位置仍對齊
                    uiPos += 1 + item.contentLines.size
                }
            }
        }

        return segments
    }

    /**
     * 清理 Markdown 特殊符號 (如粗體 **、斜體 *、反引號 ` 等)
     */
    fun cleanMarkdownSymbols(text: String): String {
        return text
            .replace("**", "")
            .replace("*", "")
            .replace("`", "")
            .replace(Regex("""^#+\s*"""), "")
            .trim()
    }

    /**
     * 清理用語音發音的字元：
     * 移除特殊括號符號以使發音流暢，保留正常停頓逗點句號
     */
    fun cleanSpeechText(text: String): String {
        return text
            .replace("【", "")
            .replace("】", "：")
            .replace("──", "，")
            .replace("——", "，")
            .replace("……", "。")
            .replace("…", "。")
            .replace(Regex("""[#*`~>]"""), "")
            .trim()
    }
}
