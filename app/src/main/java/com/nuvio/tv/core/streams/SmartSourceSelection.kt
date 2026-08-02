package com.nuvio.tv.core.streams

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamVisualTag
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
    val technologies: List<String> = emptyList(),
    val prioritizedAudioLanguage: String? = null,
    val prioritizedSubtitleLanguage: String? = null
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
    private val releaseTagSettings = DebridSettings()

    /**
     * Metadata for one immutable stream result set.
     *
     * Building this is the expensive part of smart-source selection. Callers
     * can derive picker options, the selected source, and all exact matches
     * from the same analysis instead of parsing every release three times.
     */
    class Analysis internal constructor(
        internal val candidates: List<Pair<Stream, SmartSourceMetadata>>
    ) {
        val options: SmartSourceOptions = optionsFrom(candidates)

        fun select(preferences: SmartSourcePreferences): SmartSourceSelectionResult =
            selectFrom(candidates, preferences)

        fun matchingStreams(preferences: SmartSourcePreferences): List<Stream> =
            matchingFrom(candidates, preferences)

        fun withMetadata(stream: Stream, metadata: SmartSourceMetadata): Analysis = Analysis(
            candidates.map { candidate ->
                if (candidate.first == stream) candidate.first to metadata else candidate
            }
        )
    }

    /** Keep the picker aligned with Nuvio's normal release-tag parser. */
    private val technologyOrder = buildList {
        DebridStreamVisualTag.defaultOrder
            .filterNot { it == DebridStreamVisualTag.UNKNOWN }
            .mapTo(this) { technologyLabel(it) }
        DebridStreamAudioTag.defaultOrder
            .filterNot { it == DebridStreamAudioTag.UNKNOWN }
            .mapTo(this) { technologyLabel(it) }
        DebridStreamEncode.defaultOrder
            .filterNot { it == DebridStreamEncode.UNKNOWN }
            .mapTo(this) { it.label }
    }.distinct()

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

    private val flagLanguages = linkedMapOf(
        "🇧🇷" to "pt-BR",
        "🇵🇹" to "pt",
        "🇺🇸" to "en",
        "🇬🇧" to "en",
        "🇪🇸" to "es",
        "🇲🇽" to "es-419",
        "🇦🇷" to "es-419",
        "🇨🇴" to "es-419",
        "🇨🇱" to "es-419",
        "🇵🇪" to "es-419",
        "🇫🇷" to "fr",
        "🇩🇪" to "de",
        "🇮🇹" to "it",
        "🇯🇵" to "ja",
        "🇰🇷" to "ko",
        "🇨🇳" to "zh",
        "🇹🇼" to "zh",
        "🇭🇰" to "zh",
        "🇷🇺" to "ru",
        "🇸🇦" to "ar",
        "🇮🇳" to "hi",
        "🇹🇷" to "tr",
        "🇳🇱" to "nl",
        "🇵🇱" to "pl"
    )

    private val dualAudioRegex = Regex("(?i)\\bdual[ ._-]?(?:audio|áudio)\\b")

    private val multiSubtitleRegex = Regex("(?i)\\b(?:multi[ ._-]?subs?|multisub)\\b")
    private val languageTokenSplitRegex = Regex("[^a-z0-9-]+")
    private val twoLetterLanguageRegex = Regex("[a-z]{2}(?:-[a-z]{2})?")
    private val threeLetterLanguageRegex = Regex("[a-z]{3}")

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

    fun analyzeAll(streams: List<Stream>): Analysis =
        Analysis(streams.map { stream -> stream to analyze(stream) })

    fun availableOptions(streams: List<Stream>): SmartSourceOptions = analyzeAll(streams).options

    private fun optionsFrom(
        candidates: List<Pair<Stream, SmartSourceMetadata>>
    ): SmartSourceOptions {
        val metadata = candidates.map { it.second }
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
    ): SmartSourceSelectionResult = analyzeAll(streams).select(preferences)

    fun evaluateCandidate(
        stream: Stream,
        metadata: SmartSourceMetadata,
        preferences: SmartSourcePreferences
    ): SmartSourceSelectionResult = selectFrom(listOf(stream to metadata), preferences)

    fun prioritizeLanguageOptions(
        options: SmartSourceOptions,
        preferredAudioLanguage: String?,
        preferredSubtitleLanguage: String?
    ): SmartSourceOptions {
        val prioritizedAudio = preferredAvailableLanguage(
            options.audioLanguages,
            preferredAudioLanguage
        )
        val prioritizedSubtitle = preferredAvailableLanguage(
            options.subtitleLanguages,
            preferredSubtitleLanguage
        )
        return options.copy(
            audioLanguages = moveLanguageToFront(options.audioLanguages, prioritizedAudio),
            subtitleLanguages = moveLanguageToFront(options.subtitleLanguages, prioritizedSubtitle),
            prioritizedAudioLanguage = prioritizedAudio,
            prioritizedSubtitleLanguage = prioritizedSubtitle
        )
    }

    private fun selectFrom(
        candidates: List<Pair<Stream, SmartSourceMetadata>>,
        preferences: SmartSourcePreferences
    ): SmartSourceSelectionResult {
        if (candidates.isEmpty()) return SmartSourceSelectionResult.None
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

    /** Returns every exact match, ordered by the same ranking used for selection. */
    fun matchingStreams(
        streams: List<Stream>,
        preferences: SmartSourcePreferences
    ): List<Stream> = analyzeAll(streams).matchingStreams(preferences)

    private fun matchingFrom(
        candidates: List<Pair<Stream, SmartSourceMetadata>>,
        preferences: SmartSourcePreferences
    ): List<Stream> = candidates
        .filter { (_, metadata) -> exactMatch(metadata, preferences) }
        .sortedWith(candidateComparator(preferences).reversed())
        .map { it.first }

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

        val languagesFromFlags = extractFlagLanguages(text)
        if (dualAudioRegex.containsMatchIn(text) && languagesFromFlags.isNotEmpty()) {
            // "Dual Audio" does not identify either language. Only attach
            // languages that the source explicitly supplies alongside it.
            audioLanguages += languagesFromFlags
        }

        val subtitleLanguages = linkedSetOf<String>()
        markedSubtitleRegex.findAll(text).forEach { match ->
            normalizeLanguage(match.groupValues[1])?.let(subtitleLanguages::add)
        }
        if (multiSubtitleRegex.containsMatchIn(text)) {
            subtitleLanguages += "multi"
        }
        // Torrent addons often expose language availability only as flags. An
        // unqualified flag can describe audio or subtitles, so retain it in both.
        subtitleLanguages += languagesFromFlags

        return SmartSourceMetadata(
            quality = quality,
            audioLanguages = audioLanguages,
            subtitleLanguages = subtitleLanguages,
            technologies = extractTechnologies(stream),
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
            normalized.matches(twoLetterLanguageRegex) -> normalized
            normalized.matches(threeLetterLanguageRegex) -> languageAliases.entries
                .firstOrNull { (_, aliases) -> normalized in aliases }
                ?.key
                ?: normalized
            else -> null
        }
    }

    private fun exactMatch(metadata: SmartSourceMetadata, preferences: SmartSourcePreferences): Boolean {
        val qualityMatches = SmartSourceQuality.from(preferences.targetQuality)?.label == metadata.quality
        val audioMatches = preferences.targetAudioLanguage.isNullOrBlank() ||
            languageMatches(preferences.targetAudioLanguage, metadata.audioLanguages)
        val subtitleMatches = preferences.targetSubtitleLanguage.isNullOrBlank() ||
            preferences.targetSubtitleLanguage.equals(SUBTITLE_NONE, ignoreCase = true) ||
            languageMatches(preferences.targetSubtitleLanguage, metadata.subtitleLanguages)
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
        if (requestedAudio != null && !languageMatches(requestedAudio, metadata.audioLanguages)) {
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
            !languageMatches(requestedSubtitle, metadata.subtitleLanguages)
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
            score += if (languageMatches(language, metadata.audioLanguages)) 450 else -350
        }
        normalizeLanguage(preferences.targetSubtitleLanguage)
            ?.takeUnless { preferences.targetSubtitleLanguage.equals(SUBTITLE_NONE, ignoreCase = true) }
            ?.let { language ->
                score += if (languageMatches(language, metadata.subtitleLanguages)) 300 else -250
            }

        score += ((metadata.seeds ?: 0).coerceAtMost(1_000) / 10)
        return score
    }

    private fun extractTechnologies(stream: Stream): Set<String> {
        val facts = DirectDebridStreamFilter.facts(stream, releaseTagSettings)
        return buildSet {
            facts.visualTags
                .filterNot { it == DebridStreamVisualTag.UNKNOWN }
                .mapTo(this) { technologyLabel(it) }
            facts.audioTags
                .filterNot { it == DebridStreamAudioTag.UNKNOWN }
                .mapTo(this) { technologyLabel(it) }
            facts.encode
                .takeUnless { it == DebridStreamEncode.UNKNOWN }
                ?.let { add(it.label) }
        }
    }

    private fun extractLanguages(text: String): Set<String> {
        val lowered = text.lowercase(Locale.ROOT)
        val tokenized = lowered.split(languageTokenSplitRegex)
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

    internal fun extractTrackLanguages(text: String): Set<String> = buildSet {
        normalizeLanguage(text)?.let(::add)
        addAll(extractLanguages(text))
        addAll(extractFlagLanguages(text))
    }

    private fun extractFlagLanguages(text: String): Set<String> = buildSet {
        flagLanguages.forEach { (flag, language) ->
            if (flag in text) add(language)
        }
    }

    private fun languageMatches(requestedValue: String?, available: Set<String>): Boolean {
        val requested = normalizeLanguage(requestedValue) ?: return false
        if ("multi" in available || requested in available) return true
        // Torrentio may label Brazilian Portuguese with Portugal's flag.
        return requested.startsWith("pt") && available.any { it.startsWith("pt") }
    }

    private fun preferredAvailableLanguage(
        languages: List<String>,
        preferredLanguage: String?
    ): String? {
        val preferred = normalizeLanguage(preferredLanguage) ?: return null
        val exactIndex = languages.indexOfFirst { language ->
            normalizeLanguage(language) == preferred
        }
        val preferredBase = preferred.substringBefore('-')
        val index = if (exactIndex >= 0) {
            exactIndex
        } else {
            languages.indexOfFirst { language ->
                val normalized = normalizeLanguage(language) ?: return@indexOfFirst false
                normalized.substringBefore('-') == preferredBase && normalized != "multi"
            }
        }
        return languages.getOrNull(index)
    }

    private fun moveLanguageToFront(
        languages: List<String>,
        prioritizedLanguage: String?
    ): List<String> {
        val index = prioritizedLanguage?.let(languages::indexOf) ?: return languages
        if (index <= 0) return languages
        return buildList(languages.size) {
            add(languages[index])
            languages.forEachIndexed { languageIndex, language ->
                if (languageIndex != index) add(language)
            }
        }
    }

    private fun technologyLabel(tag: DebridStreamVisualTag): String = when (tag) {
        DebridStreamVisualTag.DV -> "Dolby Vision"
        else -> tag.label
    }

    private fun technologyLabel(tag: DebridStreamAudioTag): String = when (tag) {
        DebridStreamAudioTag.ATMOS -> "Dolby Atmos"
        else -> tag.label
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
