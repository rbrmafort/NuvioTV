package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleReleaseMatcherTest {
    @Test
    fun `prefers subtitle with matching episode and release group`() {
        val correct = subtitle("Show.Name.S02E07.1080p.WEB-DL.DDP5.1-GROUP.srt")
        val wrongEpisode = subtitle("Show.Name.S02E08.1080p.WEB-DL.DDP5.1-GROUP.srt")
        val wrongGroup = subtitle("Show.Name.S02E07.1080p.WEB-DL.DDP5.1-OTHER.srt")

        val selected = SubtitleReleaseMatcher.bestMatch(
            releaseName = "Show.Name.S02E07.1080p.WEB-DL.DDP5.1-GROUP.mkv",
            subtitles = listOf(wrongEpisode, wrongGroup, correct)
        )

        assertEquals(correct, selected)
    }

    @Test
    fun `scene noise tokens do not outweigh the movie title and year`() {
        val matching = "Movie.Title.2025.720p.BluRay-GRP.srt"
        val unrelated = "Other.Movie.2025.1080p.WEB-DL-GRP.srt"

        assertTrue(
            SubtitleReleaseMatcher.similarity(
                "Movie.Title.2025.2160p.WEB-DL.HDR10-GRP.mkv",
                matching
            ) > SubtitleReleaseMatcher.similarity(
                "Movie.Title.2025.2160p.WEB-DL.HDR10-GRP.mkv",
                unrelated
            )
        )
    }

    private fun subtitle(filename: String) = Subtitle(
        id = filename,
        url = "https://example.test/subtitles/$filename",
        lang = "en",
        addonName = "Subtitle addon",
        addonLogo = null
    )
}
