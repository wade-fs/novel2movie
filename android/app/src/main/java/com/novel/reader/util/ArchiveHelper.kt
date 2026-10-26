package com.novel.reader.util

import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object ArchiveHelper {

    /**
     * 自動偵測副檔名與魔術字元解壓縮 ZIP、TAR、TAR.GZ (.tgz)
     */
    fun extractArchive(inputStream: InputStream, fileName: String, destDir: File): Boolean {
        destDir.mkdirs()
        val bufferedIn = BufferedInputStream(inputStream)
        bufferedIn.mark(4)
        val header = ByteArray(2)
        val readBytes = bufferedIn.read(header, 0, 2)
        bufferedIn.reset()

        val isGzip = (readBytes == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte())
        val lowerName = fileName.lowercase()

        return when {
            lowerName.endsWith(".zip") -> {
                extractZip(bufferedIn, destDir)
            }
            isGzip || lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") -> {
                extractTar(GZIPInputStream(bufferedIn), destDir)
            }
            lowerName.endsWith(".tar") -> {
                extractTar(bufferedIn, destDir)
            }
            else -> {
                // 預設嘗試以 ZIP 解壓
                try {
                    extractZip(bufferedIn, destDir)
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    /**
     * 解壓 ZIP 串流
     */
    fun extractZip(inputStream: InputStream, destDir: File): Boolean {
        return try {
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    val entryName = entry.name
                    if (!entry.isDirectory && (entryName.endsWith(".md") || entryName.endsWith(".txt"))) {
                        val outFile = File(destDir, entryName)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 解壓 TAR 串流 (支援標準 UStar/POSIX Tar 格式)
     */
    fun extractTar(inputStream: InputStream, destDir: File): Boolean {
        return try {
            val buffer = ByteArray(512)
            var bytesRead: Int
            while (true) {
                // 讀取 512 bytes 的 Header block
                var totalRead = 0
                while (totalRead < 512) {
                    bytesRead = inputStream.read(buffer, totalRead, 512 - totalRead)
                    if (bytesRead <= 0) break
                    totalRead += bytesRead
                }
                if (totalRead < 512) break

                // 檢查是否為 Tar 結尾的空 block (連續全為 0)
                if (buffer.all { it == 0.toByte() }) {
                    // 通常連續兩個 512 全 0 代表結束
                    break
                }

                // 檔名：bytes 0..99
                val rawName = String(buffer, 0, 100, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                if (rawName.isEmpty()) continue

                // 檔案大小：bytes 124..135 (Octal ASCII)
                val rawSize = String(buffer, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                val fileSize = rawSize.toLongOrNull(8) ?: 0L

                // 檔案類型：byte 156 ('0' 或 0 為一般檔案，'5' 為目錄)
                val typeFlag = buffer[156].toInt().toChar()
                val isDir = (typeFlag == '5' || rawName.endsWith("/"))

                if (!isDir && (rawName.endsWith(".md") || rawName.endsWith(".txt"))) {
                    val outFile = File(destDir, rawName)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var remaining = fileSize
                        val copyBuf = ByteArray(4096)
                        while (remaining > 0) {
                            val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                            val r = inputStream.read(copyBuf, 0, toRead)
                            if (r <= 0) break
                            fos.write(copyBuf, 0, r)
                            remaining -= r
                        }
                    }
                } else {
                    // 跳過該檔案內容
                    skipBytesFully(inputStream, fileSize)
                }

                // Tar 規範：每個檔案結尾對齊 512 bytes 邊界
                val padding = (512 - (fileSize % 512)) % 512
                if (padding > 0) {
                    skipBytesFully(inputStream, padding)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun skipBytesFully(inputStream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuffer = ByteArray(4096)
        while (remaining > 0) {
            val toRead = minOf(skipBuffer.size.toLong(), remaining).toInt()
            val r = inputStream.read(skipBuffer, 0, toRead)
            if (r <= 0) break
            remaining -= r
        }
    }
}
