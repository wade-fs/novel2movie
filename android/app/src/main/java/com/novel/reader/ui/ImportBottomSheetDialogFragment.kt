package com.novel.reader.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novel.reader.databinding.DialogImportGithubBinding
import com.novel.reader.model.Chapter
import com.novel.reader.util.GitHubHelper
import com.novel.reader.util.GitHubLoadResult
import kotlinx.coroutines.launch

class ImportBottomSheetDialogFragment(
    private val onImportSuccess: (chapters: List<Chapter>, novelTitle: String, isSingleChapter: Boolean) -> Unit,
    private val onPickArchive: () -> Unit,
    private val onPickDirectory: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogImportGithubBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogImportGithubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 貼上剪貼簿
        binding.btnPasteClipboard.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clip.isNullOrEmpty()) {
                binding.etGithubUrl.setText(clip)
            } else {
                Toast.makeText(requireContext(), "剪貼簿為空", Toast.LENGTH_SHORT).show()
            }
        }

        // 範例捷徑
        binding.chipExampleYuanTree.setOnClickListener {
            binding.etGithubUrl.setText("https://github.com/wade-fs/novel2movie/tree/main/yuan")
        }
        binding.chipExampleYuanChap1.setOnClickListener {
            binding.etGithubUrl.setText("https://github.com/wade-fs/novel2movie/blob/main/yuan/chapters/volume1/chap001.md")
        }
        binding.chipExampleGuixu.setOnClickListener {
            binding.etGithubUrl.setText("https://github.com/wade-fs/novel2movie/tree/main/guixu")
        }
        binding.chipExampleFormosa.setOnClickListener {
            binding.etGithubUrl.setText("https://github.com/wade-fs/novel2movie/tree/main/Origins-of-Formosa")
        }

        // 下載並閱讀
        binding.btnDownloadAndRead.setOnClickListener {
            val url = binding.etGithubUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "請輸入或貼上 GitHub 網址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val info = GitHubHelper.parseUrl(url)
            if (info == null) {
                Toast.makeText(requireContext(), "無法識別的 GitHub 網址格式，請確認是否包含 github.com", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            startDownload(info)
        }

        // 本機上傳 ZIP / TAR
        binding.btnUploadArchive.setOnClickListener {
            dismiss()
            onPickArchive()
        }

        // 本機選取目錄
        binding.btnSelectDirectory.setOnClickListener {
            dismiss()
            onPickDirectory()
        }
    }

    private fun startDownload(info: com.novel.reader.util.GitHubUrlInfo) {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.btnDownloadAndRead.isEnabled = false
        isCancelable = false

        lifecycleScope.launch {
            val result = if (info.isSingleFile) {
                binding.tvProgressMessage.text = "正在下載單一章節 (${info.path.substringAfterLast("/")})..."
                GitHubHelper.downloadSingleChapter(requireContext(), info)
            } else {
                binding.tvProgressMessage.text = "正在從 GitHub 取得專案並解壓小說..."
                GitHubHelper.downloadTreeFolder(requireContext(), info) { msg ->
                    lifecycleScope.launch {
                        binding.tvProgressMessage.text = msg
                    }
                }
            }

            binding.layoutProgress.visibility = View.GONE
            binding.btnDownloadAndRead.isEnabled = true
            isCancelable = true

            when (result) {
                is GitHubLoadResult.Success -> {
                    Toast.makeText(
                        requireContext(),
                        if (result.isSingleChapter) "已成功載入指定章節！" else "已成功載入小說：共 ${result.chapters.size} 章",
                        Toast.LENGTH_SHORT
                    ).show()
                    dismiss()
                    onImportSuccess(result.chapters, result.title, result.isSingleChapter)
                }
                is GitHubLoadResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
