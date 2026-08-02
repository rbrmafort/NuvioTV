package com.nuvio.tv.core.streams

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class SmartSourceTrackMetadata(
    val audioLanguages: Set<String>,
    val subtitleLanguages: Set<String>
)

@Singleton
class SmartSourceMediaProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun probe(
        sourceUrl: String,
        headers: Map<String, String> = emptyMap()
    ): SmartSourceTrackMetadata? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        runInterruptible(Dispatchers.IO) {
            probeBlocking(sourceUrl, headers)
        }
    }

    private fun probeBlocking(
        sourceUrl: String,
        headers: Map<String, String>
    ): SmartSourceTrackMetadata? {
        val extractor = MediaExtractor()
        return try {
            val uri = Uri.parse(sourceUrl)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> extractor.setDataSource(sourceUrl, headers)
                else -> extractor.setDataSource(context, uri, headers)
            }

            if (extractor.trackCount <= 0) return null

            val audioLanguages = linkedSetOf<String>()
            val subtitleLanguages = linkedSetOf<String>()
            var recognizedMediaTrack = false

            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.stringValue(MediaFormat.KEY_MIME)?.lowercase().orEmpty()
                when {
                    mime.startsWith("video/") -> recognizedMediaTrack = true
                    mime.startsWith("audio/") -> {
                        recognizedMediaTrack = true
                        audioLanguages += format.trackLanguages()
                    }
                    mime.isSubtitleMimeType() -> {
                        recognizedMediaTrack = true
                        subtitleLanguages += format.trackLanguages()
                    }
                }
            }

            if (!recognizedMediaTrack) {
                null
            } else {
                SmartSourceTrackMetadata(
                    audioLanguages = audioLanguages,
                    subtitleLanguages = subtitleLanguages
                )
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Smart-source media probe failed: ${error.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.trackLanguages(): Set<String> = buildSet {
        listOf(
            stringValue(MediaFormat.KEY_LANGUAGE),
            stringValue(TRACK_LABEL_KEY),
            stringValue(TRACK_TITLE_KEY)
        ).filterNotNull().forEach { value ->
            addAll(SmartSourceSelector.extractTrackLanguages(value))
        }
    }

    private fun MediaFormat.stringValue(key: String): String? =
        if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

    private fun String.isSubtitleMimeType(): Boolean =
        startsWith("text/") ||
            contains("subtitle") ||
            this in SUBTITLE_MIME_TYPES

    private companion object {
        private const val TAG = "SmartSourceMediaProbe"
        private const val PROBE_TIMEOUT_MS = 12_000L
        private const val TRACK_LABEL_KEY = "label"
        private const val TRACK_TITLE_KEY = "title"

        private val SUBTITLE_MIME_TYPES = setOf(
            "application/cea-608",
            "application/cea-708",
            "application/dvbsubs",
            "application/pgs",
            "application/ttml+xml",
            "application/vobsub",
            "application/x-ass",
            "application/x-mp4-cea-608",
            "application/x-mp4-vtt",
            "application/x-quicktime-tx3g",
            "application/x-sami",
            "application/x-ssa",
            "application/x-vobsub",
            "application/x-subrip"
        )
    }
}
