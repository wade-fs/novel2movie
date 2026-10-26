package com.novel.reader.util

import org.junit.Assert.*
import org.junit.Test

class GitHubHelperTest {

    @Test
    fun testParseTreeUrl() {
        val url = "https://github.com/wade-fs/novel2movie/tree/main/yuan"
        val info = GitHubHelper.parseUrl(url)
        assertNotNull(info)
        assertEquals("wade-fs", info!!.owner)
        assertEquals("novel2movie", info.repo)
        assertEquals("main", info.branch)
        assertEquals("yuan", info.path)
        assertFalse(info.isSingleFile)
        assertEquals("https://github.com/wade-fs/novel2movie/archive/refs/heads/main.zip", info.archiveZipUrl)
    }

    @Test
    fun testParseBlobUrl() {
        val url = "https://github.com/wade-fs/novel2movie/blob/main/yuan/chapters/volume1/chap001.md"
        val info = GitHubHelper.parseUrl(url)
        assertNotNull(info)
        assertEquals("wade-fs", info!!.owner)
        assertEquals("novel2movie", info.repo)
        assertEquals("main", info.branch)
        assertEquals("yuan/chapters/volume1/chap001.md", info.path)
        assertTrue(info.isSingleFile)
        assertEquals(
            "https://raw.githubusercontent.com/wade-fs/novel2movie/main/yuan/chapters/volume1/chap001.md",
            info.rawFileUrl
        )
    }

    @Test
    fun testParseRawUrl() {
        val url = "https://raw.githubusercontent.com/wade-fs/novel2movie/main/yuan/chapters/volume1/chap001.md"
        val info = GitHubHelper.parseUrl(url)
        assertNotNull(info)
        assertEquals("wade-fs", info!!.owner)
        assertEquals("novel2movie", info.repo)
        assertEquals("main", info.branch)
        assertEquals("yuan/chapters/volume1/chap001.md", info.path)
        assertTrue(info.isSingleFile)
    }
}
