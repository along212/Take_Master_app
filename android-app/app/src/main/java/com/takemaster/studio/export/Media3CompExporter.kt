package com.takemaster.studio.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.takemaster.studio.audio.AudioAligner
import com.takemaster.studio.audio.CompAudioStitcher
import com.takemaster.studio.model.CompSegment
import com.takemaster.studio.model.ExportAudioMode
import com.takemaster.studio.model.ExportConfig
import com.takemaster.studio.model.ExportOutput
import com.takemaster.studio.model.TakeItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class ExportHandle(
    private val cancelAction: () -> Unit
) {
    fun cancel() = cancelAction()
}

@OptIn(UnstableApi::class)
object Media3CompExporter {

    fun exportComp(
        context: Context,
        takes: List<TakeItem>,
        segments: List<CompSegment>,
        config: ExportConfig,
        onProgress: (Int) -> Unit,
        onComplete: (ExportOutput) -> Unit,
        onError: (String) -> Unit
    ): ExportHandle? {
        val takeMap = takes.associateBy { it.id }
        val validSegments = segments.filter { seg ->
            val t = takeMap[seg.takeId] ?: return@filter false
            val s = max(seg.startMasterSec, t.trackStartOnMasterSec)
            val e = min(seg.endMasterSec, t.trackEndOnMasterSec)
            (e - s) >= 0.08
        }
        if (validSegments.isEmpty()) {
            onError("No valid segments to export.")
            return null
        }

        val refTake = takes.firstOrNull { it.hasVideo } ?: takes.first()
        val videoEffects = buildVideoEffects(refTake, config)

        // Strip audio from clipped video items; audio is stitched sample-accurately into one
        // continuous WAV with overlapping equal-power crossfades so cuts have 0ms silence gaps.
        val videoItems = validSegments.map { seg ->
            val take = takeMap.getValue(seg.takeId)
            val startLocalSec = (seg.startMasterSec - take.trackStartOnMasterSec).coerceIn(0.0, take.durationSec)
            val endLocalSec = (seg.endMasterSec - take.trackStartOnMasterSec).coerceIn(startLocalSec + 0.05, take.durationSec)
            val startMs = (startLocalSec * 1000.0).roundToLong()
            val endMs = (endLocalSec * 1000.0).roundToLong()

            val mediaItem = MediaItem.Builder()
                .setUri(take.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(true)
                .apply {
                    if (videoEffects.isNotEmpty()) {
                        setEffects(Effects(emptyList(), videoEffects))
                    }
                }
                .build()
        }

        val stamp = System.currentTimeMillis()
        val tmpVideoFile = File(context.cacheDir, "takemaster_export_$stamp.mp4").apply { if (exists()) delete() }
        val tmpWavFile = File(context.cacheDir, "takemaster_audio_$stamp.wav").apply { if (exists()) delete() }

        val mainHandler = Handler(Looper.getMainLooper())
        val cancelled = AtomicBoolean(false)
        var activeTransformer: Transformer? = null
        var isFinished = false

        val progressHolder = ProgressHolder()
        val progressRunnable = object : Runnable {
            override fun run() {
                if (isFinished || cancelled.get()) return
                val t = activeTransformer ?: return
                val state = t.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    // Reserve 0..4% for pre-rendering the sample-accurate audio WAV
                    onProgress((4 + (progressHolder.progress * 0.95f).toInt()).coerceIn(4, 99))
                }
                mainHandler.postDelayed(this, 150L)
            }
        }

        Thread({
            val sources = mutableMapOf<String, CompAudioStitcher.FilePcmSource>()
            try {
                if (cancelled.get()) return@Thread
                mainHandler.post { onProgress(1) }

                val lockedTake = if (config.audioMode == ExportAudioMode.LOCK_TO_TAKE) {
                    takeMap[config.lockedTakeId] ?: takes.firstOrNull { it.hasAudio }
                } else null

                val requiredTakes = if (lockedTake != null) {
                    listOf(lockedTake)
                } else {
                    validSegments.mapNotNull { takeMap[it.takeId] }.distinctBy { it.id }
                }

                for (take in requiredTakes) {
                    if (cancelled.get()) return@Thread
                    val pcmFile = AudioAligner.ensurePcmCacheFile(context, take)
                    if (pcmFile != null) {
                        sources[take.id] = CompAudioStitcher.FilePcmSource(pcmFile)
                    }
                }

                if (cancelled.get()) return@Thread
                mainHandler.post { onProgress(3) }

                val frameMap = CompAudioStitcher.buildFrameMap(validSegments, takes, CompAudioStitcher.SAMPLE_RATE)
                CompAudioStitcher.writeExportWav(
                    outWavFile = tmpWavFile,
                    takes = takes,
                    frameMap = frameMap,
                    sources = sources,
                    audioMode = config.audioMode,
                    lockedTakeId = config.lockedTakeId,
                    crossfadeMs = config.crossfadeMs,
                    sampleRate = CompAudioStitcher.SAMPLE_RATE
                )
            } catch (e: Exception) {
                tmpWavFile.delete()
                tmpVideoFile.delete()
                if (!cancelled.get()) {
                    mainHandler.post {
                        onError("Failed to prepare stitched audio: ${e.localizedMessage ?: "unknown error"}")
                    }
                }
                return@Thread
            } finally {
                sources.values.forEach { it.close() }
            }

            if (cancelled.get()) {
                tmpWavFile.delete()
                tmpVideoFile.delete()
                return@Thread
            }

            mainHandler.post {
                if (cancelled.get()) {
                    tmpWavFile.delete()
                    tmpVideoFile.delete()
                    return@post
                }
                onProgress(4)

                val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(tmpWavFile)))
                    .setRemoveVideo(true)
                    .build()

                val composition = Composition.Builder(
                    listOf(
                        EditedMediaItemSequence(videoItems),
                        EditedMediaItemSequence(listOf(audioItem))
                    )
                ).build()

                try {
                    val transformer = Transformer.Builder(context)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                isFinished = true
                                mainHandler.removeCallbacks(progressRunnable)
                                tmpWavFile.delete()
                                onProgress(99)
                                Thread {
                                    try {
                                        if (!tmpVideoFile.exists() || tmpVideoFile.length() == 0L) {
                                            tmpVideoFile.delete()
                                            mainHandler.post {
                                                onError("Export produced an empty file.")
                                            }
                                            return@Thread
                                        }
                                        val saved = saveToPublicGallery(context, tmpVideoFile)
                                        tmpVideoFile.delete()
                                        mainHandler.post {
                                            onProgress(100)
                                            onComplete(saved)
                                        }
                                    } catch (e: Exception) {
                                        tmpVideoFile.delete()
                                        mainHandler.post {
                                            onError("Failed to save to Gallery: ${e.localizedMessage ?: "unknown error"}")
                                        }
                                    }
                                }.start()
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                isFinished = true
                                mainHandler.removeCallbacks(progressRunnable)
                                tmpWavFile.delete()
                                tmpVideoFile.delete()
                                onError(
                                    exportException.localizedMessage
                                        ?: "Video export failed (${exportException.errorCodeName})"
                                )
                            }
                        })
                        .build()

