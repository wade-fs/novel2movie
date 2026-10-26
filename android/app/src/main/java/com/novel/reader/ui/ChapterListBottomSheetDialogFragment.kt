package com.novel.reader.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novel.reader.R
import com.novel.reader.databinding.BottomSheetChaptersBinding
import com.novel.reader.model.Chapter

class ChapterListBottomSheetDialogFragment(
    private val chapters: List<Chapter>,
    private val currentIndex: Int,
    private val onSelectChapter: (index: Int) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetChaptersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ChapterListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetChaptersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvTotalChapterCount.text = "共 ${chapters.size} 章"

        adapter = ChapterListAdapter(chapters, currentIndex) { realIndex, _ ->
            onSelectChapter(realIndex)
            dismiss()
        }

        binding.rvChapters.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChapters.adapter = adapter

        // 滾動至當前章節
        if (currentIndex in chapters.indices) {
            binding.rvChapters.post {
                binding.rvChapters.scrollToPosition(currentIndex)
            }
        }

        // 卷數過濾
        binding.chipGroupVolumes.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipVolAll
            val volIndex = when (checkedId) {
                R.id.chipVol1 -> 1
                R.id.chipVol2 -> 2
                R.id.chipVol3 -> 3
                R.id.chipVol4 -> 4
                R.id.chipVol5 -> 5
                R.id.chipVol6 -> 6
                else -> 0
            }
            adapter.filterByVolume(volIndex)
        }

        // 搜尋
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filterByQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 直達按鈕
        binding.btnQuickJump.setOnClickListener {
            performDirectJump()
        }

        // 鍵盤 Enter 鍵直達
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                performDirectJump()
                true
            } else false
        }
    }

    private fun performDirectJump() {
        val query = binding.etSearch.text?.toString()?.trim() ?: ""
        if (query.isEmpty()) return

        // 若輸入純數字，優先依章號跳轉
        val chapNum = query.toIntOrNull()
        if (chapNum != null) {
            val targetIdx = chapters.indexOfFirst { it.chapterNumber == chapNum }
            if (targetIdx != -1) {
                onSelectChapter(targetIdx)
                dismiss()
                return
            }
        }

        // 依關鍵字找第一個匹配的章節跳轉
        val matchedIdx = chapters.indexOfFirst { it.title.contains(query, ignoreCase = true) }
        if (matchedIdx != -1) {
            onSelectChapter(matchedIdx)
            dismiss()
        } else {
            Toast.makeText(requireContext(), "未找到符合「$query」的章節", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
