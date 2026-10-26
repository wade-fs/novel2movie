package com.novel.reader.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.novel.reader.R
import com.novel.reader.model.ChapterContent
import com.novel.reader.model.WorkRecordCategory

class NovelContentAdapter(
    private val onItemClick: (uiPosition: Int) -> Unit,
    private val onPrevChapterClick: () -> Unit,
    private val onNextChapterClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_TITLE = 0
        const val TYPE_PARAGRAPH = 1
        const val TYPE_WORK_HEADER = 2
        const val TYPE_WORK_LINE = 3
        const val TYPE_NAV_FOOTER = 4
    }

    sealed class DisplayItem(val uiPosition: Int) {
        class TitleItem(uiPos: Int, val title: String) : DisplayItem(uiPos)
        class ParagraphItem(uiPos: Int, val text: String, val paragraphIndex: Int) : DisplayItem(uiPos)
        class WorkHeaderItem(uiPos: Int, val header: String, val category: WorkRecordCategory?, val isMainHeader: Boolean) : DisplayItem(uiPos)
        class WorkLineItem(uiPos: Int, val line: String, val category: WorkRecordCategory?) : DisplayItem(uiPos)
        class NavFooterItem(val hasPrev: Boolean, val hasNext: Boolean) : DisplayItem(-1)
    }

    private val items = mutableListOf<DisplayItem>()
    private var activeUiPosition: Int = -1

    fun setContent(chapterContent: ChapterContent, hasPrev: Boolean = true, hasNext: Boolean = true) {
        items.clear()
        var uiPos = 0

        // 1. 章節標題
        items.add(DisplayItem.TitleItem(uiPos++, chapterContent.title))

        // 2. 正文段落
        chapterContent.bodyParagraphs.forEachIndexed { pIdx, paragraph ->
            items.add(DisplayItem.ParagraphItem(uiPos++, paragraph, pIdx))
        }

        // 3. 工作記錄
        if (chapterContent.workRecords.isNotEmpty()) {
            items.add(DisplayItem.WorkHeaderItem(uiPos++, "章後工作紀錄", null, isMainHeader = true))
            chapterContent.workRecords.forEach { record ->
                items.add(DisplayItem.WorkHeaderItem(uiPos++, record.headerText, record.category, isMainHeader = false))
                record.contentLines.forEach { line ->
                    items.add(DisplayItem.WorkLineItem(uiPos++, line, record.category))
                }
            }
        }

        // 4. 底部上一章/下一章快速換章卡片
        items.add(DisplayItem.NavFooterItem(hasPrev, hasNext))

        activeUiPosition = -1
        notifyDataSetChanged()
    }

    fun setActiveUiPosition(position: Int) {
        val oldPos = activeUiPosition
        activeUiPosition = position

        val oldItemIndex = items.indexOfFirst { it.uiPosition == oldPos }
        if (oldItemIndex != -1) {
            notifyItemChanged(oldItemIndex)
        }

        val newItemIndex = items.indexOfFirst { it.uiPosition == position }
        if (newItemIndex != -1) {
            notifyItemChanged(newItemIndex)
        }
    }

    fun getItemIndexByUiPosition(uiPosition: Int): Int {
        return items.indexOfFirst { it.uiPosition == uiPosition }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is DisplayItem.TitleItem -> TYPE_TITLE
            is DisplayItem.ParagraphItem -> TYPE_PARAGRAPH
            is DisplayItem.WorkHeaderItem -> TYPE_WORK_HEADER
            is DisplayItem.WorkLineItem -> TYPE_WORK_LINE
            is DisplayItem.NavFooterItem -> TYPE_NAV_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TITLE -> {
                val view = inflater.inflate(R.layout.item_chapter_title, parent, false)
                TitleViewHolder(view)
            }
            TYPE_PARAGRAPH -> {
                val view = inflater.inflate(R.layout.item_paragraph, parent, false)
                ParagraphViewHolder(view)
            }
            TYPE_WORK_HEADER -> {
                val view = inflater.inflate(R.layout.item_work_record_header, parent, false)
                WorkHeaderViewHolder(view)
            }
            TYPE_WORK_LINE -> {
                val view = inflater.inflate(R.layout.item_work_record_line, parent, false)
                WorkLineViewHolder(view)
            }
            TYPE_NAV_FOOTER -> {
                val view = inflater.inflate(R.layout.item_chapter_nav_footer, parent, false)
                NavFooterViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val isSpeaking = (item.uiPosition == activeUiPosition)

        when (holder) {
            is TitleViewHolder -> {
                val titleItem = item as DisplayItem.TitleItem
                holder.tvTitle.text = titleItem.title
                holder.itemView.setOnClickListener { onItemClick(item.uiPosition) }
            }
            is ParagraphViewHolder -> {
                val paraItem = item as DisplayItem.ParagraphItem
                holder.tvText.text = "\u3000\u3000" + paraItem.text
                holder.indicator.visibility = if (isSpeaking) View.VISIBLE else View.INVISIBLE
                holder.container.setBackgroundColor(
                    if (isSpeaking) ContextCompat.getColor(holder.itemView.context, R.color.highlight_speaking)
                    else Color.TRANSPARENT
                )
                holder.itemView.setOnClickListener { onItemClick(item.uiPosition) }
            }
            is WorkHeaderViewHolder -> {
                val headerItem = item as DisplayItem.WorkHeaderItem
                holder.tvHeader.text = headerItem.header
                if (headerItem.category != null) {
                    holder.chipTag.visibility = View.VISIBLE
                    holder.chipTag.text = headerItem.category.displayName
                } else {
                    holder.chipTag.visibility = View.GONE
                }
                holder.topDivider.visibility = if (headerItem.isMainHeader) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener { onItemClick(item.uiPosition) }
            }
            is WorkLineViewHolder -> {
                val lineItem = item as DisplayItem.WorkLineItem
                holder.tvLine.text = lineItem.line
                holder.indicator.visibility = if (isSpeaking) View.VISIBLE else View.INVISIBLE
                holder.container.setBackgroundColor(
                    if (isSpeaking) ContextCompat.getColor(holder.itemView.context, R.color.highlight_speaking)
                    else Color.TRANSPARENT
                )
                holder.itemView.setOnClickListener { onItemClick(item.uiPosition) }
            }
            is NavFooterViewHolder -> {
                val footerItem = item as DisplayItem.NavFooterItem
                holder.btnPrev.isEnabled = footerItem.hasPrev
                holder.btnNext.isEnabled = footerItem.hasNext
                holder.btnPrev.setOnClickListener { onPrevChapterClick() }
                holder.btnNext.setOnClickListener { onNextChapterClick() }
            }
        }
    }

    class TitleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvChapterTitle)
    }

    class ParagraphViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: View = itemView.findViewById(R.id.containerParagraph)
        val indicator: View = itemView.findViewById(R.id.indicatorSpeaking)
        val tvText: TextView = itemView.findViewById(R.id.tvParagraphText)
    }

    class WorkHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val topDivider: View = itemView.findViewById(R.id.topDivider)
        val tvHeader: TextView = itemView.findViewById(R.id.tvWorkRecordHeader)
        val chipTag: Chip = itemView.findViewById(R.id.chipCategoryTag)
    }

    class WorkLineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: View = itemView.findViewById(R.id.containerWorkLine)
        val indicator: View = itemView.findViewById(R.id.indicatorSpeakingLine)
        val tvLine: TextView = itemView.findViewById(R.id.tvWorkLineText)
    }

    class NavFooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val btnPrev: Button = itemView.findViewById(R.id.btnFooterPrevChapter)
        val btnNext: Button = itemView.findViewById(R.id.btnFooterNextChapter)
    }
}
