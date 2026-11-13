package com.drumcover.compstudio.export

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.drumcover.compstudio.audio.AlignmentResult
import java.io.File

data class CompSegment(
    val startSec: Double,
    val endSec: Double,
    val takeIndex: Int
)

/**
 * Hardware-accelerated multi-take stitcher using AndroidX Media3 Transformer.
 * Runs directly on the Galaxy S26 Ultra Snapdragon media engine and normalizes
 * Samsung Variable Frame Rate (VFR) timestamps cleanly.
 */
object Media3CompExporter {

    @OptIn(UnstableApi::class)
    fun exportStitchedCover(
        context: Context,
        takes: List<AlignmentResult>,
        segments: List<CompSegment>,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val outFile = File(outDir, "DrumCover_Master_${System.currentTimeMillis()}.mp4")

        val editedItems = segments.map { seg ->
            val take = takes[seg.takeIndex]
            val startMs = ((seg.startSec + take.offsetSeconds).coerceAtLeast(0.0) * 1000.0).toLong()
            val endMs = ((seg.endSec + take.offsetSeconds).coerceAtLeast(seg.startSec + 0.1) * 1000.0).toLong()

            val mediaItem = MediaItem.Builder()
                .setUri(take.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            EditedMediaItem.Builder(mediaItem).build()
        }

        val sequence = EditedMediaItemSequence(editedItems)
        val composition = Composition.Builder(sequence).build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onComplete(outFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    onError(exportException.localizedMessage ?: "Media3 export failed")
                }
            })
            .build()

        transformer.start(composition, outFile.absolutePath)
    }
}
