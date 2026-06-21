package com.pisces312.streamclip.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.pisces312.streamclip.BaseActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.pisces312.streamclip.R
import com.pisces312.streamclip.audio.AudioDecoder
import com.pisces312.streamclip.audio.AudioEncoder
import com.pisces312.streamclip.audio.AudioPlayer
import com.pisces312.streamclip.audio.FFmpegWaveformLoader
import com.pisces312.streamclip.audio.FastWaveformLoader
import com.pisces312.streamclip.audio.WaveformProcessor
import com.pisces312.streamclip.audio.WaveformView
import com.pisces312.streamclip.databinding.ActivityAudioEditorBinding
import com.pisces312.streamclip.util.LogCollector
import com.pisces312.streamclip.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ShortBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音频编辑主界面。
 * 波形显示、选区裁剪、预览播放、多格式导出。
 */
class AudioEditorActivity : BaseActivity(), WaveformView.WaveformListener {

    companion object {
        private const val TAG = "AudioEditorActivity"
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LOAD_MODE = "load_mode"
        const val MODE_EDIT = "edit"
        const val MODE_RECORD = "record"
        const val LOAD_MODE_A = "a"  // streaming (not yet implemented)
        const val LOAD_MODE_B = "b"  // fast preview waveform
        const val LOAD_MODE_C = "c"  // optimized full decode
        const val LOAD_MODE_D = "d"  // ffmpeg waveform
        private const val PREF_NAME = "audio_editor_prefs"
        private const val KEY_LAST_EXPORT_DIR = "last_export_dir"
        private const val BOUNDARY_TOUCH_DP = 24
    }

    private lateinit var binding: ActivityAudioEditorBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var decodedAudio: AudioDecoder.DecodedAudio? = null
    private var player: AudioPlayer? = null
    private var encoder: AudioEncoder? = null

    private var isPlaying = false
    private var startPos = 0   // in waveform pixels
    private var endPos = 0     // in waveform pixels
    private var cursorPos = 0  // current playback cursor position in pixels
    private var offset = 0
    private var density = 0f
    private var audioFile: File? = null
    private var pendingExportFormat: AudioEncoder.OutputFormat? = null

    // 当前一次播放的终点（毫秒）；轮询 Runnable 到此处即视为播放完成，触发 UI 复位
    private var currentPlaybackEndMs = 0

    // Selection state — 选区由像素范围表示，无需额外标记
    private var selectionStartPx = 0
    private var selectionEndPx = 0
    /** 选区是否有效（起点 < 终点） */
    private val hasValidSelection: Boolean get() = selectionStartPx < selectionEndPx

    /** 清除选区像素状态（视觉清除由调用方负责） */
    private fun clearSelectionState() {
        selectionStartPx = 0
        selectionEndPx = 0
    }
    private var isLoopingSelection = false
    private var touchDownPos = 0
    private var isDraggingSelection = false
    private var isAdjustingBoundary = false
    private var isPendingInsideSelection = false  // 触摸落在选区内部，等待判断是拖拽还是点击
    private var boundaryTouchThreshold = 30f

    // Undo stack: (startPos, endPos, selectionStartPx, selectionEndPx)
    private data class UndoState(val startPos: Int, val endPos: Int, val selStart: Int, val selEnd: Int)
    private val undoStack = ArrayDeque<UndoState>()

