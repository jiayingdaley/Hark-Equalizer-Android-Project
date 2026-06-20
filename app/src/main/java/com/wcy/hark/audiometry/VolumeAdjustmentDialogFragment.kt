package com.wcy.hark.audiometry
import com.wcy.hark.R

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.DialogFragment

class VolumeAdjustmentDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "VolumeAdjustmentDialog" // 新增 TAG
    }

    private lateinit var audioManager: AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var seekBarSystemVolume: SeekBar
    private lateinit var callback: DialogNavCallback // 新增 callback 變數

    private val soundResourceName = "adjust_mcl"

    override fun onAttach(context: Context) { // 新增 onAttach
        super.onAttach(context)
        if (context is DialogNavCallback) {
            callback = context
        } else {
            throw RuntimeException("$context must implement DialogNavCallback")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_volume_adjustment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        audioManager = requireActivity().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        seekBarSystemVolume = view.findViewById(R.id.seekBarSystemVolume)
        val buttonCloseDialog: ImageButton = view.findViewById(R.id.buttonCloseDialog)
        val buttonConfirmVolume: Button = view.findViewById(R.id.buttonConfirmVolume)

        setupVolumeSeekBar()
        prepareAndPlaySound()

        buttonCloseDialog.setOnClickListener {
            // 根據你的要求，點擊 "X" 會關閉此 Dialog，流程回到 SpeechAudiometryExplanationActivity
            // DialogFragment 的 dismiss() 方法本身就會關閉視窗，使其下的 Activity 可見
            dismiss()
        }

        buttonConfirmVolume.setOnClickListener {
            // 使用 callback 通知 Activity 顯示下一個 Dialog
            callback.onVolumeAdjustedShowInstructions()
            dismiss() // 關閉自己
        }
    }

    // ... (setupVolumeSeekBar, prepareAndPlaySound, onStart, onStop, dismiss 方法保持不變)
    private fun setupVolumeSeekBar() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        seekBarSystemVolume.max = maxVolume
        seekBarSystemVolume.progress = currentVolume

        seekBarSystemVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun prepareAndPlaySound() {
        val soundResourceId = resources.getIdentifier(soundResourceName, "raw", requireActivity().packageName)

        if (soundResourceId == 0) {
            // 檔案未找到的處理
            Toast.makeText(context, "舒適音量測試音訊檔案 ($soundResourceName.wav) 未找到於 res/raw/", Toast.LENGTH_LONG).show()
            // 你可以在此處決定是否禁用 "OK" 按鈕或採取其他措施
            view?.findViewById<Button>(R.id.buttonConfirmVolume)?.isEnabled = false
            return
        }

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            try {
                val afd = resources.openRawResourceFd(soundResourceId) ?: return
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepareAsync()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "無法載入音訊檔案", Toast.LENGTH_SHORT).show()
                view?.findViewById<Button>(R.id.buttonConfirmVolume)?.isEnabled = false
                return
            }
        }

        mediaPlayer?.setOnPreparedListener {
            it.start()
        }
        mediaPlayer?.setOnErrorListener { _, _, _ ->
            Toast.makeText(context, "播放音訊時發生錯誤", Toast.LENGTH_SHORT).show()
            view?.findViewById<Button>(R.id.buttonConfirmVolume)?.isEnabled = false
            true // 表示已處理錯誤
        }
    }

    override fun onStart() {
        super.onStart()
        // 設定 DialogFragment 的寬高
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // dialog?.setCanceledOnTouchOutside(false) // 如果需要點擊外部不消失，取消此行註解
    }

    override fun onStop() {
        super.onStop()
        // 釋放 MediaPlayer 資源
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun dismiss() {
        // 先檢查 mediaPlayer 是否為 null，避免重複釋放或對 null 物件操作
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        super.dismiss()
    }
}