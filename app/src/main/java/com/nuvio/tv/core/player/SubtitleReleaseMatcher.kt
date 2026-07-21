package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Subtitle
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Matches addon subtitle release names against the playing torrent/video file. */
object SubtitleReleaseMatcher {
    private val noiseTokens = setOf(
        "2160p", "1080p", "1080i", "720p", "480p", "360p",
        "uhd", "bluray", "bdrip", "brrip", "webrip", "webdl", "web",
        "hdtv", "dvdrip", "remux", "x264", "x265", "h264", "h265",
        "hevc", "av1", "hdr", "hdr10", "dv", "dovi", "atmos", "aac",
        "ac3", "eac3", "ddp", "dts", "truehd", "proper", "repack",
        "extended", "unrated", "multi", "subs", "sub", "subtitle",
        "mkv", "mp4", "avi", "srt", "vtt", "ass", "ssa"
    )

    fun bestMatch(releaseName: String?, subtitles: List<Subtitle>): Subtitle? {
        if (subtitles.isEmpty()) return null
        val release = releaseName?.takeIf { it.isNotBlank() } ?: return subtitles.first()
        return subtitles
            .mapIndexed { index, subtitle ->
                val subtitleName = subtitleReleaseName(subtitle)
                Triple(subtitle, similarity(release, subtitleName), -index)
            }
            .maxWithOrNull(compareBy<Triple<Subtitle, Int, Int>>({ it.second }, { it.third }))
            ?.first
    }

    internal fun similarity(releaseName: String, subtitleName: String): Int {
        val release = parse(releaseName)
        val subtitle = parse(subtitleName)
        if (release.tokens.isEmpty() || subtitle.tokens.isEmpty()) return 0

        var score = 0
        val common = release.tokens intersect subtitle.tokens
        score += common.size * 12
        score -= (subtitle.tokens - release.tokens).size * 2

        if (release.episodeToken != null && release.episodeToken == subtitle.episodeToken) score += 80
        if (release.year != null && release.year == subtitle.year) score += 25
        if (release.releaseGroup != null && release.releaseGroup == subtitle.releaseGroup) score += 45
        if (release.normalizedBase == subtitle.normalizedBase) score += 120
        if (
            release.normalizedBase.contains(subtitle.normalizedBase) ||
            subtitle.normalizedBase.contains(release.normalizedBase)
        ) score += 35
        return score
    }

    private fun subtitleReleaseName(subtitle: Subtitle): String {
        val id = decode(subtitle.id).substringAfterLast('/').substringAfterLast('\\')
        if (looksLikeReleaseName(id)) return id
        val urlName = runCatching {
            URI(subtitle.url).path?.substringAfterLast('/').orEmpty()
        }.getOrDefault("")
        return decode(urlName).ifBlank { id.ifBlank { subtitle.url } }
    }

    private fun looksLikeReleaseName(value: String): Boolean =
        value.contains('.') || value.contains('_') || value.contains('-') ||
            Regex("(?i)s\\d{1,2}e\\d{1,3}|\\b(?:19|20)\\d{2}\\b").containsMatchIn(value)

    private fun parse(value: String): ParsedReleaseName {
        val decoded = decode(value).substringAfterLast('/').substringAfterLast('\\')
        val withoutExtension = decoded.replace(Regex("(?i)\\.(mkv|mp4|avi|srt|vtt|ass|ssa|sub)$"), "")
        val lower = withoutExtension.lowercase(Locale.ROOT)
        val episode = Regex("(?i)\\bs(\\d{1,2})[ ._-]*e(\\d{1,3})\\b")
            .find(lower)
            ?.let { "s${it.groupValues[1].padStart(2, '0')}e${it.groupValues[2].padStart(2, '0')}" }
            ?: Regex("(?i)\\b(\\d{1,2})x(\\d{1,3})\\b")
                .find(lower)
                ?.let { "s${it.groupValues[1].padStart(2, '0')}e${it.groupValues[2].padStart(2, '0')}" }
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(lower)?.value
        val releaseGroup = Regex("-([a-z0-9]+)$").find(lower)?.groupValues?.getOrNull(1)
        val rawTokens = lower.split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 }
        val tokens = rawTokens
            .filterNot { it in noiseTokens || it.matches(Regex("(?:19|20)\\d{2}")) }
            .toSet()
        return ParsedReleaseName(
            normalizedBase = rawTokens.joinToString(""),
            tokens = tokens,
            episodeToken = episode,
            year = year,
            releaseGroup = releaseGroup
        )
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private data class ParsedReleaseName(
        val normalizedBase: String,
        val tokens: Set<String>,
        val episodeToken: String?,
        val year: String?,
        val releaseGroup: String?
    )
}