    private val pickDirLauncher: ActivityResultLauncher<Uri?> = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* 蹇界暐 */ }

            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putString(KEY_LAST_EXPORT_DIR, treeUri.toString()).apply()

            val format = pendingExportFormat ?: return@registerForActivityResult
            performExport(treeUri, format)
        }
        pendingExportFormat = null
    }

    private val handler = Handler(Looper.getMainLooper())
    // 播放轮询版本号：每次 startPlayback 递增，旧 runnable 检测到版本不匹配则自裁
    private var playbackGeneration = 0
    // 当前活跃的轮询 Runnable（用于精确移除）
    private var activePlayRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_audio_editor)

        density = resources.displayMetrics.density
        boundaryTouchThreshold = BOUNDARY_TOUCH_DP * density
        binding.waveformView.setListener(this)
        binding.waveformView.recomputeHeights(density)

        LogCollector.i(TAG, "onCreate: density=$density")

        setupControls()

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_EDIT
        LogCollector.i(TAG, "onCreate: mode=$mode")
        if (mode == MODE_RECORD) {
            Toast.makeText(this, getString(R.string.ae_recording_dev), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val audioUriStr = intent.getStringExtra(EXTRA_AUDIO_URI)
        val loadMode = intent.getStringExtra(EXTRA_LOAD_MODE) ?: LOAD_MODE_C
        if (audioUriStr != null) {
            loadAudio(Uri.parse(audioUriStr), loadMode)
        } else {
            Toast.makeText(this, getString(R.string.ae_no_audio_file), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupControls() {
        binding.btnPlay.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }

        binding.btnRewind.setOnClickListener {
            player?.seekTo(0)
            cursorPos = 0
            currentPlaybackEndMs = if (hasValidSelection) {
                binding.waveformView.pixelsToMillisecs(selectionEndPx)
            } else {
                binding.waveformView.pixelsToMillisecs(endPos)
            }
            binding.waveformView.setPlayback(0)
            binding.waveformView.invalidate()
            binding.tvCurrentTime.text = formatTime(0)
        }

        binding.btnFfwd.setOnClickListener {
            val targetEndPx = if (hasValidSelection) selectionEndPx else endPos
            val endMs = binding.waveformView.pixelsToMillisecs(targetEndPx)
            player?.seekTo(endMs)
            cursorPos = targetEndPx
            currentPlaybackEndMs = endMs
            binding.waveformView.setPlayback(targetEndPx)
            binding.waveformView.invalidate()
            binding.tvCurrentTime.text = formatTime(endMs)
        }

        binding.btnZoomIn.setOnClickListener {
            binding.waveformView.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.waveformView.zoomOut()
        }

        binding.btnFitToScreen.setOnClickListener {
            while (binding.waveformView.canZoomOut()) {
                binding.waveformView.zoomOut()
            }
            offset = 0
            updateDisplay()
        }

        // Export format spinner
        val formatOptions = AudioEncoder.OutputFormat.entries.map { "${it.displayName} (.${it.extension})" }
        val formatAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, formatOptions)
        binding.spinnerExportFormat.setAdapter(formatAdapter)
        binding.spinnerExportFormat.setText(formatOptions[0], false)

        // Sample rate spinner
         val sampleRateOptions = listOf("原始", "44100 Hz", "48000 Hz", "22050 Hz", "16000 Hz")
        val sampleRateAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sampleRateOptions)
        binding.spinnerSampleRate.setAdapter(sampleRateAdapter)
        binding.spinnerSampleRate.setText(sampleRateOptions[0], false)

        // Bitrate mode spinner
        val bitrateModeOptions = listOf(getString(R.string.ae_cbr), getString(R.string.ae_vbr))
        val bitrateModeAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, bitrateModeOptions)
        binding.spinnerBitrateMode.setAdapter(bitrateModeAdapter)
        binding.spinnerBitrateMode.setText(bitrateModeOptions[0], false)

        // Cascading: format change 鈫?show/hide bitrate controls
        binding.spinnerExportFormat.setOnItemClickListener { _, _, _, _ -> updateExportSettingsVisibility() }
        binding.spinnerBitrateMode.setOnItemClickListener { _, _, _, _ -> updateBitrateSlider() }

        // Bitrate slider value display
        binding.sliderBitrate.addOnChangeListener { _, value, _ ->
            val isVbr = binding.spinnerBitrateMode.text.toString() == getString(R.string.ae_vbr)
            val formatIndex = AudioEncoder.OutputFormat.entries.map { "${it.displayName} (.${it.extension})" }
                .indexOf(binding.spinnerExportFormat.text.toString())
            val format = AudioEncoder.OutputFormat.entries.getOrElse(formatIndex) { AudioEncoder.OutputFormat.MP3 }

            if (isVbr) {
                when (format) {
                    AudioEncoder.OutputFormat.MP3 -> binding.tvBitrateValue.text = "${value.toInt()} (鈮?{listOf(245,225,190,175,150,130,110,95,80,65)[value.toInt().coerceIn(0,9)]} kbps)"
                    AudioEncoder.OutputFormat.OPUS -> binding.tvBitrateValue.text = "${value.toInt()} kbps"
                    else -> binding.tvBitrateValue.text = "${value.toInt()}"
                }
            } else {
                binding.tvBitrateValue.text = "${value.toInt()} kbps"
            }
        }

        updateExportSettingsVisibility()

        binding.btnExport.setOnClickListener {
            val index = formatOptions.indexOf(binding.spinnerExportFormat.text.toString())
            val format = AudioEncoder.OutputFormat.entries.getOrElse(index) { AudioEncoder.OutputFormat.MP3 }
            exportAudio(format)
        }

        // Selection action bar
        binding.btnSelectAll.setOnClickListener {
            selectionStartPx = 0
            selectionEndPx = endPos
            cursorPos = 0
            binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
            binding.waveformView.setPlayback(0)
            binding.waveformView.invalidate()
            binding.tvCurrentTime.text = formatTime(0)
            updateEditActionsState()
            updateDisplay()
        }

        binding.btnDeleteSelected.setOnClickListener {
            if (hasValidSelection) {
                val startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
                val endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
                performDeleteSelected(startMs, endMs)
            }
        }

        binding.btnKeepOnly.setOnClickListener {
            if (hasValidSelection) {
                val startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
                val endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
                performKeepOnly(startMs, endMs)
            }
        }

        binding.btnUndo.setOnClickListener {
            performUndo()
        }

        // 初始状态：删/留按钮灰掉
        updateEditActionsState()
    }

    /**
     * 刷新编辑操作按钮的启用状态。
     * - 删/留：需要 hasValidSelection=true
     * - 撤销：需要 undoStack 非空
     */
    private fun updateEditActionsState() {
        binding.btnDeleteSelected.isEnabled = hasValidSelection
        binding.btnKeepOnly.isEnabled = hasValidSelection
        binding.btnUndo.isEnabled = undoStack.isNotEmpty()
    }

    private fun loadAudio(uri: Uri, loadMode: String = LOAD_MODE_C) {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE
        binding.tvLoadingText.text = getString(R.string.ae_decoding)
        LogCollector.i(TAG, "loadAudio: uri=$uri, mode=$loadMode")

        scope.launch {
            try {
                val startTime = System.currentTimeMillis()

                when (loadMode) {
                    LOAD_MODE_B -> loadFastPreview(uri, startTime)
                    LOAD_MODE_D -> loadFFmpegWaveform(uri, startTime)
                    LOAD_MODE_A -> {
                        // Not yet implemented, fallback to C
                        LogCollector.w(TAG, "loadAudio: mode A not implemented, falling back to C")
                        loadOptimizedDecode(uri, startTime)
                    }
                    else -> loadOptimizedDecode(uri, startTime)
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "loadAudio failed: ${e.javaClass.simpleName}: ${e.message}", e)
                Log.e(TAG, "Failed to load audio", e)
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@AudioEditorActivity)
                        .setTitle(getString(R.string.error))
                        .setMessage("${e.javaClass.simpleName}: ${e.message}")
                        .setPositiveButton(getString(R.string.ok)) { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

    /**
     * Mode C: Optimized full decode.
     * Improvements over original: larger dequeue timeout, pre-allocated buffer,
     * reduced progress callback frequency, direct URI source (skip cache copy when possible).
     */
    private suspend fun loadOptimizedDecode(uri: Uri, startTime: Long) {
        val file = copyUriToCache(uri)
        audioFile = file
        LogCollector.i(TAG, "loadOptimizedDecode: cached file=${file.absolutePath}, size=${file.length()}")

        binding.tvLoadingPercent.text = ""
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.max = 100
        binding.progressBar.progress = 0

        val decoder = AudioDecoder()
        val decoded = withContext(Dispatchers.IO) {
            decoder.decodeOptimized(file.absolutePath, object : AudioDecoder.ProgressListener {
                override fun onProgress(fraction: Double): Boolean {
                    runOnUiThread {
                        val percent = (fraction * 100).toInt()
                        binding.tvLoadingPercent.text = "$percent%"
                        binding.progressBar.progress = percent
                    }
                    return true
                }
            })
        }
        decodedAudio = decoded
        val decodeMs = System.currentTimeMillis() - startTime
        LogCollector.i(TAG, "loadOptimizedDecode: decoded in ${decodeMs}ms, samples=${decoded.numSamples}, rate=${decoded.sampleRate}, ch=${decoded.channels}")

        val waveform = withContext(Dispatchers.Default) {
            WaveformProcessor.process(decoded.samples, decoded.channels, decoded.numSamples)
        }
        LogCollector.i(TAG, "loadOptimizedDecode: waveform frames=${waveform.numFrames}")

        decoded.samples.rewind()
        player = AudioPlayer(decoded)

        binding.waveformView.setData(waveform, decoded.sampleRate)
        binding.waveformView.recomputeHeights(density)

        startPos = 0
        endPos = binding.waveformView.maxPos()
        offset = 0
        // 加载后默认全选（播放起止点 = 整个音频范围），红色光标在 0
        selectionStartPx = 0
        selectionEndPx = endPos
        cursorPos = 0
        currentPlaybackEndMs = 0
        binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
        binding.waveformView.setPlayback(0)
        updateEditActionsState()
        updateDisplay()

        val durationMs = (decoded.numSamples.toDouble() / decoded.sampleRate * 1000).toInt()
        val channelsStr = if (decoded.channels == 1) getString(R.string.ae_mono) else getString(R.string.ae_stereo)
        val info = "${file.absolutePath}  |  ${formatTime(durationMs)}  |  ${decoded.sampleRate}Hz  |  $channelsStr  |  ${decoded.avgBitrateKbps}kbps  |  [C] ${decodeMs}ms"
                binding.tvFileInfo.text = info
                binding.tvFileInfo.isSelected = true

        binding.progressBar.visibility = View.GONE
        binding.loadingLayout.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE
        LogCollector.i(TAG, "loadOptimizedDecode: success in ${System.currentTimeMillis() - startTime}ms total")
    }

    /**
     * Mode B: Fast preview waveform using MediaExtractor frame skipping.
     * Only decodes a sparse subset of frames to build an approximate waveform quickly.
     * Full decode for playback happens lazily in background.
     */
    private suspend fun loadFastPreview(uri: Uri, startTime: Long) {
        val file = copyUriToCache(uri)
        audioFile = file

        binding.tvLoadingPercent.text = ""
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.max = 100
        binding.progressBar.progress = 0

        // Step 1: Fast preview waveform (sparse decode)
        val previewLoader = FastWaveformLoader()
        val previewResult = withContext(Dispatchers.IO) {
            previewLoader.loadPreview(file.absolutePath, object : FastWaveformLoader.ProgressListener {
                override fun onProgress(fraction: Double) {
                    runOnUiThread {
                        val percent = (fraction * 100).toInt()
                        binding.tvLoadingPercent.text = "$percent%"
                        binding.progressBar.progress = percent
                    }
                }
            })
        }
        val previewMs = System.currentTimeMillis() - startTime
        LogCollector.i(TAG, "loadFastPreview: preview waveform in ${previewMs}ms, frames=${previewResult.numFrames}")

        // Show waveform immediately
        binding.tvLoadingPercent.text = "娉㈠舰宸插姞杞斤紝鍚庡彴瑙ｇ爜涓?.."

        val waveform = WaveformProcessor.processFromGains(
            previewResult.frameGains,
            previewResult.sampleRate,
            previewResult.channels,
            previewResult.durationMs
        )

        binding.waveformView.setData(waveform, previewResult.sampleRate)
        binding.waveformView.recomputeHeights(density)
        startPos = 0
        endPos = binding.waveformView.maxPos()
        offset = 0
        // 加载后默认全选（播放起止点 = 整个音频范围），红色光标在 0
        selectionStartPx = 0
        selectionEndPx = endPos
        cursorPos = 0
        currentPlaybackEndMs = 0
        binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
        binding.waveformView.setPlayback(0)
        updateEditActionsState()
        val durationMs = previewResult.durationMs
        val channelsStr = if (previewResult.channels == 1) getString(R.string.ae_mono) else getString(R.string.ae_stereo)
        val info = "${file.absolutePath}  |  ${formatTime(durationMs)}  |  ${previewResult.sampleRate}Hz  |  $channelsStr  |  [B] ${previewMs}ms"
                binding.tvFileInfo.text = info
                binding.tvFileInfo.isSelected = true

        binding.progressBar.visibility = View.GONE
        binding.loadingLayout.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE
        updateDisplay()

        // Step 2: Full decode in background for playback
        withContext(Dispatchers.IO) {
            val decoder = AudioDecoder()
            val decoded = decoder.decodeOptimized(file.absolutePath, null)
            decodedAudio = decoded
            decoded.samples.rewind()
            withContext(Dispatchers.Main) {
                player = AudioPlayer(decoded)
                binding.tvLoadingPercent.text = ""
                LogCollector.i(TAG, "loadFastPreview: background decode done in ${System.currentTimeMillis() - startTime}ms total")
            }
        }
    }

    /**
     * Mode D: FFmpeg showwavespic waveform generation.
     * Uses FFmpeg to generate a waveform image and extracts amplitude data from it.
     * Very fast, but waveform is approximate and playback requires full decode afterward.
     */
    private suspend fun loadFFmpegWaveform(uri: Uri, startTime: Long) {
        val file = copyUriToCache(uri)
        audioFile = file

        binding.tvLoadingPercent.text = ""
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.max = 100
        binding.progressBar.progress = 10

        val ffmpegLoader = FFmpegWaveformLoader()
        val result = withContext(Dispatchers.IO) {
            ffmpegLoader.loadWaveform(file.absolutePath)
        }
        val ffmpegMs = System.currentTimeMillis() - startTime
        LogCollector.i(TAG, "loadFFmpegWaveform: waveform in ${ffmpegMs}ms, frames=${result.frameGains.size}")

        if (!result.success) {
            throw RuntimeException("FFmpeg waveform failed: ${result.errorMessage}")
        }

        binding.progressBar.progress = 80

        val waveform = WaveformProcessor.processFromGains(
            result.frameGains,
            result.sampleRate,
            result.channels,
            result.durationMs
        )

        binding.waveformView.setData(waveform, result.sampleRate)
        binding.waveformView.recomputeHeights(density)
        startPos = 0
        endPos = binding.waveformView.maxPos()
        offset = 0
        // 加载后默认全选（播放起止点 = 整个音频范围），红色光标在 0
        selectionStartPx = 0
        selectionEndPx = endPos
        cursorPos = 0
        currentPlaybackEndMs = 0
        binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
        binding.waveformView.setPlayback(0)
        updateEditActionsState()
        val durationMs = result.durationMs
        val channelsStr = if (result.channels == 1) getString(R.string.ae_mono) else getString(R.string.ae_stereo)
        val info = "${file.absolutePath}  |  ${formatTime(durationMs)}  |  ${result.sampleRate}Hz  |  $channelsStr  |  [D] ${ffmpegMs}ms"
                binding.tvFileInfo.text = info
                binding.tvFileInfo.isSelected = true

        binding.progressBar.progress = 90

        // Full decode in background for playback
        withContext(Dispatchers.IO) {
            val decoder = AudioDecoder()
            val decoded = decoder.decodeOptimized(file.absolutePath, null)
            decodedAudio = decoded
            decoded.samples.rewind()
            withContext(Dispatchers.Main) {
                player = AudioPlayer(decoded)
                binding.progressBar.visibility = View.GONE
                binding.loadingLayout.visibility = View.GONE
                binding.contentLayout.visibility = View.VISIBLE
                updateDisplay()
                LogCollector.i(TAG, "loadFFmpegWaveform: background decode done in ${System.currentTimeMillis() - startTime}ms total")
            }
        }
    }

    /**
     * 解析音频 URI 为本地文件路径。
     * 优先原地直读（file:// / ExternalStorage / Downloads / MediaStore），
     * 解析失败或文件不存在时再 fallback 到 cacheDir 复制。
     * onDestroy 会按 cacheDir 路径判断并自动清理复制的副本（见 L1273）。
     */
    private fun copyUriToCache(uri: Uri): File {
        // 1. 优先原地直读
        val direct = FileUtils.getPathResultFromUri(this, uri)
        if (direct != null && File(direct.path).exists()) {
            val directFile = File(direct.path)
            LogCollector.i(TAG, "copyUriToCache: direct read ${directFile.absolutePath}, size=${directFile.length()}")
            return directFile
        }

        // 2. 兜底：复制到 cacheDir
        val docName = DocumentFile.fromSingleUri(this, uri)?.name
        val fileName = docName ?: "audio_${System.currentTimeMillis()}"
        LogCollector.i(TAG, "copyUriToCache: fallback copy uri=$uri, docName=$docName, fileName=$fileName")
        val cacheFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")
        LogCollector.i(TAG, "copyUriToCache: cached ${cacheFile.absolutePath}, size=${cacheFile.length()}")
        return cacheFile
    }

    private fun startPlayback() {
        val p = player
        if (p == null) {
             Toast.makeText(this, "后台解码中，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        // 清除旧的轮询回调，防止多次调用后 handler 队列堆积
        activePlayRunnable?.let { handler.removeCallbacks(it) }
        // 始终从选区像素值计算播放范围（选区像素由 waveformView 绘制管理，不会丢失）
        // 当选区有效（start < end）时使用选区，否则从光标位置播放到音频末尾
        val startMs = if (hasValidSelection) {
            binding.waveformView.pixelsToMillisecs(selectionStartPx)
        } else {
            binding.waveformView.pixelsToMillisecs(cursorPos)
        }
        val endMs = if (hasValidSelection) {
            binding.waveformView.pixelsToMillisecs(selectionEndPx)
        } else {
            binding.waveformView.pixelsToMillisecs(endPos)
        }
        LogCollector.i(TAG, "startPlayback: startMs=$startMs, endMs=$endMs, " +
                "selPx=[$selectionStartPx,$selectionEndPx], endPos=$endPos, " +
                "hasValidSel=$hasValidSelection, gen=$playbackGeneration")
        p.setLooping(false)
        isLoopingSelection = false
        p.setPlaybackRange(startMs, endMs)
        currentPlaybackEndMs = endMs
        p.start()
        isPlaying = true
        binding.btnPlay.setImageResource(R.drawable.ic_pause)
        // 光标同步到播放起点
        val cursorPx = if (hasValidSelection) selectionStartPx else cursorPos
        cursorPos = cursorPx
        binding.waveformView.setPlayback(cursorPx)
        binding.waveformView.invalidate()
        binding.tvCurrentTime.text = formatTime(startMs)
        postPlayPositionPoller()
    }

    private fun pausePlayback() {
        player?.pause()
        isPlaying = false
        binding.btnPlay.setImageResource(R.drawable.ic_play)
        activePlayRunnable?.let { handler.removeCallbacks(it) }
    }

    /**
     * 投递播放位置轮询 Runnable。每次调用递增 generation，旧 runnable 自动自裁。
     */
    private fun postPlayPositionPoller() {
        val gen = ++playbackGeneration
        LogCollector.i(TAG, "postPlayPositionPoller: gen=$gen, endMs=$currentPlaybackEndMs")
        val runnable = object : Runnable {
            override fun run() {
                if (gen != playbackGeneration) {
                    LogCollector.i(TAG, "poller: gen mismatch $gen != $playbackGeneration, exit")
                    return  // 已被新播放取代，自裁
                }
                if (!isPlaying) return
                val p = player ?: return
                val ms = p.getCurrentPosition()
                LogCollector.d(TAG, "poller: pos=${ms}ms, endMs=$currentPlaybackEndMs")
                if (currentPlaybackEndMs > 0 && ms >= currentPlaybackEndMs - 5) {
                    LogCollector.i(TAG, "poller: reached end → onPlaybackComplete")
                    onPlaybackComplete()
                    return
                }
                val pos = binding.waveformView.millisecsToPixels(ms)
                cursorPos = pos
                binding.waveformView.setPlayback(pos)
                binding.waveformView.invalidate()
                binding.tvCurrentTime.text = formatTime(ms)
                handler.postDelayed(this, 50)
            }
        }
        activePlayRunnable = runnable
        handler.post(runnable)
    }

    /**
     * 播放完成（轮询 Runnable 到达 currentPlaybackEndMs 时触发）。
     * 复位 UI：按钮变播放、光标移到选区首（或 0）、停止位置刷新。
     */
    private fun onPlaybackComplete() {
        if (!isPlaying) return  // 已被手动暂停/停止，防止重入
        LogCollector.i(TAG, "onPlaybackComplete: gen=$playbackGeneration, endMs=$currentPlaybackEndMs")
        // 立即设置 false + 递增 generation，形成原子门控，防止并发 runnable 重入
        isPlaying = false
        playbackGeneration++  // 使所有旧 runnable 的 generation 检查失败
        activePlayRunnable?.let { handler.removeCallbacks(it) }
        binding.btnPlay.setImageResource(R.drawable.ic_play)
        isLoopingSelection = false
        player?.setLooping(false)
        player?.stop()
        // 光标回到选区首（无选区则回到 0）
        val newCursor = if (hasValidSelection) selectionStartPx else 0
        cursorPos = newCursor
        binding.waveformView.setPlayback(newCursor)
        binding.waveformView.invalidate()
        binding.tvCurrentTime.text = formatTime(binding.waveformView.pixelsToMillisecs(newCursor))
    }

    private fun exportAudio(format: AudioEncoder.OutputFormat) {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val lastDirStr = prefs.getString(KEY_LAST_EXPORT_DIR, null)

        if (lastDirStr != null) {
            val dirUri = Uri.parse(lastDirStr)
            try {
                val docFile = DocumentFile.fromTreeUri(this, dirUri)
                if (docFile != null && docFile.exists()) {
                    performExport(dirUri, format)
                    return
                }
            } catch (_: Exception) { /* URI invalid, fall through to picker */ }
        }

        pendingExportFormat = format
        pickDirLauncher.launch(null)
    }

    private fun performExport(dirUri: Uri, format: AudioEncoder.OutputFormat) {
        val decoded = decodedAudio ?: return
        val startMs = if (hasValidSelection) {
            binding.waveformView.pixelsToMillisecs(selectionStartPx)
        } else {
            binding.waveformView.pixelsToMillisecs(startPos)
        }
        val endMs = if (hasValidSelection) {
            binding.waveformView.pixelsToMillisecs(selectionEndPx)
        } else {
            binding.waveformView.pixelsToMillisecs(endPos)
        }
        LogCollector.i(TAG, "performExport: format=${format.displayName}, range=${startMs}-${endMs}ms, samples=${decoded.numSamples}")
        val fadeIn = binding.sliderFadeIn.value
        val fadeOut = binding.sliderFadeOut.value

        val baseName = audioFile?.nameWithoutExtension ?: "audio"
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val outputFileName = "${baseName}_${timestamp}.${format.extension}"

        val dir = DocumentFile.fromTreeUri(this, dirUri)
        val outputFile = dir?.createFile(format.mimeType, outputFileName)
        if (outputFile == null) {
            Toast.makeText(this, getString(R.string.ae_cannot_create_file, outputFileName), Toast.LENGTH_LONG).show()
            return
        }

        val tempOutput = File(cacheDir, "export_temp_${timestamp}.${format.extension}")

        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.max = 100
        binding.progressBar.progress = 0
        binding.tvStatus.text = getString(R.string.ae_exporting, format.displayName)
        binding.tvStatus.visibility = View.VISIBLE
        setExportButtonsEnabled(false)

        scope.launch {
            try {
                val enc = AudioEncoder()
                encoder = enc

                // Read export settings from UI
                val sampleRateStr = binding.spinnerSampleRate.text.toString()
                val targetSampleRate = when {
                    sampleRateStr.startsWith("44100") -> 44100
                    sampleRateStr.startsWith("48000") -> 48000
                    sampleRateStr.startsWith("22050") -> 22050
                    sampleRateStr.startsWith("16000") -> 16000
                    else -> 0  // keep original
                }

                val isLossless = format == AudioEncoder.OutputFormat.FLAC || format == AudioEncoder.OutputFormat.WAV
                val bitrateModeText = binding.spinnerBitrateMode.text.toString()
                val isVbr = bitrateModeText == getString(R.string.ae_vbr)
                val sliderValue = binding.sliderBitrate.value.toInt()

                // CBR: slider = kbps (64-320) 鈫?bitrate in bps
                // VBR MP3/M4A: slider = quality index 鈫?vbrQuality
                // VBR Opus: slider = kbps (32-256) 鈫?bitrate in bps
                val bitrateBps: Int
                val vbrQuality: Int
                if (isVbr) {
                    when (format) {
                        AudioEncoder.OutputFormat.OPUS -> {
                            bitrateBps = sliderValue * 1000
                            vbrQuality = sliderValue
                        }
                        else -> {
                            bitrateBps = 192000 // fallback, not used for VBR
                            vbrQuality = sliderValue
                        }
                    }
                } else {
                    bitrateBps = sliderValue * 1000
                    vbrQuality = 4
                }

                val config = AudioEncoder.EncodeConfig(
                    format = format,
                    bitrate = bitrateBps,
                    vbrQuality = vbrQuality,
                    bitrateMode = if (isLossless) AudioEncoder.BitrateMode.CBR else if (isVbr) AudioEncoder.BitrateMode.VBR else AudioEncoder.BitrateMode.CBR,
                    sampleRate = targetSampleRate,
                    fadeInSec = fadeIn,
                    fadeOutSec = fadeOut
                )

                val selectionDurationMs = endMs - startMs
                val result = withContext(Dispatchers.IO) {
                    decoded.samples.rewind()
                    enc.encode(
                        samples = decoded.samples,
                        sampleRate = decoded.sampleRate,
                        channels = decoded.channels,
                        numSamples = decoded.numSamples,
                        startTimeSec = startMs / 1000f,
                        endTimeSec = endMs / 1000f,
                        outputPath = tempOutput.absolutePath,
                        config = config
                    )
                }

                if (result.success) {
                    contentResolver.openOutputStream(outputFile.uri)?.use { out ->
                        tempOutput.inputStream().use { it.copyTo(out) }
                    }
                    tempOutput.delete()
                    LogCollector.i(TAG, "performExport: success -> $outputFileName (${result.durationMs}ms)")

                    val fullPath = resolveExportPath(dirUri, outputFileName)
                    binding.tvStatus.text = getString(R.string.ae_saved, fullPath)
                    Toast.makeText(this@AudioEditorActivity,
                        getString(R.string.ae_saved, fullPath), Toast.LENGTH_LONG).show()
                } else {
                    tempOutput.delete()
                    LogCollector.e(TAG, "performExport: FFmpeg failed: ${result.errorMessage}")
                    binding.tvStatus.text = result.errorMessage
                    Toast.makeText(this@AudioEditorActivity, getString(R.string.ae_export_failed), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "performExport exception: ${e.javaClass.simpleName}: ${e.message}", e)
                tempOutput.delete()
                binding.tvStatus.text = getString(R.string.ae_export_error, e.message ?: "")
                Toast.makeText(this@AudioEditorActivity, getString(R.string.ae_export_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                setExportButtonsEnabled(true)
            }
        }
    }

    private fun resolveExportPath(dirUri: Uri, fileName: String): String {
        // Try to get a readable path from the tree URI
        val path = dirUri.path ?: return fileName
        // Typical tree URI: /tree/primary:Documents/sub -> /storage/emulated/0/Documents/sub
        val treePart = path.removePrefix("/tree/").replace(":", "/")
        val storagePath = if (treePart.startsWith("primary/")) {
            "/storage/emulated/0/${treePart.removePrefix("primary/")}"
        } else {
            "/storage/$treePart"
        }
        return "$storagePath/$fileName"
    }

    private fun setExportButtonsEnabled(enabled: Boolean) {
        binding.btnExport.isEnabled = enabled
        binding.spinnerExportFormat.isEnabled = enabled
        binding.spinnerSampleRate.isEnabled = enabled
        binding.spinnerBitrateMode.isEnabled = enabled
        binding.sliderBitrate.isEnabled = enabled
    }

    private fun updateExportSettingsVisibility() {
        val formatIndex = AudioEncoder.OutputFormat.entries.map { "${it.displayName} (.${it.extension})" }
            .indexOf(binding.spinnerExportFormat.text.toString())
        val format = AudioEncoder.OutputFormat.entries.getOrElse(formatIndex) { AudioEncoder.OutputFormat.MP3 }
        val isLossless = format == AudioEncoder.OutputFormat.FLAC || format == AudioEncoder.OutputFormat.WAV

        binding.layoutBitrateMode.visibility = if (isLossless) View.GONE else View.VISIBLE
        binding.layoutBitrate.visibility = if (isLossless) View.GONE else View.VISIBLE
        updateBitrateSlider()
    }

    private fun updateBitrateSlider() {
        val modeText = binding.spinnerBitrateMode.text.toString()
        val isVbr = modeText == getString(R.string.ae_vbr)

        val formatIndex = AudioEncoder.OutputFormat.entries.map { "${it.displayName} (.${it.extension})" }
            .indexOf(binding.spinnerExportFormat.text.toString())
        val format = AudioEncoder.OutputFormat.entries.getOrElse(formatIndex) { AudioEncoder.OutputFormat.MP3 }

        // Must set valueFrom/valueTo before setting value to avoid IllegalArgumentException
        if (isVbr) {
            binding.tvBitrateLabel.text = getString(R.string.ae_quality)
            when (format) {
                AudioEncoder.OutputFormat.MP3 -> {
                    binding.sliderBitrate.valueFrom = 0f
                    binding.sliderBitrate.valueTo = 9f
                    binding.sliderBitrate.stepSize = 1f
                    binding.sliderBitrate.value = 4f
                    binding.tvBitrateValue.text = "4 (鈮?75 kbps)"
                }
                AudioEncoder.OutputFormat.M4A -> {
                    binding.sliderBitrate.valueFrom = 1f
                    binding.sliderBitrate.valueTo = 5f
                    binding.sliderBitrate.stepSize = 1f
                    binding.sliderBitrate.value = 3f
                    binding.tvBitrateValue.text = "3"
                }
                AudioEncoder.OutputFormat.OPUS -> {
                    binding.sliderBitrate.valueFrom = 32f
                    binding.sliderBitrate.valueTo = 256f
                    binding.sliderBitrate.stepSize = 16f
                    binding.sliderBitrate.value = 128f
                    binding.tvBitrateValue.text = "128 kbps"
                }
                else -> {}
            }
        } else {
            binding.tvBitrateLabel.text = getString(R.string.ae_bitrate)
            binding.sliderBitrate.stepSize = 16f
            binding.sliderBitrate.valueFrom = 64f
            binding.sliderBitrate.valueTo = 320f
            binding.sliderBitrate.value = 192f
            binding.tvBitrateValue.text = "192 kbps"
        }
    }

    private fun updateDisplay() {
        binding.waveformView.setParameters(startPos, endPos, offset)
        binding.waveformView.invalidate()

        binding.tvStartTime.text = formatTime(binding.waveformView.pixelsToMillisecs(startPos))

        // Total duration from decoded audio 鈥?stays correct after splice
        val decoded = decodedAudio
        if (decoded != null && decoded.sampleRate > 0) {
            val totalMs = (decoded.numSamples.toDouble() / decoded.sampleRate * 1000).toInt()
            binding.tvEndTime.text = formatTime(totalMs)
        } else {
            binding.tvEndTime.text = formatTime(binding.waveformView.pixelsToMillisecs(endPos))
        }

        // Show selection info during drag or after selection is finalized
        val showSelection = hasValidSelection ||
            (isDraggingSelection && Math.abs(selectionEndPx - selectionStartPx) > 5) ||
            (isAdjustingBoundary && hasValidSelection)

        binding.btnExport.text = if (showSelection) getString(R.string.ae_export_selection) else getString(R.string.ae_export_label)

        if (showSelection) {
            binding.selectionActionBar.visibility = View.VISIBLE
            binding.selectionTimeLayout.visibility = View.VISIBLE
            val lo = minOf(selectionStartPx, selectionEndPx)
            val hi = maxOf(selectionStartPx, selectionEndPx)
            val selStartMs = binding.waveformView.pixelsToMillisecs(lo)
            val selEndMs = binding.waveformView.pixelsToMillisecs(hi)
            binding.tvSelectionDuration.text = "⏱ ${formatTime(selEndMs - selStartMs)}"
            binding.tvSelStartTime.text = formatTime(selStartMs)
            binding.tvSelEndTime.text = formatTime(selEndMs)
        } else {
            binding.selectionActionBar.visibility = View.GONE
            binding.selectionTimeLayout.visibility = View.GONE
        }
    }

    private fun formatTime(msec: Int): String {
        val totalSec = msec / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val ms = msec % 1000
        return String.format("%02d:%02d.%03d", min, sec, ms)
    }

    // === Undo ===

    private fun pushUndo() {
        undoStack.addLast(UndoState(startPos, endPos, selectionStartPx, selectionEndPx))
        // Limit stack size
        if (undoStack.size > 20) undoStack.removeFirst()
    }

    private fun performUndo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, getString(R.string.ae_nothing_to_undo), Toast.LENGTH_SHORT).show()
            return
        }
        val state = undoStack.removeLast()
        startPos = state.startPos
        endPos = state.endPos
        selectionStartPx = state.selStart
        selectionEndPx = state.selEnd
        if (!hasValidSelection) {
            binding.waveformView.clearSelection()
            stopLoopPlayback()
        } else {
            binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
        }
        updateDisplay()
        Toast.makeText(this, getString(R.string.ae_undone), Toast.LENGTH_SHORT).show()
    }

    // === PCM Splice ===

    private fun splicePcm(delStartMs: Int, delEndMs: Int, keepBefore: Boolean) {
        LogCollector.i(TAG, "splicePcm: del=${delStartMs}-${delEndMs}ms, keepBefore=$keepBefore")
        val decoded = decodedAudio ?: return
        val sr = decoded.sampleRate
        val ch = decoded.channels
        val delStartSample = (delStartMs / 1000f * sr).toInt().coerceIn(0, decoded.numSamples)
        val delEndSample = (delEndMs / 1000f * sr).toInt().coerceIn(0, decoded.numSamples)
        LogCollector.i(TAG, "splicePcm: samples ${delStartSample}-${delEndSample} of ${decoded.numSamples}")

        if (delStartSample >= delEndSample) return

        val beforeCount = delStartSample
        val afterCount = decoded.numSamples - delEndSample
        val newNumSamples = if (keepBefore) beforeCount else afterCount

        if (newNumSamples <= 0) {
            Toast.makeText(this, getString(R.string.ae_delete_all_warning), Toast.LENGTH_SHORT).show()
            return
        }

        // Allocate new buffer
        val newBuf = ShortArray(newNumSamples * ch)
        decoded.samples.rewind()

        if (keepBefore) {
            // Copy samples before deletion
            for (i in 0 until beforeCount * ch) {
                if (decoded.samples.remaining() > 0) newBuf[i] = decoded.samples.get()
            }
        } else {
            // Skip to after deletion, copy samples after
            decoded.samples.position(delEndSample * ch)
            for (i in 0 until afterCount * ch) {
                if (decoded.samples.remaining() > 0) newBuf[i] = decoded.samples.get()
            }
        }

        val newShortBuf = ShortBuffer.wrap(newBuf)
        val newDecoded = AudioDecoder.DecodedAudio(
            samples = newShortBuf,
            sampleRate = sr,
            channels = ch,
            numSamples = newNumSamples,
            avgBitrateKbps = decoded.avgBitrateKbps,
            fileType = decoded.fileType,
            fileSize = decoded.fileSize
        )
        decodedAudio = newDecoded

        // Recompute waveform
        newShortBuf.rewind()
        val waveform = WaveformProcessor.process(newShortBuf, ch, newNumSamples)
        newShortBuf.rewind()

        // Update player
        player?.release()
        player = AudioPlayer(newDecoded)

        // Update UI
        binding.waveformView.setData(waveform, sr)
        binding.waveformView.recomputeHeights(density)
        startPos = 0
        endPos = binding.waveformView.maxPos()
        offset = 0
        // 拼接后：音频已重置，选区失效，回到默认全选状态，红色光标在 0
        selectionStartPx = 0
        selectionEndPx = endPos
        cursorPos = 0
        currentPlaybackEndMs = 0
        updateEditActionsState()
        binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
        binding.waveformView.setPlayback(0)
        stopLoopPlayback()
        updateDisplay()
    }

    // === WaveformListener ===

    override fun waveformTouchStart(x: Float) {
        // 播放中禁止波形交互，简化状态管理
        if (isPlaying) {
            Toast.makeText(this, getString(R.string.ae_pause_first), Toast.LENGTH_SHORT).show()
            return
        }
        val pos = binding.waveformView.getOffset() + x.toInt()
        touchDownPos = pos

        if (hasValidSelection) {
            val distToStart = Math.abs(pos - selectionStartPx)
            val distToEnd = Math.abs(pos - selectionEndPx)
            when {
                distToStart < boundaryTouchThreshold && distToStart <= distToEnd -> {
                    isAdjustingBoundary = true
                    selectionStartPx = pos.coerceIn(0, selectionEndPx - 1)
                    binding.waveformView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                distToEnd < boundaryTouchThreshold -> {
                    isAdjustingBoundary = true
                    selectionEndPx = pos.coerceIn(selectionStartPx + 1, binding.waveformView.maxPos())
                    binding.waveformView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                pos in selectionStartPx..selectionEndPx -> {
                    // Touch inside selection — wait to see if user drags (new selection) or taps (set cursor)
                    isPendingInsideSelection = true
                    isDraggingSelection = false
                    isAdjustingBoundary = false
                }
                else -> {
                    // Touch outside selection 鈥?clear existing selection, start new one
                    clearSelectionState()
            updateEditActionsState()
                    binding.waveformView.clearSelection()
                    isDraggingSelection = true
                    selectionStartPx = pos
                    selectionEndPx = pos
                    binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
                }
            }
        } else {
            isDraggingSelection = true
            selectionStartPx = pos
            selectionEndPx = pos
            binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
        }
        updateDisplay()
    }

    override fun waveformTouchMove(x: Float) {
        if (isPlaying) return
        val pos = binding.waveformView.getOffset() + x.toInt()

        // 选区内触摸后滑动超过阈值 → 清除旧选区，开始新选区拖拽
        if (isPendingInsideSelection && Math.abs(pos - touchDownPos) > boundaryTouchThreshold) {
            isPendingInsideSelection = false
            clearSelectionState()
            updateEditActionsState()
            binding.waveformView.clearSelection()
            isDraggingSelection = true
            selectionStartPx = touchDownPos
            selectionEndPx = touchDownPos
        }

        if (isAdjustingBoundary) {
            if (Math.abs(pos - selectionStartPx) < Math.abs(pos - selectionEndPx)) {
                selectionStartPx = pos.coerceIn(0, selectionEndPx - 1)
            } else {
                selectionEndPx = pos.coerceIn(selectionStartPx + 1, binding.waveformView.maxPos())
            }
        } else if (isDraggingSelection) {
            if (pos >= touchDownPos) {
                selectionStartPx = touchDownPos
                selectionEndPx = pos.coerceIn(0, binding.waveformView.maxPos())
            } else {
                selectionStartPx = pos.coerceIn(0, binding.waveformView.maxPos())
                selectionEndPx = touchDownPos
            }
        }
        binding.waveformView.setSelection(selectionStartPx, selectionEndPx)
        // 拖选过程中实时同步光标到选区起点
        if (isDraggingSelection) {
            cursorPos = minOf(selectionStartPx, selectionEndPx)
            binding.waveformView.setPlayback(cursorPos)
        }
        updateDisplay()
    }

    override fun waveformTouchEnd() {
        if (isPlaying) return
        if (isAdjustingBoundary) {
            isAdjustingBoundary = false
            // Don't auto-play after boundary adjustment
        } else if (isPendingInsideSelection) {
            // Tap inside selection — set cursor to tap position, keep selection
            isPendingInsideSelection = false
            cursorPos = touchDownPos
            val tapMs = binding.waveformView.pixelsToMillisecs(cursorPos)
            player?.seekTo(tapMs)
            currentPlaybackEndMs = if (hasValidSelection) {
                binding.waveformView.pixelsToMillisecs(selectionEndPx)
            } else {
                binding.waveformView.pixelsToMillisecs(endPos)
            }
            binding.waveformView.setPlayback(cursorPos)
            binding.waveformView.invalidate()
            binding.tvCurrentTime.text = formatTime(tapMs)
        } else if (isDraggingSelection) {
            isDraggingSelection = false
            val distance = Math.abs(selectionEndPx - selectionStartPx)
            if (distance > 5) {
                updateEditActionsState()
                if (selectionEndPx < selectionStartPx) {
                    val tmp = selectionStartPx; selectionStartPx = selectionEndPx; selectionEndPx = tmp
                }
                // 光标同步到选区起点
                cursorPos = selectionStartPx
                binding.waveformView.setPlayback(cursorPos)
                binding.waveformView.invalidate()
                binding.tvCurrentTime.text = formatTime(binding.waveformView.pixelsToMillisecs(cursorPos))
            } else {
                if (hasValidSelection && touchDownPos in selectionStartPx..selectionEndPx) {
                    return
                }
                // Tap: move playback position indicator to tap point, no auto-play
                clearSelectionState()
            updateEditActionsState()
                binding.waveformView.clearSelection()
                if (isPlaying) {
                    pausePlayback()
                }
                cursorPos = selectionStartPx
                val tapMs = binding.waveformView.pixelsToMillisecs(cursorPos)
                player?.seekTo(tapMs)
                // 同步播放终点：有选区用选区尾，无选区用音频末尾
                currentPlaybackEndMs = if (hasValidSelection) {
                    binding.waveformView.pixelsToMillisecs(selectionEndPx)
                } else {
                    binding.waveformView.pixelsToMillisecs(endPos)
                }
                binding.waveformView.setPlayback(cursorPos)
                binding.waveformView.invalidate()
                binding.tvCurrentTime.text = formatTime(tapMs)
            }
            updateDisplay()
        }
    }

    override fun waveformLongPress(pos: Int) {
        if (!hasValidSelection || pos !in selectionStartPx..selectionEndPx) {
            return
        }
        showSegmentActionMenu()
    }

    override fun waveformFling(vx: Float) {
        handler.post {
            offset = binding.waveformView.getOffset() - (vx / 3).toInt()
            if (offset + binding.waveformView.measuredWidth > binding.waveformView.maxPos()) {
                offset = binding.waveformView.maxPos() - binding.waveformView.measuredWidth
            }
            if (offset < 0) offset = 0
            updateDisplay()
        }
    }

    override fun waveformDraw() {
        // Sync offset from view 鈥?keeps Activity in sync with scrollbar drags
        offset = binding.waveformView.getOffset()
        if (!isPlaying) {
            // Show cursor position time if set, otherwise show left edge time
            val pos = if (cursorPos >= 0) cursorPos else offset
            val currentMs = binding.waveformView.pixelsToMillisecs(pos)
            binding.tvCurrentTime.text = formatTime(currentMs)
        }
    }

    override fun waveformZoomIn() {
        binding.waveformView.zoomIn()
    }

    override fun waveformZoomOut() {
        binding.waveformView.zoomOut()
    }

    // === Selection / Loop / Segment Actions ===

    private fun startLoopPlayback() {
        player?.let { p ->
            val startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
            val endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
            p.setLooping(true)
            p.setPlaybackRange(startMs, endMs)
            if (!isPlaying) {
                p.start()
                isPlaying = true
                binding.btnPlay.setImageResource(R.drawable.ic_pause)
                postPlayPositionPoller()
            }
            isLoopingSelection = true
        }
    }

    private fun stopLoopPlayback() {
        isLoopingSelection = false
        player?.setLooping(false)
        if (isPlaying) {
            pausePlayback()
        }
    }

    private fun showSegmentActionMenu() {
        val startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
        val endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
        val durationMs = endMs - startMs

        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_segment_action, null)

        view.findViewById<android.widget.TextView>(R.id.tvSelectionInfo).text =
            getString(R.string.ae_selection_info, formatTime(startMs), formatTime(endMs), formatTime(durationMs))

        view.findViewById<View>(R.id.action_delete_selected).setOnClickListener {
            performDeleteSelected(startMs, endMs)
            bottomSheet.dismiss()
        }

        view.findViewById<View>(R.id.action_delete_others).setOnClickListener {
            performKeepOnly(startMs, endMs)
            bottomSheet.dismiss()
        }

        view.findViewById<View>(R.id.action_cancel).setOnClickListener {
            bottomSheet.dismiss()
        }

        bottomSheet.setContentView(view)
        bottomSheet.show()
    }

    private fun performDeleteSelected(delStartMs: Int, delEndMs: Int) {
        val currentStartMs = binding.waveformView.pixelsToMillisecs(startPos)
        val currentEndMs = binding.waveformView.pixelsToMillisecs(endPos)

        if (delStartMs <= currentStartMs && delEndMs >= currentEndMs) {
            Toast.makeText(this, getString(R.string.ae_delete_all_warning), Toast.LENGTH_SHORT).show()
            return
        }

        pushUndo()

        // If selection is at start or end, just adjust range (no PCM splice needed)
        if (delStartMs <= currentStartMs) {
            startPos = binding.waveformView.millisecsToPixels(delEndMs)
            clearSelectionState()
            updateEditActionsState()
            binding.waveformView.clearSelection()
            stopLoopPlayback()
            updateDisplay()
        } else if (delEndMs >= currentEndMs) {
            endPos = binding.waveformView.millisecsToPixels(delStartMs)
            clearSelectionState()
            updateEditActionsState()
            binding.waveformView.clearSelection()
            stopLoopPlayback()
            updateDisplay()
        } else {
            // Middle deletion: splice PCM (keep the part before deletion)
            splicePcm(delStartMs, delEndMs, keepBefore = true)
        }

        Toast.makeText(this, getString(R.string.ae_deleted_selected), Toast.LENGTH_SHORT).show()
    }

    private fun performKeepOnly(keepStartMs: Int, keepEndMs: Int) {
        pushUndo()

        // If selection covers full range, just adjust
        val currentStartMs = binding.waveformView.pixelsToMillisecs(startPos)
        val currentEndMs = binding.waveformView.pixelsToMillisecs(endPos)

        if (keepStartMs <= currentStartMs && keepEndMs >= currentEndMs) {
            // Keeping everything, no-op
            undoStack.removeLast()
            return
        }

        if (keepStartMs <= currentStartMs) {
            // Keep from start to keepEnd
            endPos = binding.waveformView.millisecsToPixels(keepEndMs)
            clearSelectionState()
            updateEditActionsState()
            binding.waveformView.clearSelection()
            stopLoopPlayback()
            updateDisplay()
        } else if (keepEndMs >= currentEndMs) {
            // Keep from keepStart to end
            startPos = binding.waveformView.millisecsToPixels(keepStartMs)
            clearSelectionState()
            updateEditActionsState()
            binding.waveformView.clearSelection()
            stopLoopPlayback()
            updateDisplay()
        } else {
            // Middle keep: splice PCM 鈥?delete the part before keepStart and after keepEnd
            // First delete after keepEnd (keep the part before keepEnd = the full range up to keepEnd)
            splicePcm(keepEndMs, currentEndMs, keepBefore = true)
            // Then adjust startPos
            startPos = binding.waveformView.millisecsToPixels(keepStartMs - currentStartMs)
            updateDisplay()
        }

        Toast.makeText(this, getString(R.string.ae_deleted_others), Toast.LENGTH_SHORT).show()
    }

    // === Menu ===

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        activePlayRunnable?.let { handler.removeCallbacks(it) }
        player?.release()
        scope.cancel()
        audioFile?.let { if (it.exists() && it.absolutePath.contains(cacheDir.absolutePath)) it.delete() }
    }
}
