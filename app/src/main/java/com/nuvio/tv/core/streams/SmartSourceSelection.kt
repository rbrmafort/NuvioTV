package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs

data class SmartSourcePreferences(
    val enabled: Boolean = false,
    val targetQuality: String = SmartSourceQuality.FULL_HD.label,
    val targetAudioLanguage: String? = null,
    val targetSubtitleLanguage: String? = SmartSourceSelector.SUBTITLE_NONE,
    val technologies: Set<String> = emptySet()
)

enum class SmartSourceQuality(val label: String, val height: Int) {
    UHD("4K", 2160),
    FULL_HD("1080p", 1080),
    HD("720p", 720),
    SD("480p", 480),
    LOW("360p", 360);

    companion object {
        fun from(value: String?): SmartSourceQuality? {
            val text = value?.trim()?.lowercase(Locale.ROOT) ?: return null
            return when {
                text == "4k" || text == "uhd" || text.contains("2160") -> UHD
                text.contains("1080") || text == "fhd" -> FULL_HD
                text.contains("720") || text == "hd" -> HD
                text.contains("480") || text == "sd" -> SD
                text.contains("360") -> LOW
                else -> null
            }
        }
    }
}

data class SmartSourceOptions(
    val qualities: List<String> = emptyList(),
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val technologies: List<String> = emptyList()
)

data class SmartSourceMetadata(
    val quality: String?,
    val audioLanguages: Set<String>,
    val subtitleLanguages: Set<String>,
    val technologies: Set<String>,
    val seeds: Int?,
    val releaseName: String?
)

enum class SmartSourcePreferenceCategory {
    QUALITY,
    AUDIO,
    SUBTITLE,
    TECHNOLOGY
}

data class SmartSourcePreferenceLoss(
    val category: SmartSourcePreferenceCategory,
    val requested: String,
    val available: String?
)

data class SmartSourceAlternative(
    val stream: Stream,
    val metadata: SmartSourceMetadata,
    val losses: List<SmartSourcePreferenceLoss>,
    val proposedQuality: String?
) {
    val signature: String = buildString {
        append(stream.stableKey())
        append('|')
        losses.forEach { loss ->
            append(loss.category.name)
            append(':')
            append(loss.requested)
            append('>')
            append(loss.available.orEmpty())
            append('|')
        }
    }
}

sealed interface SmartSourceSelectionResult {
    data object None : SmartSourceSelectionResult
    data class Exact(val stream: Stream, val metadata: SmartSourceMetadata) : SmartSourceSelectionResult
    data class Alternative(val proposal: SmartSourceAlternative) : SmartSourceSelectionResult
}

/**
 * Selects a single stream from a fully-scraped addon result set.
 *
 * Torrent release names are not standardized, so structured addon metadata is
 * preferred and common scene/P2P naming conventions are used as a fallback.
 */
object SmartSourceSelector {
    const val SUBTITLE_NONE = "none"

    private val technologyOrder = listOf(
        "Dolby Vision",
        "HDR10+",
        "HDR10",
        "HDR",
        "Dolby Atmos",
        "DTS:X",
        "DTS-HD",
        "HEVC",
        "AV1"
    )

    private val languageAliases = linkedMapOf(
        "pt-BR" to listOf("pt-br", "ptbr", "por-br", "pob", "brazilian portuguese", "portuguese brazil"),
        "pt" to listOf("por", "portuguese", "portugues"),
        "en" to listOf("eng", "english"),
        "es" to listOf("spa", "spanish", "espanol", "castellano"),
        "es-419" to listOf("latino", "latin spanish", "latam"),
        "fr" to listOf("fre", "fra", "french", "francais"),
        "de" to listOf("ger", "deu", "german", "deutsch"),
        "it" to listOf("ita", "italian", "italiano"),
        "ja" to listOf("jpn", "japanese"),
        "ko" to listOf("kor", "korean"),
        "zh" to listOf("chi", "zho", "chinese", "mandarin"),
        "ru" to listOf("rus", "russian"),
        "ar" to listOf("ara", "arabic"),
        "hi" to listOf("hin", "hindi"),
        "tr" to listOf("tur", "turkish"),
        "nl" to listOf("dut", "nld", "dutch"),
        "pl" to listOf("pol", "polish")
    )