                    activeTransformer = transformer
                    transformer.start(composition, tmpVideoFile.absolutePath)
                    mainHandler.postDelayed(progressRunnable, 150L)
                } catch (e: Exception) {
                    isFinished = true
                    tmpWavFile.delete()
                    tmpVideoFile.delete()
                    onError(e.localizedMessage ?: "Unable to start export")
                }
            }
        }, "TakeMaster-ExportPrep").start()

        return ExportHandle {
            cancelled.set(true)
            mainHandler.post {
                isFinished = true
                mainHandler.removeCallbacks(progressRunnable)
                try {
                    activeTransformer?.cancel()
                } catch (_: Exception) {
                }
                tmpWavFile.delete()
                tmpVideoFile.delete()
            }
        }
    }

    private fun buildVideoEffects(refTake: TakeItem, config: ExportConfig): List<Effect> {
        var targetW = refTake.displayWidth.coerceAtLeast(2)
        var targetH = refTake.displayHeight.coerceAtLeast(2)

        val maxShort = config.quality.maxShortSide
        if (maxShort != null) {
            val shortSide = min(targetW, targetH)
            if (shortSide > maxShort) {
                val scale = maxShort.toDouble() / shortSide.toDouble()
                targetW = ((targetW * scale).roundToInt() / 2) * 2
                targetH = ((targetH * scale).roundToInt() / 2) * 2
            }
        }
        // Ensure even dimensions required by hardware video encoders
        targetW = (targetW / 2) * 2
        targetH = (targetH / 2) * 2

        return listOf(
            Presentation.createForWidthAndHeight(
                targetW.coerceAtLeast(2),
                targetH.coerceAtLeast(2),
                Presentation.LAYOUT_SCALE_TO_FIT
            )
        )
    }

    private fun saveToPublicGallery(context: Context, sourceFile: File): ExportOutput {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "TakeMaster_$stamp.mp4"
        val relativeDir = "${Environment.DIRECTORY_MOVIES}/TakeMaster"
        val sizeBytes = sourceFile.length()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativeDir)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore rejected video insert.")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 256 * 1024)
                    }
                    out.flush()
                } ?: throw IllegalStateException("Unable to open output stream for Gallery video.")

                val doneValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                return ExportOutput(
                    uri = uri,
                    displayName = fileName,
                    relativePath = "$relativeDir/$fileName",
                    sizeBytes = sizeBytes
                )
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "TakeMaster"
            )
            moviesDir.mkdirs()
            val dest = File(moviesDir, fileName)
            sourceFile.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
            return ExportOutput(
                uri = Uri.fromFile(dest),
                displayName = fileName,
                relativePath = "$relativeDir/$fileName",
                sizeBytes = sizeBytes
            )
        }
    }

    fun openVideo(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Open Exported Video"))
        } catch (_: Exception) {
        }
    }

    fun shareVideo(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Share Exported Video"))
        } catch (_: Exception) {
        }
    }
}
