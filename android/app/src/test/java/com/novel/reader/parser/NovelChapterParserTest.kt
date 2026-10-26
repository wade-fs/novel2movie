package com.novel.reader.parser

import com.novel.reader.model.ReadingFilterConfig
import com.novel.reader.model.WorkRecordCategory
import org.junit.Assert.*
import org.junit.Test

class NovelChapterParserTest {

    private val sampleMarkdown = """
# 第一章　上都煙塵

草原上的七月，日頭落得極慢。

近黃昏時，金蓮川的草浪泛起一層焦乾的鐵銹色。

---

## 章後工作紀錄

- **【史實骨架】：** 
  - 《元史·泰定帝本紀》：泰定五年（致和元年，1328）秋七月庚午，帝崩於上都行宮。
  - 《元史·燕帖木兒傳》：泰定帝崩，中書左丞相倒剌沙等封鎖消息。
- **【合理推演】：** 
  - 泰定帝七月暴崩於上都行宮後，中書左丞相倒剌沙為控制局勢切斷通信。
- **【待核事項】：** 
  - 上都開平府至野狐嶺間各驛站於致和元年的精確驛名。
    """.trimIndent()

    @Test
    fun testParseTitleAndBody() {
        val chapter = NovelChapterParser.parse(sampleMarkdown)
        assertEquals("第一章　上都煙塵", chapter.title)
        assertEquals(2, chapter.bodyParagraphs.size)
        assertEquals("草原上的七月，日頭落得極慢。", chapter.bodyParagraphs[0])
        assertEquals("近黃昏時，金蓮川的草浪泛起一層焦乾的鐵銹色。", chapter.bodyParagraphs[1])
    }

    @Test
    fun testParseWorkRecords() {
        val chapter = NovelChapterParser.parse(sampleMarkdown)
        assertEquals(3, chapter.workRecords.size)

        val facts = chapter.workRecords.find { it.category == WorkRecordCategory.HISTORICAL_FACTS }
        assertNotNull(facts)
        assertEquals(2, facts!!.contentLines.size)
        assertTrue(facts.contentLines[0].contains("泰定帝本紀"))

        val inference = chapter.workRecords.find { it.category == WorkRecordCategory.INFERENCE }
        assertNotNull(inference)

        val verification = chapter.workRecords.find { it.category == WorkRecordCategory.PENDING_VERIFICATION }
        assertNotNull(verification)
    }

    @Test
    fun testFullModeSegments() {
        val chapter = NovelChapterParser.parse(sampleMarkdown)
        val config = ReadingFilterConfig() // default full mode
        val segments = NovelChapterParser.buildTtsSegments(chapter, config)

        // Title + 2 body + work record header + 3 category headers + 4 content lines = 11 segments
        assertTrue(segments.size >= 10)
        assertEquals("第一章　上都煙塵", segments[0].displayText)
    }

    @Test
    fun testBodyOnlyModeSegments() {
        val chapter = NovelChapterParser.parse(sampleMarkdown)
        val config = ReadingFilterConfig().apply { setBodyOnlyMode() }
        val segments = NovelChapterParser.buildTtsSegments(chapter, config)

        // Title should be excluded, work records excluded, only 2 body paragraphs
        assertEquals(2, segments.size)
        assertEquals("草原上的七月，日頭落得極慢。", segments[0].displayText)
    }

    @Test
    fun testFilterOutHistoricalFacts() {
        val chapter = NovelChapterParser.parse(sampleMarkdown)
        val config = ReadingFilterConfig().apply {
            readHistoricalFacts = false
        }
        val segments = NovelChapterParser.buildTtsSegments(chapter, config)

        // Historical facts category shouldn't be in segments
        val hasFacts = segments.any { it.category == WorkRecordCategory.HISTORICAL_FACTS }
        assertFalse(hasFacts)

        // Inference should still be there
        val hasInference = segments.any { it.category == WorkRecordCategory.INFERENCE }
        assertTrue(hasInference)
    }
}