    private val markedSubtitleRegex = Regex(
        "(?i)(?:sub(?:title)?s?|legendas?|subs?)[\\s:._\\-\\[\\]()]*" +
            "([a-z]{2,3}(?:-[a-z]{2})?|brazilian portuguese|portuguese|english|spanish|latino|french|german|italian|japanese|korean)"
    )

    private val seedRegexes = listOf(
        Regex("(?i)(?:seeders?|seeds?|\\bse\\b)[\\s:=|\\-]*(\\d{1,7})"),
        Regex("(?i)(?:^|\\s)s[\\s:=|\\-]+(\\d{1,7})"),
        Regex("(?i)(\\d{1,7})[\\s]*(?:seeders?|seeds?)"),
        Regex("(?:👤|🌱|⬆)[\\s:]*(\\d{1,7})")
    )

    fun availableOptions(streams: List<Stream>): SmartSourceOptions {
        val metadata = streams.map(::analyze)
        val qualities = SmartSourceQuality.entries
            .map { it.label }
            .filter { quality -> metadata.any { it.quality == quality } }
        val audio = metadata.flatMap { it.audioLanguages }.distinct().sortedWith(languageComparator())
        val subtitles = metadata.flatMap { it.subtitleLanguages }.distinct().sortedWith(languageComparator())
        val technologies = technologyOrder.filter { technology ->
            metadata.any { technology in it.technologies }
        }
        return SmartSourceOptions(
            qualities = qualities,
            audioLanguages = audio,
            subtitleLanguages = subtitles,
            technologies = technologies
        )
    }

    fun select(
        streams: List<Stream>,
        preferences: SmartSourcePreferences
    ): SmartSourceSelectionResult {
        if (streams.isEmpty()) return SmartSourceSelectionResult.None
        val candidates = streams.map { stream -> stream to analyze(stream) }
        val exact = candidates.filter { (_, metadata) -> exactMatch(metadata, preferences) }
        if (exact.isNotEmpty()) {
            val selected = exact.maxWithOrNull(candidateComparator(preferences))
                ?: return SmartSourceSelectionResult.None
            return SmartSourceSelectionResult.Exact(selected.first, selected.second)
        }

        val selected = candidates.maxWithOrNull(candidateComparator(preferences))
            ?: return SmartSourceSelectionResult.None
        val losses = preferenceLosses(selected.second, preferences)
        if (losses.isEmpty()) {
            return SmartSourceSelectionResult.Exact(selected.first, selected.second)
        }
        return SmartSourceSelectionResult.Alternative(
            SmartSourceAlternative(
                stream = selected.first,
                metadata = selected.second,
                losses = losses,
                proposedQuality = selected.second.quality?.takeIf {
                    !it.equals(preferences.targetQuality, ignoreCase = true)
                }
            )
        )
    }

