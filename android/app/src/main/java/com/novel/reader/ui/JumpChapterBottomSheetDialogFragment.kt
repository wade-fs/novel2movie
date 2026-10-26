package com.novel.reader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novel.reader.databinding.DialogJumpChapterBinding
import com.novel.reader.model.Chapter

class JumpChapterBottomSheetDialogFragment(
    private val chapters: List<Chapter>,
    private val currentChapterIndex: Int,
    private val onJumpToChapter: (chapterIndex: Int) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogJumpChapterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogJumpChapterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (chapters.isEmpty()) return

        val total = chapters.size
        binding.tvJumpSubtitle.text = "輸入章號或滑動滑桿快速跳轉 (共 $total 章)"

        val curNum = chapters.getOrNull(currentChapterIndex)?.chapterNumber ?: 1
        binding.sliderChapter.valueFrom = 1f
        binding.sliderChapter.valueTo = total.toFloat()
        binding.sliderChapter.value = curNum.toFloat().coerceIn(1f, total.toFloat())

        updatePreview(curNum)

        binding.sliderChapter.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val chapNum = value.toInt()
                updatePreview(chapNum)
            }
        }

        binding.btnDirectGo.setOnClickListener {
            val text = binding.etDirectChapterNum.text?.toString()?.trim()
            val targetNum = if (!text.isNullOrEmpty()) {
                text.toIntOrNull()
            } else {
                binding.sliderChapter.value.toInt()
            }

            if (targetNum == null || targetNum !in 1..total) {
                Toast.makeText(requireContext(), "請輸入 1 到 $total 之間的章號", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            jumpToChapterNumber(targetNum)
        }

        // 各卷起點快捷鍵
        binding.btnJumpVol1.setOnClickListener { jumpToChapterNumber(1) }
        binding.btnJumpVol2.setOnClickListener { jumpToChapterNumber(91) }
        binding.btnJumpVol3.setOnClickListener { jumpToChapterNumber(181) }
        binding.btnJumpVol4.setOnClickListener { jumpToChapterNumber(271) }
        binding.btnJumpVol5.setOnClickListener { jumpToChapterNumber(361) }
        binding.btnJumpVol6.setOnClickListener { jumpToChapterNumber(451) }
    }

    private fun updatePreview(chapNum: Int) {
        binding.tvSliderPreview.text = "第 $chapNum 章"
        val chap = chapters.find { it.chapterNumber == chapNum } ?: chapters.getOrNull(chapNum - 1)
        binding.tvPreviewChapterTitle.text = chap?.title ?: "第 $chapNum 章"
    }

    private fun jumpToChapterNumber(chapNum: Int) {
        val targetIdx = chapters.indexOfFirst { it.chapterNumber == chapNum }
        val finalIdx = if (targetIdx != -1) targetIdx else (chapNum - 1).coerceIn(chapters.indices)
        dismiss()
        onJumpToChapter(finalIdx)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
