package com.novel.reader.util

import android.content.Context
import com.novel.reader.model.Chapter
import com.novel.reader.parser.NovelChapterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class GitHubUrlInfo(
    val owner: String,
    val repo: String,
    val branch: String,
    val path: String,
    val isSingleFile: Boolean
) {
    val rawFileUrl: String
        get() = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"

    val archiveZipUrl: String
        get() = "https://github.com/$owner/$repo/archive/refs/heads/$branch.zip"

    val inferredParentFolderUrl: String
        get() {
            val parentPath = if (path.contains("/")) path.substringBeforeLast("/") else ""
            val novelRoot = if (parentPath.contains("chapters")) parentPath.substringBefore("chapters").trimEnd('/') else parentPath
            return "https://github.com/$owner/$repo/tree/$branch/$novelRoot"
        }
}

sealed class GitHubLoadResult {
    data class Success(val chapters: List<Chapter>, val title: String, val isSingleChapter: Boolean) : GitHubLoadResult()
    data class Error(val message: String) : GitHubLoadResult()
}

object GitHubHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val BLOB_REGEX = Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""")
    private val TREE_REGEX = Regex("""^https?://github\.com/([^/]+)/([^/]+)/tree/([^/]+)/?(.*)$""")
    private val RAW_REGEX = Regex("""^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")
    private val REPO_REGEX = Regex("""^https?://github\.com/([^/]+)/([^/]+)/?$""")

    /**
     * 解析使用者輸入的 GitHub 網址
     */
    fun parseUrl(url: String): GitHubUrlInfo? {
        val trimmed = url.trim()

        // 1. 單一檔案 blob
        BLOB_REGEX.find(trimmed)?.let { match ->
            return GitHubUrlInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3],
                path = match.groupValues[4],
                isSingleFile = true
            )
        }

        // 2. raw 網址
        RAW_REGEX.find(trimmed)?.let { match ->
            return GitHubUrlInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3],
                path = match.groupValues[4],
                isSingleFile = true
            )
        }

        // 3. tree 目錄
        TREE_REGEX.find(trimmed)?.let { match ->
            return GitHubUrlInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3],
                path = match.groupValues[4].trimEnd('/'),
                isSingleFile = false
            )
        }

        // 4. 整體 repo
        REPO_REGEX.find(trimmed)?.let { match ->
            return GitHubUrlInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2].removeSuffix(".git"),
                branch = "main",
                path = "",
                isSingleFile = false
            )
        }

        return null
    }

    /**
     * 下載單一 Markdown 章節
     */
    suspend fun downloadSingleChapter(
        context: Context,
        info: GitHubUrlInfo
    ): GitHubLoadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(info.rawFileUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext GitHubLoadResult.Error("下載失敗 (HTTP ${response.code}): ${response.message}")
            }

            val body = response.body?.string() ?: ""
            if (body.isBlank()) {
                return@withContext GitHubLoadResult.Error("取得的檔案內容為空")
            }

            val fileName = info.path.substringAfterLast("/")
            val targetDir = File(context.filesDir, "github_single")
            targetDir.mkdirs()
            val file = File(targetDir, fileName)
            file.writeText(body, Charsets.UTF_8)

            val parsed = NovelChapterParser.parse(body)
            val chapNumber = Regex("""\d+""").find(fileName)?.value?.toIntOrNull() ?: 1
            val chapter = Chapter(
                id = file.absolutePath,
                volumeIndex = 1,
                volumeName = "第一卷",
                chapterNumber = chapNumber,
                title = parsed.title,
                fileUriString = android.net.Uri.fromFile(file).toString()
            )

            GitHubLoadResult.Success(
                chapters = listOf(chapter),
                title = parsed.title,
                isSingleChapter = true
            )
        } catch (e: Exception) {
            e.printStackTrace()
            GitHubLoadResult.Error("下載發生錯誤: ${e.localizedMessage}")
        }
    }

    /**
     * 下載整本書或整個目錄 (透過 GitHub Branch Zip 封裝串流解壓)
     */
    suspend fun downloadTreeFolder(
        context: Context,
        info: GitHubUrlInfo,
        onProgress: (message: String) -> Unit = {}
    ): GitHubLoadResult = withContext(Dispatchers.IO) {
        try {
            onProgress("正在連線至 GitHub 下載專案資料...")
            val request = Request.Builder().url(info.archiveZipUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext GitHubLoadResult.Error("連線至 GitHub 失敗 (HTTP ${response.code})")
            }

            val body = response.body ?: return@withContext GitHubLoadResult.Error("回應為空")
            val targetDirName = "gh_${info.owner}_${info.repo}_" + (info.path.replace("/", "_").ifEmpty { "all" })
            val destDir = File(context.filesDir, targetDirName)
            destDir.deleteRecursively()
            destDir.mkdirs()

            onProgress("正在解壓縮並索引小說章節...")
            // GitHub archive zip 的第一層目錄通常為 "${repo}-${branch}/"
            val expectedPrefix = if (info.path.isNotEmpty()) "/${info.path}/" else "/"

            ZipInputStream(body.byteStream()).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    val name = entry.name
                    // 檢查路徑是否包含指定的 subpath
                    val matchesPath = if (info.path.isEmpty()) true else {
                        name.contains(expectedPrefix) || name.startsWith("${info.repo}-${info.branch}/${info.path}")
                    }

                    if (matchesPath && !entry.isDirectory && (name.endsWith(".md") || name.endsWith(".txt"))) {
                        // 排除規則檔
                        if (!name.contains("rules") && !name.contains("README")) {
                            val relativeName = if (info.path.isNotEmpty()) {
                                name.substringAfter(expectedPrefix, name.substringAfterLast("/"))
                            } else name

                            val outFile = File(destDir, relativeName)
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 掃描目錄
            val chapters = mutableListOf<Chapter>()
            fun scan(file: File, volIndex: Int, volName: String) {
                if (file.isDirectory) {
                    val name = file.name
                    val vIdx = if (name.contains("volume")) {
                        name.replace(Regex("""[^0-9]"""), "").toIntOrNull() ?: volIndex
                    } else volIndex
                    val vName = "第 ${vIdx} 卷"
                    file.listFiles()?.forEach { scan(it, vIdx, vName) }
                } else if (file.isFile && (file.name.endsWith(".md") || file.name.endsWith(".txt"))) {
                    val num = Regex("""\d+""").find(file.name)?.value?.toIntOrNull() ?: (chapters.size + 1)
                    val title = try {
                        file.bufferedReader(Charsets.UTF_8).use { r ->
                            var l: String?
                            var t: String? = null
                            while (r.readLine().also { l = it } != null) {
                                val tr = l!!.trim()
                                if (tr.startsWith("# ")) {
                                    t = NovelChapterParser.cleanMarkdownSymbols(tr.removePrefix("#").trim())
                                    break
                                }
                            }
                            t
                        }
                    } catch (e: Exception) {
                        null
                    } ?: "第 ${num} 章"

                    chapters.add(
                        Chapter(
                            id = file.absolutePath,
                            volumeIndex = volIndex,
                            volumeName = volName,
                            chapterNumber = num,
                            title = title,
                            fileUriString = android.net.Uri.fromFile(file).toString()
                        )
                    )
                }
            }

            scan(destDir, 1, "第一卷")
            chapters.sortBy { it.chapterNumber }

            if (chapters.isEmpty()) {
                return@withContext GitHubLoadResult.Error("在指定的 GitHub 路徑中未找到小說章節檔案 (.md)")
            }

            val novelTitle = if (info.path.isNotEmpty()) {
                info.path.substringAfterLast("/").replaceFirstChar { it.uppercase() }
            } else info.repo

            GitHubLoadResult.Success(
                chapters = chapters,
                title = novelTitle,
                isSingleChapter = false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            GitHubLoadResult.Error("下載專案發生異常: ${e.localizedMessage}")
        }
    }
}
