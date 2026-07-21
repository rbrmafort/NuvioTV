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
