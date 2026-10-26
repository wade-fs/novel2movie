package com.novel.reader.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.novel.reader.model.Chapter
import com.novel.reader.model.ChapterContent
import com.novel.reader.parser.NovelChapterParser
import com.novel.reader.util.ArchiveHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

class NovelRepository(private val context: Context) {

    private val cachedChapters = mutableListOf<Chapter>()
    var currentNovelId: String = "builtin_yuan"
        private set
    var currentNovelTitle: String = "元末明初歷史群像 (540章全本)"
        private set

    /**
     * 載入內建的小說章節 (優先從 assets/novels/yuan.zip 解壓載入，節省空間與檔案描述符)
     */
    suspend fun loadBuiltinNovelChapters(): List<Chapter> = withContext(Dispatchers.IO) {
        if (cachedChapters.isNotEmpty() && currentNovelId == "builtin_yuan") {
            return@withContext cachedChapters
        }

        val targetDir = File(context.filesDir, "builtin_novel")
        val markerFile = File(targetDir, ".extracted_ok")

        // 若尚未解壓過，則自 assets/novels/yuan.zip 解壓
        if (!markerFile.exists()) {
            targetDir.deleteRecursively()
            targetDir.mkdirs()
            try {
                context.assets.open("novels/yuan.zip").use { input ->
                    ArchiveHelper.extractZip(input, targetDir)
                }
                markerFile.createNewFile()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val chapters = scanDirectoryForChapters(targetDir)
        cachedChapters.clear()
        cachedChapters.addAll(chapters)
        currentNovelId = "builtin_yuan"
        currentNovelTitle = "元末明初歷史群像"
        chapters
    }

    /**
     * 從外部選取的壓縮檔 (.zip, .tar, .tar.gz, .tgz) 匯入並載入章節
     */
    suspend fun loadArchiveChapters(archiveUri: Uri, fileName: String): List<Chapter> = withContext(Dispatchers.IO) {
        val safeName = fileName.replace(Regex("""[^a-zA-Z0-9_\u4e00-\u9fa5]"""), "_")
        val extractDir = File(context.filesDir, "imported_$safeName")
        extractDir.deleteRecursively()
        extractDir.mkdirs()

        val success = context.contentResolver.openInputStream(archiveUri)?.use { input ->
            ArchiveHelper.extractArchive(input, fileName, extractDir)
        } ?: false

        if (!success) return@withContext emptyList()

        val chapters = scanDirectoryForChapters(extractDir)
        if (chapters.isNotEmpty()) {
            cachedChapters.clear()
            cachedChapters.addAll(chapters)
            currentNovelId = "archive_$safeName"
            currentNovelTitle = fileName.substringBeforeLast(".")
        }
        chapters
    }

    /**
     * 從外部選取的資料夾 (SAF Uri) 載入小說章節
     */
    suspend fun loadCustomDirectoryChapters(treeUri: Uri): List<Chapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<Chapter>()
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()

        fun scanFolder(docFile: DocumentFile, volIndex: Int, volName: String) {
            for (file in docFile.listFiles()) {
                if (file.isDirectory) {
                    val name = file.name ?: ""
                    val vIdx = if (name.contains("volume")) {
                        name.replace(Regex("""[^0-9]"""), "").toIntOrNull() ?: volIndex
                    } else volIndex
                    scanFolder(file, vIdx, "第 ${vIdx} 卷")
                } else if (file.isFile && (file.name?.endsWith(".md") == true || file.name?.endsWith(".txt") == true)) {
                    val name = file.name ?: ""
                    val num = Regex("""\d+""").find(name)?.value?.toIntOrNull() ?: (chapters.size + 1)
                    val title = readTitleFromUri(file.uri) ?: name.removeSuffix(".md").removeSuffix(".txt")
                    chapters.add(
                        Chapter(
                            id = file.uri.toString(),
                            volumeIndex = volIndex,
                            volumeName = volName,
                            chapterNumber = num,
                            title = title,
                            fileUriString = file.uri.toString()
                        )
                    )
                }
            }
        }

        scanFolder(rootDoc, 1, "目錄")
        chapters.sortBy { it.chapterNumber }
        if (chapters.isNotEmpty()) {
            cachedChapters.clear()
            cachedChapters.addAll(chapters)
            currentNovelId = "folder_${treeUri.hashCode()}"
            currentNovelTitle = rootDoc.name ?: "外部小說目錄"
        }
        chapters
    }

    /**
     * 掃描本機實體目錄下的所有章節 markdown 檔案
     */
    private fun scanDirectoryForChapters(baseDir: File): List<Chapter> {
        val chapters = mutableListOf<Chapter>()

        fun scan(file: File, volIndex: Int, volName: String) {
            if (file.isDirectory) {
                val name = file.name
                val vIdx = if (name.contains("volume")) {
                    name.replace(Regex("""[^0-9]"""), "").toIntOrNull() ?: volIndex
                } else volIndex
                val vName = when (vIdx) {
                    1 -> "第一卷"
                    2 -> "第二卷"
                    3 -> "第三卷"
                    4 -> "第四卷"
                    5 -> "第五卷"
                    6 -> "第六卷"
                    else -> "第 $vIdx 卷"
                }
                file.listFiles()?.forEach { scan(it, vIdx, vName) }
            } else if (file.isFile && (file.name.endsWith(".md") || file.name.endsWith(".txt"))) {
                // 排除純規則說明文件 (如: chapter_writer_rules.md, outline.md)
                if (file.name.contains("rules") || file.name.contains("outline") || file.name.contains("README")) {
                    return
                }
                val num = Regex("""\d+""").find(file.name)?.value?.toIntOrNull() ?: 0
                val title = readTitleFromFile(file) ?: "第 ${num} 章"
                chapters.add(
                    Chapter(
                        id = file.absolutePath,
                        volumeIndex = volIndex,
                        volumeName = volName,
                        chapterNumber = num,
                        title = title,
                        fileUriString = Uri.fromFile(file).toString()
                    )
                )
            }
        }

        scan(baseDir, 1, "第一卷")
        chapters.sortBy { it.chapterNumber }
        return chapters
    }

    /**
     * 載入特定章節的完整內容
     */
    suspend fun loadChapterContent(chapter: Chapter): ChapterContent = withContext(Dispatchers.IO) {
        val markdownText = when {
            chapter.fileUriString != null -> {
                val uri = Uri.parse(chapter.fileUriString)
                if (uri.scheme == "file") {
                    File(uri.path!!).readText(Charsets.UTF_8)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    } ?: ""
                }
            }
            chapter.assetPath != null -> {
                context.assets.open(chapter.assetPath).use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }
            }
            else -> ""
        }

        NovelChapterParser.parse(markdownText)
    }

    private fun readTitleFromFile(file: File): String? {
        return try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.startsWith("# ")) {
                        return@use NovelChapterParser.cleanMarkdownSymbols(trimmed.removePrefix("#").trim())
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readTitleFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.startsWith("# ")) {
                            return@use NovelChapterParser.cleanMarkdownSymbols(trimmed.removePrefix("#").trim())
                        }
                    }
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
