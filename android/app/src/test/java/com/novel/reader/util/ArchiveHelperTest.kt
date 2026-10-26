package com.novel.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveHelperTest {

    @Test
    fun testExtractZip() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("chapters/volume1/chap001.md"))
            zos.write("# 第一章\n正文測試".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val tempDir = Files.createTempDirectory("zip_test").toFile()
        try {
            val success = ArchiveHelper.extractArchive(
                ByteArrayInputStream(baos.toByteArray()),
                "novel.zip",
                tempDir
            )
            assertTrue(success)
            val extracted = File(tempDir, "chapters/volume1/chap001.md")
            assertTrue(extracted.exists())
            assertEquals("# 第一章\n正文測試", extracted.readText(Charsets.UTF_8))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testExtractTarGz() {
        // 構建一個合法的 mini TAR
        val tarBaos = ByteArrayOutputStream()
        val filename = "chapters/volume1/chap002.md"
        val content = "# 第二章\n這是Tar測試".toByteArray(Charsets.UTF_8)

        val header = ByteArray(512)
        // name: 0..99
        System.arraycopy(filename.toByteArray(Charsets.UTF_8), 0, header, 0, filename.length)
        // size: 124..135 (octal)
        val sizeStr = String.format("%011o ", content.size)
        System.arraycopy(sizeStr.toByteArray(Charsets.US_ASCII), 0, header, 124, 12)
        // typeflag: 156
        header[156] = '0'.toByte()

        tarBaos.write(header)
        tarBaos.write(content)
        val padding = (512 - (content.size % 512)) % 512
        if (padding > 0) {
            tarBaos.write(ByteArray(padding))
        }
        // TAR 結尾 1024 bytes 0
        tarBaos.write(ByteArray(1024))

        // GZIP 壓縮
        val gzBaos = ByteArrayOutputStream()
        GZIPOutputStream(gzBaos).use { gzos ->
            gzos.write(tarBaos.toByteArray())
        }

        val tempDir = Files.createTempDirectory("tar_test").toFile()
        try {
            val success = ArchiveHelper.extractArchive(
                ByteArrayInputStream(gzBaos.toByteArray()),
                "novel.tar.gz",
                tempDir
            )
            assertTrue(success)
            val extracted = File(tempDir, filename)
            assertTrue(extracted.exists())
            assertEquals("# 第二章\n這是Tar測試", extracted.readText(Charsets.UTF_8))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
