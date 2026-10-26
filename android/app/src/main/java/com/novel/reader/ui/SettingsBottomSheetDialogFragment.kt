package com.novel.reader.ui

import android.os.Bundle
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novel.reader.R
import com.novel.reader.databinding.BottomSheetSettingsBinding
import com.novel.reader.model.ReadingSettings
import com.novel.reader.model.VoiceGender
import com.novel.reader.model.WorkRecordCategory

class SettingsBottomSheetDialogFragment(
    private var settings: ReadingSettings,
    private val availableVoices: List<Voice>,
    private val onSettingsChanged: (ReadingSettings) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!
    private var isUpdatingUi = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        syncUiFromSettings()
    }

    private fun setupViews() {
        // 1. 預設模式切換
        binding.chipGroupPresetModes.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi) return@setOnCheckedStateChangeListener
            when (checkedIds.firstOrNull()) {
                R.id.chipModeFull -> {
                    settings.filterConfig.setFullMode()
                    notifyChange()
                    syncFilterUi()
                }
                R.id.chipModeBodyOnly -> {
                    settings.filterConfig.setBodyOnlyMode()
                    notifyChange()
                    syncFilterUi()
                }
                R.id.chipModeTitleAndBody -> {
                    settings.filterConfig.setTitleAndBodyMode()
                    notifyChange()
                    syncFilterUi()
                }
            }
        }

        // 2. 語速 Slider 與快速按鈕
        binding.sliderSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.speechRate = value
                binding.tvSpeedValue.text = String.format("%.2fx", value)
                notifyChange()
            }
        }
        binding.btnSpeed075.setOnClickListener { setSpeed(0.75f) }
        binding.btnSpeed100.setOnClickListener { setSpeed(1.00f) }
        binding.btnSpeed125.setOnClickListener { setSpeed(1.25f) }
        binding.btnSpeed150.setOnClickListener { setSpeed(1.50f) }
        binding.btnSpeed200.setOnClickListener { setSpeed(2.00f) }

        // 3. 語音性別 (男生 / 女生 / 預設)
        binding.chipGroupGender.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi) return@setOnCheckedStateChangeListener
            settings.gender = when (checkedIds.firstOrNull()) {
                R.id.chipGenderMale -> VoiceGender.MALE
                R.id.chipGenderFemale -> VoiceGender.FEMALE
                else -> VoiceGender.DEFAULT
            }
            notifyChange()
        }

        // 語調 Slider
        binding.sliderPitch.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.pitch = value
                binding.tvPitchValue.text = String.format("%.2f", value)
                notifyChange()
            }
        }

        // 系統語音下拉清單
        val voiceNames = mutableListOf("🇹🇼 系統預設語音 (優先台灣口音)")
        voiceNames.addAll(availableVoices.map { formatVoiceLabel(it) })
        val voiceAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, voiceNames)
        binding.spinnerVoices.adapter = voiceAdapter

        val selectedIdx = availableVoices.indexOfFirst { it.name == settings.selectedVoiceName }
        if (selectedIdx != -1) {
            binding.spinnerVoices.setSelection(selectedIdx + 1)
        } else {
            binding.spinnerVoices.setSelection(0)
        }

        binding.spinnerVoices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUi) return
                if (position == 0) {
                    settings.selectedVoiceName = null
                } else {
                    settings.selectedVoiceName = availableVoices.getOrNull(position - 1)?.name
                }
                notifyChange()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 開啟系統 TTS 語音設定
        binding.btnOpenSystemTtsSettings.setOnClickListener {
            openSystemTtsSettings()
        }

        // 4. 自動換下一章
        binding.switchAutoNextChapter.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            settings.autoNextChapter = isChecked
            notifyChange()
        }

        // 5. 細項過濾開關
        binding.switchReadTitle.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            settings.filterConfig.readChapterTitle = isChecked
            notifyChange()
            updatePresetChipsSelection()
        }

        binding.switchReadBody.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            settings.filterConfig.readBody = isChecked
            notifyChange()
            updatePresetChipsSelection()
        }

        binding.switchReadWorkRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            settings.filterConfig.readWorkRecords = isChecked
            binding.layoutWorkRecordCategories.visibility = if (isChecked) View.VISIBLE else View.GONE
            notifyChange()
            updatePresetChipsSelection()
        }

        // 工作記錄各分類勾選
        setupCategoryCheckbox(binding.cbHistory, WorkRecordCategory.HISTORICAL_FACTS)
        setupCategoryCheckbox(binding.cbVerification, WorkRecordCategory.PENDING_VERIFICATION)
        setupCategoryCheckbox(binding.cbInference, WorkRecordCategory.INFERENCE)
        setupCategoryCheckbox(binding.cbFiction, WorkRecordCategory.FICTION)
        setupCategoryCheckbox(binding.cbContinuity, WorkRecordCategory.CONTINUITY)
        setupCategoryCheckbox(binding.cbTransition, WorkRecordCategory.TRANSITION)
        setupCategoryCheckbox(binding.cbOther, WorkRecordCategory.OTHER)

        binding.btnDone.setOnClickListener {
            dismiss()
        }
    }

    private fun setupCategoryCheckbox(checkBox: android.widget.CheckBox, category: WorkRecordCategory) {
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            settings.filterConfig.setCategoryEnabled(category, isChecked)
            notifyChange()
            updatePresetChipsSelection()
        }
    }

    private fun setSpeed(speed: Float) {
        settings.speechRate = speed
        binding.sliderSpeed.value = speed
        binding.tvSpeedValue.text = String.format("%.2fx", speed)
        notifyChange()
    }

    private fun syncUiFromSettings() {
        isUpdatingUi = true

        // 語速
        binding.sliderSpeed.value = settings.speechRate.coerceIn(0.5f, 2.5f)
        binding.tvSpeedValue.text = String.format("%.2fx", settings.speechRate)

        // 性別
        when (settings.gender) {
            VoiceGender.DEFAULT -> binding.chipGenderDefault.isChecked = true
            VoiceGender.MALE -> binding.chipGenderMale.isChecked = true
            VoiceGender.FEMALE -> binding.chipGenderFemale.isChecked = true
        }

        // 語調
        binding.sliderPitch.value = settings.pitch.coerceIn(0.6f, 1.6f)
        binding.tvPitchValue.text = String.format("%.2f", settings.pitch)

        // 自動換章
        binding.switchAutoNextChapter.isChecked = settings.autoNextChapter

        syncFilterUi()
        isUpdatingUi = false
    }

    private fun syncFilterUi() {
        isUpdatingUi = true
        val filter = settings.filterConfig
        binding.switchReadTitle.isChecked = filter.readChapterTitle
        binding.switchReadBody.isChecked = filter.readBody
        binding.switchReadWorkRecord.isChecked = filter.readWorkRecords
        binding.layoutWorkRecordCategories.visibility = if (filter.readWorkRecords) View.VISIBLE else View.GONE

        binding.cbHistory.isChecked = filter.readHistoricalFacts
        binding.cbVerification.isChecked = filter.readPendingVerification
        binding.cbInference.isChecked = filter.readInference
        binding.cbFiction.isChecked = filter.readFiction
        binding.cbContinuity.isChecked = filter.readContinuity
        binding.cbTransition.isChecked = filter.readTransition
        binding.cbOther.isChecked = filter.readOtherWorkRecords

        updatePresetChipsSelection()
        isUpdatingUi = false
    }

    private fun updatePresetChipsSelection() {
        when {
            settings.filterConfig.isFull -> binding.chipModeFull.isChecked = true
            settings.filterConfig.isBodyOnly -> binding.chipModeBodyOnly.isChecked = true
            settings.filterConfig.readChapterTitle && settings.filterConfig.readBody && !settings.filterConfig.readWorkRecords -> {
                binding.chipModeTitleAndBody.isChecked = true
            }
            else -> {
                binding.chipGroupPresetModes.clearCheck()
            }
        }
    }

    private fun formatVoiceLabel(v: Voice): String {
        val n = v.name.lowercase()
        val loc = v.locale
        val isTw = loc.country.equals("TW", true) || loc.toLanguageTag().contains("TW", true) || n.contains("tw")
        val gender = when {
            n.contains("female") || n.contains("woman") || n.contains("ctd") || n.contains("cta") -> "女聲"
            n.contains("male") || n.contains("man") || n.contains("ctc") || n.contains("ctb") -> "男聲"
            else -> "預設"
        }
        return if (isTw) {
            "🇹🇼 台灣 ($gender) · ${v.name}"
        } else {
            "🌐 ${loc.displayCountry.ifEmpty { loc.language }} ($gender) · ${v.name}"
        }
    }

    private fun openSystemTtsSettings() {
        try {
            val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (e2: Exception) {
                android.widget.Toast.makeText(requireContext(), "請至 Android「系統設定 ➔ 系統 ➔ 語言與輸入 ➔ 文字轉語音輸出」下載台灣語音", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun notifyChange() {
        onSettingsChanged(settings)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
