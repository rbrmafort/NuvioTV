package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSourceSelectorTest {
    @Test
    fun `exact matches are ranked by seed count`() {
        val lowSeeds = stream(
            name = "Movie.2026.1080p.WEB-DL.HDR10.English S:20",
            filename = "Movie.2026.1080p.WEB-DL.HDR10.English.mkv"
        )
        val highSeeds = stream(
            name = "Movie.2026.1080p.WEB-DL.HDR10.English S:315",
            filename = "Movie.2026.1080p.WEB-DL.HDR10.English-GROUP.mkv"
        )

        val result = SmartSourceSelector.select(
            streams = listOf(lowSeeds, highSeeds),
            preferences = SmartSourcePreferences(
                enabled = true,
                targetQuality = "1080p",
                targetAudioLanguage = "en",
                technologies = setOf("HDR10")
            )
        )

        assertEquals(highSeeds, (result as SmartSourceSelectionResult.Exact).stream)
    }

    @Test
    fun `requested HDR is preserved by proposing 4K when 1080p is SDR`() {
        val fullHdSdr = stream(
            name = "Movie.2026.1080p.WEB-DL.English S:500",
            filename = "Movie.2026.1080p.WEB-DL.English.mkv"
        )
        val uhdHdr = stream(
            name = "Movie.2026.2160p.WEB-DL.HDR10 S:80",
            filename = "Movie.2026.2160p.WEB-DL.HDR10.mkv"
        )

        val result = SmartSourceSelector.select(
            streams = listOf(fullHdSdr, uhdHdr),
            preferences = SmartSourcePreferences(
                enabled = true,
                targetQuality = "1080p",
                targetAudioLanguage = "en",
                technologies = setOf("HDR10")
            )
        ) as SmartSourceSelectionResult.Alternative

        assertEquals(uhdHdr, result.proposal.stream)
        assertEquals("4K", result.proposal.proposedQuality)
        assertTrue(result.proposal.losses.any { it.category == SmartSourcePreferenceCategory.QUALITY })
        assertTrue(result.proposal.losses.any { it.category == SmartSourcePreferenceCategory.AUDIO })
    }

    @Test
    fun `structured and release-name values populate preference options`() {
        val options = SmartSourceSelector.availableOptions(
            listOf(
                stream(
                    name = "Filme.2026.2160p.DV.HDR10+.Atmos.PT-BR.SUBS.ENG 👤 42",
                    filename = "Filme.2026.2160p.DV.HDR10+.Atmos.PT-BR.SUBS.ENG.mkv"
                )
            )
        )

        assertEquals(listOf("4K"), options.qualities)
        assertTrue("pt-BR" in options.audioLanguages)
        assertTrue("en" in options.subtitleLanguages)
        assertTrue("Dolby Vision" in options.technologies)
        assertTrue("HDR10+" in options.technologies)
        assertTrue("Dolby Atmos" in options.technologies)
    }

    @Test
    fun `season and title tokens are not mistaken for seeds or languages`() {
        val metadata = SmartSourceSelector.analyze(
            stream(
                name = "It.S02E07.1080p.WEB-DL.x265-GROUP",
                filename = "It.S02E07.1080p.WEB-DL.x265-GROUP.mkv"
            )
        )

        assertEquals(null, metadata.seeds)
        assertTrue("it" !in metadata.audioLanguages)
    }

    @Test
    fun `flag emoji expose subtitle language options`() {
        val metadata = SmartSourceSelector.analyze(
            stream(
                name = "Filme.2026.1080p.WEB-DL 🇧🇷 🇵🇹",
                filename = "Filme.2026.1080p.WEB-DL.mkv"
            )
        )

        assertTrue("pt-BR" in metadata.subtitleLanguages)
        assertTrue("pt" in metadata.subtitleLanguages)
    }

    @Test
    fun `Portugal flag satisfies Brazilian Portuguese subtitle preference`() {
        val source = stream(
            name = "Filme.2026.1080p.WEB-DL.SUBS 🇵🇹 S:24",
            filename = "Filme.2026.1080p.WEB-DL.mkv"
        )

        val matching = SmartSourceSelector.matchingStreams(
            streams = listOf(source),
            preferences = SmartSourcePreferences(
                enabled = true,
                targetQuality = "1080p",
                targetSubtitleLanguage = "pt-BR"
            )
        )

        assertEquals(listOf(source), matching)
    }

    @Test
    fun `bare dual audio does not assume either language`() {
        val metadata = SmartSourceSelector.analyze(
            stream(
                name = "Filme.2026.1080p.WEB-DL.Dual.Audio.x265",
                filename = "Filme.2026.1080p.WEB-DL.Dual.Audio.x265.mkv"
            )
        )

        assertTrue(metadata.audioLanguages.isEmpty())
    }

    @Test
    fun `verified container languages can turn a text fallback into an exact match`() {
        val source = stream(
            name = "Filme.2026.1080p.WEB-DL.Dual.Audio.x265",
            filename = "Filme.2026.1080p.WEB-DL.Dual.Audio.x265.mkv"
        )
        val preferences = SmartSourcePreferences(
            enabled = true,
            targetQuality = "1080p",
            targetAudioLanguage = "pt-BR"
        )
        val textMetadata = SmartSourceSelector.analyze(source)

        assertTrue(
            SmartSourceSelector.evaluateCandidate(source, textMetadata, preferences) is
                SmartSourceSelectionResult.Alternative
        )

        val verified = SmartSourceSelector.evaluateCandidate(
            stream = source,
            metadata = textMetadata.copy(audioLanguages = setOf("en", "pt-BR")),
            preferences = preferences
        )

        assertTrue(verified is SmartSourceSelectionResult.Exact)
    }

    @Test
    fun `preferred available languages are moved to the front of picker options`() {
        val prioritized = SmartSourceSelector.prioritizeLanguageOptions(
            options = SmartSourceOptions(
                audioLanguages = listOf("en", "es", "pt-BR"),
                subtitleLanguages = listOf("de", "en", "pt")
            ),
            preferredAudioLanguage = "pt-br",
            preferredSubtitleLanguage = "en-US"
        )

        assertEquals(listOf("pt-BR", "en", "es"), prioritized.audioLanguages)
        assertEquals(listOf("en", "de", "pt"), prioritized.subtitleLanguages)
        assertEquals("pt-BR", prioritized.prioritizedAudioLanguage)
        assertEquals("en", prioritized.prioritizedSubtitleLanguage)
    }

    @Test
    fun `technology options use normal parser tags including IMAX`() {
        val options = SmartSourceSelector.availableOptions(
            listOf(
                stream(
                    name = "Movie.2026.2160p.IMAX.HLG.10bit.TrueHD.x264",
                    filename = "Movie.2026.2160p.IMAX.HLG.10bit.TrueHD.x264.mkv"
                )
            )
        )

        assertTrue("IMAX" in options.technologies)
        assertTrue("HLG" in options.technologies)
        assertTrue("10bit" in options.technologies)
        assertTrue("TrueHD" in options.technologies)
        assertTrue("AVC" in options.technologies)
    }

    @Test
    fun `matching sources returns every exact match ordered by seeds`() {
        val lowSeeds = stream(
            name = "Movie.2026.1080p.IMAX S:5",
            filename = "Movie.2026.1080p.IMAX-low.mkv"
        )
        val highSeeds = stream(
            name = "Movie.2026.1080p.IMAX S:90",
            filename = "Movie.2026.1080p.IMAX-high.mkv"
        )

        val matching = SmartSourceSelector.matchingStreams(
            streams = listOf(lowSeeds, highSeeds),
            preferences = SmartSourcePreferences(
                enabled = true,
                targetQuality = "1080p",
                technologies = setOf("IMAX")
            )
        )

        assertEquals(listOf(highSeeds, lowSeeds), matching)
    }

    @Test
    fun `one analysis supplies options selection and matching sources`() {
        val portuguese = stream(
            name = "Filme.2026.1080p.WEB-DL.PT-BR.x265 S:90",
            filename = "Filme.2026.1080p.WEB-DL.PT-BR.x265.mkv"
        )
        val english = stream(
            name = "Movie.2026.1080p.WEB-DL.English.x264 S:20",
            filename = "Movie.2026.1080p.WEB-DL.English.x264.mkv"
        )
        val preferences = SmartSourcePreferences(
            enabled = true,
            targetQuality = "1080p",
            targetAudioLanguage = "pt-BR"
        )

        val analysis = SmartSourceSelector.analyzeAll(listOf(english, portuguese))
        val selection = analysis.select(preferences) as SmartSourceSelectionResult.Exact

        assertEquals(listOf("1080p"), analysis.options.qualities)
        assertEquals(portuguese, selection.stream)
        assertEquals(listOf(portuguese), analysis.matchingStreams(preferences))
    }

    private fun stream(name: String, filename: String): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://example.test/$filename",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = false,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = filename
        ),
        addonName = "Test addon",
        addonLogo = null
    )
}
