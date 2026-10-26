package com.novel.reader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.novel.reader.R
import com.novel.reader.model.Chapter

class ChapterListAdapter(
    private var allChapters: List<Chapter>,
    private var currentChapterIndex: Int,
    private val onChapterSelected: (index: Int, chapter: Chapter) -> Unit
) : RecyclerView.Adapter<ChapterListAdapter.ViewHolder>() {

    private var filteredChapters: List<Chapter> = allChapters
    private var currentVolumeFilter: Int = 0 // 0 means all volumes
    private var currentSearchQuery: String = ""

    fun updateChapters(chapters: List<Chapter>, currentIndex: Int) {
        this.allChapters = chapters
        this.currentChapterIndex = currentIndex
        applyFilter()
    }

    fun setCurrentIndex(index: Int) {
        this.currentChapterIndex = index
        notifyDataSetChanged()
    }

    fun filterByVolume(volumeIndex: Int) {
        this.currentVolumeFilter = volumeIndex
        applyFilter()
    }

    fun filterByQuery(query: String) {
        this.currentSearchQuery = query.trim()
        applyFilter()
    }

    private fun applyFilter() {
        filteredChapters = allChapters.filter { chapter ->
            val matchVolume = (currentVolumeFilter == 0 || chapter.volumeIndex == currentVolumeFilter)
            val matchQuery = if (currentSearchQuery.isEmpty()) true else {
                chapter.title.contains(currentSearchQuery, ignoreCase = true) ||
                        chapter.chapterNumber.toString().contains(currentSearchQuery)
            }
            matchVolume && matchQuery
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = filteredChapters.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter_list_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chapter = filteredChapters[position]
        val realIndex = allChapters.indexOf(chapter)
        val isCurrent = (realIndex == currentChapterIndex)

        holder.tvNum.text = "${chapter.chapterNumber}"
        holder.tvTitle.text = chapter.title
        holder.ivIndicator.visibility = if (isCurrent) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onChapterSelected(realIndex, chapter)
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvNum: TextView = itemView.findViewById(R.id.tvChapterNumBadge)
        val tvTitle: TextView = itemView.findViewById(R.id.tvChapterEntryTitle)
        val ivIndicator: ImageView = itemView.findViewById(R.id.ivCurrentPlayingIndicator)
    }
}
