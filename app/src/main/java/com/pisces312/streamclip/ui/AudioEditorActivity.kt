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
import com.pisces312.streamclip.audio.WaveformProcessor
import com.pisces312.streamclip.audio.WaveformView
import com.pisces312.streamclip.databinding.ActivityAudioEditorBinding
import com.pisces312.streamclip.util.LogCollector
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
        const val MODE_EDIT = "edit"
        const val MODE_RECORD = "record"
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
    private var offset = 0
    private var density = 0f
    private var audioFile: File? = null
    private var pendingExportFormat: AudioEncoder.OutputFormat? = null

    // Selection state
    private var hasSelection = false
    private var selectionStartPx = 0
    private var selectionEndPx = 0
    private var isLoopingSelection = false
    private var touchDownPos = 0
    private var isDraggingSelection = false
    private var isAdjustingBoundary = false
    private var boundaryTouchThreshold = 30f

    // Undo stack: (startPos, endPos, hasSelection)
    private val undoStack = ArrayDeque<Triple<Int, Int, Boolean>>()

    private val pickDirLauncher: ActivityResultLauncher<Uri?> = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* 忽略 */ }

            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putString(KEY_LAST_EXPORT_DIR, treeUri.toString()).apply()

            val format = pendingExportFormat ?: return@registerForActivityResult
            performExport(treeUri, format)
        }
        pendingExportFormat = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updatePlayPosition = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.isPlaying()) {
                    val pos = binding.waveformView.millisecsToPixels(p.getCurrentPosition())
                    binding.waveformView.setPlayback(pos)
                    binding.waveformView.invalidate()
                    binding.tvCurrentTime.text = formatTime(p.getCurrentPosition())
                    handler.postDelayed(this, 50)
                }
            }
        }
    }

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
        if (audioUriStr != null) {
            loadAudio(Uri.parse(audioUriStr))
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
            binding.waveformView.setPlayback(0)
            binding.waveformView.invalidate()
        }

        binding.btnFfwd.setOnClickListener {
            val endMs = binding.waveformView.pixelsToMillisecs(endPos)
            player?.seekTo(endMs)
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

        binding.btnExportMp3.setOnClickListener {
            exportAudio(AudioEncoder.OutputFormat.MP3)
        }

        binding.btnExportM4A.setOnClickListener {
            exportAudio(AudioEncoder.OutputFormat.M4A)
        }

        binding.btnExportFlac.setOnClickListener {
            exportAudio(AudioEncoder.OutputFormat.FLAC)
        }

        binding.btnExportWav.setOnClickListener {
            exportAudio(AudioEncoder.OutputFormat.WAV)
        }

        // Selection action bar
        binding.btnDeleteSelected.setOnClickListener {
            if (hasSelection) {
                val startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
                val endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
                performDeleteSelected(startMs, endMs)
            }
        }

        binding.btnKeepOnly.setOnClickListener {
            if (hasSelection) {
                val startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
                val endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
                performKeepOnly(startMs, endMs)
            }
        }

        binding.btnUndo.setOnClickListener {
            performUndo()
        }
    }

    private fun loadAudio(uri: Uri) {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE
        binding.tvLoadingText.text = getString(R.string.ae_decoding)
        LogCollector.i(TAG, "loadAudio: uri=$uri")

        scope.launch {
            try {
                val file = copyUriToCache(uri)
                audioFile = file
                LogCollector.i(TAG, "loadAudio: cached file=${file.absolutePath}, size=${file.length()}")

                binding.tvLoadingPercent.text = ""
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.max = 100
                binding.progressBar.progress = 0

                val decoder = AudioDecoder()
                val decoded = withContext(Dispatchers.IO) {
                    decoder.decode(file.absolutePath, object : AudioDecoder.ProgressListener {
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
                LogCollector.i(TAG, "loadAudio: decoded samples=${decoded.numSamples}, rate=${decoded.sampleRate}, ch=${decoded.channels}, bitrate=${decoded.avgBitrateKbps}kbps")

                val waveform = withContext(Dispatchers.Default) {
                    WaveformProcessor.process(decoded.samples, decoded.channels, decoded.numSamples)
                }
                LogCollector.i(TAG, "loadAudio: waveform frames=${waveform.numFrames}, zoomLevels=${waveform.numZoomLevels}")

                decoded.samples.rewind()
                player = AudioPlayer(decoded)
                LogCollector.i(TAG, "loadAudio: player created")

                binding.waveformView.setData(waveform, decoded.sampleRate)
                binding.waveformView.recomputeHeights(density)

                startPos = 0
                endPos = binding.waveformView.maxPos()
                offset = 0
                updateDisplay()
                LogCollector.i(TAG, "loadAudio: display updated, maxPos=${endPos}")

                val durationMs = (decoded.numSamples.toDouble() / decoded.sampleRate * 1000).toInt()
                val channelsStr = if (decoded.channels == 1) getString(R.string.ae_mono) else getString(R.string.ae_stereo)
                val info = "${file.name}  |  ${formatTime(durationMs)}  |  ${decoded.sampleRate}Hz  |  $channelsStr  |  ${decoded.avgBitrateKbps}kbps"
                binding.tvFileInfo.text = info

                binding.progressBar.visibility = View.GONE
                binding.loadingLayout.visibility = View.GONE
                binding.contentLayout.visibility = View.VISIBLE
                LogCollector.i(TAG, "loadAudio: success")

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

    private fun copyUriToCache(uri: Uri): File {
        val docName = DocumentFile.fromSingleUri(this, uri)?.name
        val fileName = docName ?: "audio_${System.currentTimeMillis()}"
        LogCollector.i(TAG, "copyUriToCache: uri=$uri, docName=$docName, fileName=$fileName")
        val cacheFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")
        LogCollector.i(TAG, "copyUriToCache: done, size=${cacheFile.length()}")
        return cacheFile
    }

    private fun startPlayback() {
        player?.let { p ->
            val startMs: Int
            val endMs: Int
            if (hasSelection) {
                startMs = binding.waveformView.pixelsToMillisecs(selectionStartPx)
                endMs = binding.waveformView.pixelsToMillisecs(selectionEndPx)
                p.setLooping(true)
                isLoopingSelection = true
            } else {
                startMs = binding.waveformView.pixelsToMillisecs(startPos)
                endMs = binding.waveformView.pixelsToMillisecs(endPos)
                p.setLooping(false)
                isLoopingSelection = false
            }
            p.setPlaybackRange(startMs, endMs)
            p.start()
            isPlaying = true
            binding.btnPlay.setImageResource(R.drawable.ic_pause)
            handler.post(updatePlayPosition)
        }
    }

    private fun pausePlayback() {
        player?.pause()
        isPlaying = false
        binding.btnPlay.setImageResource(R.drawable.ic_play)
        handler.removeCallbacks(updatePlayPosition)
    }

    private fun exportAudio(format: AudioEncoder.OutputFormat) {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val lastDirStr = prefs.getString(KEY_LAST_EXPORT_DIR, null)

        if (lastDirStr != null) {
            val dirUri = Uri.parse(lastDirStr)
            val docFile = DocumentFile.fromTreeUri(this, dirUri)
            if (docFile != null && docFile.canWrite()) {
                performExport(dirUri, format)
                return
            }
        }

        pendingExportFormat = format
        pickDirLauncher.launch(null)
    }

    private fun performExport(dirUri: Uri, format: AudioEncoder.OutputFormat) {
        val decoded = decodedAudio ?: return
        val startMs = binding.waveformView.pixelsToMillisecs(startPos)
        val endMs = binding.waveformView.pixelsToMillisecs(endPos)
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
                val config = AudioEncoder.EncodeConfig(
                    format = format,
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

                    binding.tvStatus.text = getString(R.string.ae_saved, outputFileName)
                    Toast.makeText(this@AudioEditorActivity,
                        getString(R.string.ae_saved, outputFileName), Toast.LENGTH_LONG).show()
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

    private fun setExportButtonsEnabled(enabled: Boolean) {
        binding.btnExportMp3.isEnabled = enabled
        binding.btnExportM4A.isEnabled = enabled
        binding.btnExportFlac.isEnabled = enabled
        binding.btnExportWav.isEnabled = enabled
    }

    private fun updateDisplay() {
        binding.waveformView.setParameters(startPos, endPos, offset)
        binding.waveformView.invalidate()

        binding.tvStartTime.text = formatTime(binding.waveformView.pixelsToMillisecs(startPos))
        binding.tvEndTime.text = formatTime(binding.waveformView.pixelsToMillisecs(endPos))

        // Update export button text based on selection
        val exportLabel = if (hasSelection) getString(R.string.ae_export_selection) else getString(R.string.ae_export_all)
        binding.btnExportMp3.text = "$exportLabel (MP3)"
        binding.btnExportM4A.text = "$exportLabel (M4A)"
        binding.btnExportFlac.text = "$exportLabel (FLAC)"
        binding.btnExportWav.text = "$exportLabel (WAV)"

        // Update selection action bar visibility
        binding.selectionActionBar.visibility = if (hasSelection) View.VISIBLE else View.GONE
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
        undoStack.addLast(Triple(startPos, endPos, hasSelection))
        // Limit stack size
        if (undoStack.size > 20) undoStack.removeFirst()
    }

    private fun performUndo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, getString(R.string.ae_nothing_to_undo), Toast.LENGTH_SHORT).show()
            return
        }
        val (savedStart, savedEnd, savedSelection) = undoStack.removeLast()
        startPos = savedStart
        endPos = savedEnd
        hasSelection = savedSelection
        if (!hasSelection) {
            binding.waveformView.clearHighlight()
            stopLoopPlayback()
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
        hasSelection = false
        binding.waveformView.clearHighlight()
        stopLoopPlayback()
        updateDisplay()
    }

    // === WaveformListener ===

    override fun waveformTouchStart(x: Float) {
        val pos = binding.waveformView.getOffset() + x.toInt()
        touchDownPos = pos

        if (hasSelection) {
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
                    isDraggingSelection = false
                    isAdjustingBoundary = false
                }
                else -> {
                    isDraggingSelection = true
                    selectionStartPx = pos
                    selectionEndPx = pos
                    binding.waveformView.setHighlight(selectionStartPx, selectionEndPx)
                }
            }
        } else {
            isDraggingSelection = true
            selectionStartPx = pos
            selectionEndPx = pos
            binding.waveformView.setHighlight(selectionStartPx, selectionEndPx)
        }
        updateDisplay()
    }

    override fun waveformTouchMove(x: Float) {
        val pos = binding.waveformView.getOffset() + x.toInt()
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
        binding.waveformView.setHighlight(selectionStartPx, selectionEndPx)
        updateDisplay()
    }

    override fun waveformTouchEnd() {
        if (isAdjustingBoundary) {
            isAdjustingBoundary = false
            startLoopPlayback()
        } else if (isDraggingSelection) {
            isDraggingSelection = false
            val distance = Math.abs(selectionEndPx - selectionStartPx)
            if (distance > 5) {
                hasSelection = true
                if (selectionEndPx < selectionStartPx) {
                    val tmp = selectionStartPx; selectionStartPx = selectionEndPx; selectionEndPx = tmp
                }
                startLoopPlayback()
            } else {
                if (hasSelection && touchDownPos in selectionStartPx..selectionEndPx) {
                    return
                }
                hasSelection = false
                binding.waveformView.clearHighlight()
                stopLoopPlayback()
                player?.seekTo(binding.waveformView.pixelsToMillisecs(selectionStartPx))
            }
            updateDisplay()
        }
    }

    override fun waveformLongPress(pos: Int) {
        if (!hasSelection || pos !in selectionStartPx..selectionEndPx) {
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
        if (!isPlaying) {
            val currentMs = binding.waveformView.pixelsToMillisecs(
                binding.waveformView.getOffset()
            )
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
                handler.post(updatePlayPosition)
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
            hasSelection = false
            binding.waveformView.clearHighlight()
            stopLoopPlayback()
            updateDisplay()
        } else if (delEndMs >= currentEndMs) {
            endPos = binding.waveformView.millisecsToPixels(delStartMs)
            hasSelection = false
            binding.waveformView.clearHighlight()
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
            hasSelection = false
            binding.waveformView.clearHighlight()
            stopLoopPlayback()
            updateDisplay()
        } else if (keepEndMs >= currentEndMs) {
            // Keep from keepStart to end
            startPos = binding.waveformView.millisecsToPixels(keepStartMs)
            hasSelection = false
            binding.waveformView.clearHighlight()
            stopLoopPlayback()
            updateDisplay()
        } else {
            // Middle keep: splice PCM — delete the part before keepStart and after keepEnd
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
        handler.removeCallbacks(updatePlayPosition)
        player?.release()
        scope.cancel()
        audioFile?.let { if (it.exists() && it.absolutePath.contains(cacheDir.absolutePath)) it.delete() }
    }
}