    fun analyze(stream: Stream): SmartSourceMetadata {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val releaseName = firstNonBlank(
            stream.behaviorHints?.filename,
            stream.clientResolve?.stream?.raw?.filename,
            stream.clientResolve?.stream?.raw?.torrentName,
            stream.clientResolve?.filename,
            stream.clientResolve?.torrentName,
            stream.title,
            stream.name
        )
        val text = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            releaseName,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" "),
            parsed?.languages?.joinToString(" ")
        ).joinToString(" ")

        val quality = SmartSourceQuality.from(parsed?.resolution)?.label
            ?: SmartSourceQuality.from(parsed?.quality)?.label
            ?: SmartSourceQuality.from(stream.quality)?.label
            ?: SmartSourceQuality.from(text)?.label

        val audioLanguages = linkedSetOf<String>()
        parsed?.languages.orEmpty().forEach { language ->
            normalizeLanguage(language)?.let(audioLanguages::add)
        }
        extractLanguages(text).forEach(audioLanguages::add)

        val subtitleLanguages = linkedSetOf<String>()
        markedSubtitleRegex.findAll(text).forEach { match ->
            normalizeLanguage(match.groupValues[1])?.let(subtitleLanguages::add)
        }
        if (Regex("(?i)\\b(?:multi[ ._-]?subs?|multisub)\\b").containsMatchIn(text)) {
            subtitleLanguages += "multi"
        }

        return SmartSourceMetadata(
            quality = quality,
            audioLanguages = audioLanguages,
            subtitleLanguages = subtitleLanguages,
            technologies = extractTechnologies(text, parsed?.hdr.orEmpty(), parsed?.audio.orEmpty(), parsed?.codec),
            seeds = extractSeeds(text),
            releaseName = releaseName
        )
    }

    fun normalizeLanguage(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace('_', '-')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (normalized == "multi" || normalized == "multilingual") return "multi"
        languageAliases.forEach { (code, aliases) ->
            if (normalized == code.lowercase(Locale.ROOT) || normalized in aliases) return code
        }
        return when {
            normalized.matches(Regex("[a-z]{2}(?:-[a-z]{2})?")) -> normalized
            normalized.matches(Regex("[a-z]{3}")) -> languageAliases.entries
                .firstOrNull { (_, aliases) -> normalized in aliases }
                ?.key
                ?: normalized
            else -> null
        }
    }

    private fun exactMatch(metadata: SmartSourceMetadata, preferences: SmartSourcePreferences): Boolean {
        val qualityMatches = SmartSourceQuality.from(preferences.targetQuality)?.label == metadata.quality
        val audioMatches = preferences.targetAudioLanguage.isNullOrBlank() ||
            normalizeLanguage(preferences.targetAudioLanguage) in metadata.audioLanguages
        val subtitleMatches = preferences.targetSubtitleLanguage.isNullOrBlank() ||
            preferences.targetSubtitleLanguage.equals(SUBTITLE_NONE, ignoreCase = true) ||
            normalizeLanguage(preferences.targetSubtitleLanguage) in metadata.subtitleLanguages ||
            "multi" in metadata.subtitleLanguages
        val technologyMatches = preferences.technologies.all { requested ->
            metadata.technologies.any { it.equals(requested, ignoreCase = true) }
        }
        return qualityMatches && audioMatches && subtitleMatches && technologyMatches
    }

    private fun preferenceLosses(
        metadata: SmartSourceMetadata,
        preferences: SmartSourcePreferences
    ): List<SmartSourcePreferenceLoss> = buildList {
        val requestedQuality = SmartSourceQuality.from(preferences.targetQuality)?.label
        if (requestedQuality != null && metadata.quality != requestedQuality) {
            add(SmartSourcePreferenceLoss(SmartSourcePreferenceCategory.QUALITY, requestedQuality, metadata.quality))
        }

        val requestedAudio = normalizeLanguage(preferences.targetAudioLanguage)
        if (requestedAudio != null && requestedAudio !in metadata.audioLanguages) {
            add(
                SmartSourcePreferenceLoss(
                    SmartSourcePreferenceCategory.AUDIO,
                    requestedAudio,
                    metadata.audioLanguages.joinToString().ifBlank { null }
                )
            )
        }

        val requestedSubtitle = normalizeLanguage(preferences.targetSubtitleLanguage)
        if (
            requestedSubtitle != null &&
            !preferences.targetSubtitleLanguage.equals(SUBTITLE_NONE, ignoreCase = true) &&
            requestedSubtitle !in metadata.subtitleLanguages &&
            "multi" !in metadata.subtitleLanguages
        ) {
            add(
                SmartSourcePreferenceLoss(
                    SmartSourcePreferenceCategory.SUBTITLE,
                    requestedSubtitle,
                    metadata.subtitleLanguages.joinToString().ifBlank { null }
                )
            )
        }

        preferences.technologies.forEach { technology ->
            if (metadata.technologies.none { it.equals(technology, ignoreCase = true) }) {
                add(
                    SmartSourcePreferenceLoss(
                        SmartSourcePreferenceCategory.TECHNOLOGY,
                        technology,
                        metadata.technologies.joinToString().ifBlank { null }
                    )
                )
            }
        }
    }

    private fun candidateComparator(
        preferences: SmartSourcePreferences
    ): Comparator<Pair<Stream, SmartSourceMetadata>> = compareBy<Pair<Stream, SmartSourceMetadata>>(
        { candidateScore(it.second, preferences) },
        { it.second.seeds ?: -1 },
        { it.first.behaviorHints?.videoSize ?: -1L }
    )

    private fun candidateScore(metadata: SmartSourceMetadata, preferences: SmartSourcePreferences): Int {
        var score = 0

        // User-requested playback technologies are intentional constraints. Preserve
        // them ahead of resolution so a 4K HDR stream beats a 1080p SDR stream when
        // HDR was explicitly selected.
        preferences.technologies.forEach { technology ->
            score += if (metadata.technologies.any { it.equals(technology, ignoreCase = true) }) 2_000 else -2_000
        }

        val requestedQuality = SmartSourceQuality.from(preferences.targetQuality)
        val actualQuality = SmartSourceQuality.from(metadata.quality)
        score += when {
            requestedQuality == null -> 0
            actualQuality == requestedQuality -> 700
            actualQuality == null -> -250
            else -> 420 - abs(
                SmartSourceQuality.entries.indexOf(requestedQuality) -
                    SmartSourceQuality.entries.indexOf(actualQuality)
            ) * 110
        }

        normalizeLanguage(preferences.targetAudioLanguage)?.let { language ->
            score += if (language in metadata.audioLanguages) 450 else -350
        }
        normalizeLanguage(preferences.targetSubtitleLanguage)
            ?.takeUnless { preferences.targetSubtitleLanguage.equals(SUBTITLE_NONE, ignoreCase = true) }
            ?.let { language ->
                score += if (language in metadata.subtitleLanguages || "multi" in metadata.subtitleLanguages) 300 else -250
            }

        score += ((metadata.seeds ?: 0).coerceAtMost(1_000) / 10)
        return score
    }

    private fun extractTechnologies(
        text: String,
        hdr: List<String>,
        audio: List<String>,
        codec: String?
    ): Set<String> {
        val upper = (listOf(text) + hdr + audio + listOfNotNull(codec)).joinToString(" ").uppercase(Locale.ROOT)
        return buildSet {
            if (Regex("(?:DOLBY[ ._-]?VISION|DOVI|\\bDV\\b)").containsMatchIn(upper)) add("Dolby Vision")
            if (Regex("HDR[ ._-]?10\\+").containsMatchIn(upper)) add("HDR10+")
            if (Regex("HDR[ ._-]?10(?!\\+)").containsMatchIn(upper)) add("HDR10")
            if (Regex("\\bHDR\\b").containsMatchIn(upper) && none { it.startsWith("HDR10") }) add("HDR")
            if (Regex("(?:DOLBY[ ._-]?)?ATMOS").containsMatchIn(upper)) add("Dolby Atmos")
            if (Regex("DTS[ ._-]?X").containsMatchIn(upper)) add("DTS:X")
            if (Regex("DTS[ ._-]?HD").containsMatchIn(upper)) add("DTS-HD")
            if (Regex("(?:HEVC|H[ ._-]?265|X265)").containsMatchIn(upper)) add("HEVC")
            if (Regex("\\bAV1\\b").containsMatchIn(upper)) add("AV1")
        }
    }

    private fun extractLanguages(text: String): Set<String> {
        val lowered = text.lowercase(Locale.ROOT)
        val tokenized = lowered.split(Regex("[^a-z0-9-]+"))
        return buildSet {
            languageAliases.forEach { (code, aliases) ->
                if (
                    tokenized.any { token -> token in aliases } ||
                    aliases.any { alias -> ' ' in alias && lowered.contains(alias) }
                ) {
                    add(code)
                }
            }
        }
    }

    private fun extractSeeds(text: String): Int? = seedRegexes
        .flatMap { regex -> regex.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList() }
        .maxOrNull()

    private fun languageComparator(): Comparator<String> = compareBy(
        { if (it == "multi") 1 else 0 },
        { it.lowercase(Locale.ROOT) }
    )

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }
        ?.let(::decodeReleaseName)

    private fun decodeReleaseName(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
