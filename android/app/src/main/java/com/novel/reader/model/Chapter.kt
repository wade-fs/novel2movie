package com.novel.reader.model

data class Chapter(
    val id: String,                 // 例: "vol1_chap001"
    val volumeIndex: Int,           // 1..6
    val volumeName: String,         // 例: "第一卷"
    val chapterNumber: Int,         // 1..540
    val title: String,              // 例: "第一章　上都煙塵"
    val assetPath: String? = null,  // assets 內的相對路徑
    val fileUriString: String? = null // 外部檔案的 URI
)
