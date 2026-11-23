package io.github.iufb.reach.stickers

import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.files.TdFile
import org.junit.Assert.assertEquals
import org.junit.Test

class StickerSectionsTest {

    private fun sticker(n: Int, setId: Long = 0) = StickerInfo(
        width = 512, height = 512, emoji = "🙂", setId = setId, isStatic = true, isTgs = false, isWebm = false,
        file = TdFile(n, "u$n"), thumbnail = null,
    )

    private fun set(id: Long, vararg stickers: StickerInfo, size: Int = stickers.size, isViewed: Boolean = true) = StickerSetInfo(
        id = id, title = "t$id", name = "n$id", thumbnail = null, stickers = stickers.toList(), size = size,
        isInstalled = true, isArchived = false, isOfficial = false, isViewed = isViewed,
    )

    @Test fun sectionsInOrderRecentFavoritesThenPacks() {
        val sections = stickerSections(
            recent = listOf(sticker(1)),
            favorites = listOf(sticker(2)),
            installed = listOf(set(10, sticker(3)), set(20, sticker(4))),
            sets = emptyMap(),
        )
        assertEquals(listOf("recent", "favorites", "set-10", "set-20"), sections.map { it.key })
    }

    @Test fun emptySectionsAreHidden() {
        val sections = stickerSections(emptyList(), emptyList(), listOf(set(10), set(20, sticker(1))), emptyMap())
        assertEquals(listOf("set-20"), sections.map { it.key })
    }

    @Test fun packUsesFullSetWhenLoaded() {
        val covers = set(10, sticker(1), size = 3)
        val full = set(10, sticker(1), sticker(2), sticker(3))
        val sections = stickerSections(emptyList(), emptyList(), listOf(covers), mapOf(10L to full))
        assertEquals(3, sections.single().stickers.size)
        assertEquals(full, sections.single().set)
    }

    @Test fun packFallsBackToCoversWhileLoading() {
        val sections = stickerSections(emptyList(), emptyList(), listOf(set(10, sticker(1), size = 3)), emptyMap())
        assertEquals(1, sections.single().stickers.size)
    }

    @Test fun duplicateFilesInSectionAreDropped() {
        val sections = stickerSections(listOf(sticker(1), sticker(1), sticker(2)), emptyList(), emptyList(), emptyMap())
        assertEquals(listOf("u1", "u2"), sections.single().stickers.map { it.file.uniqueId })
    }

    @Test fun headerIndicesCountHeaderAndStickers() {
        val sections = stickerSections(
            recent = listOf(sticker(1), sticker(2)),
            favorites = emptyList(),
            installed = listOf(set(10, sticker(3), sticker(4), sticker(5)), set(20, sticker(6))),
            sets = emptyMap(),
        )
        val starts = sectionStarts(sections)
        assertEquals(listOf(0, 3, 7), starts)
        assertEquals(0, sectionAt(starts, 0))
        assertEquals(0, sectionAt(starts, 2))
        assertEquals(1, sectionAt(starts, 3))
        assertEquals(1, sectionAt(starts, 6))
        assertEquals(2, sectionAt(starts, 8))
    }

    @Test fun trendingComesFirstAsSingleGridItem() {
        val sections = stickerSections(
            recent = listOf(sticker(1)),
            favorites = emptyList(),
            installed = listOf(set(10, sticker(2))),
            sets = emptyMap(),
            trending = listOf(set(30, sticker(3)), set(31, sticker(4)), set(30, sticker(3))),
        )
        assertEquals(listOf("trending", "recent", "set-10"), sections.map { it.key })
        assertEquals(listOf(30L, 31L), sections.first().trending.map { it.id })
        assertEquals(listOf(0, 1, 3), sectionStarts(sections))
    }

    @Test fun noTrendingSectionWhenEmpty() {
        val sections = stickerSections(listOf(sticker(1)), emptyList(), emptyList(), emptyMap(), trending = emptyList())
        assertEquals(listOf("recent"), sections.map { it.key })
    }

    @Test fun trendingShownUnlessHiddenWithNothingNew() {
        val viewed = listOf(set(30, isViewed = true), set(31, isViewed = true))
        val fresh = viewed + set(32, isViewed = false)
        assertEquals(false, trendingShown(emptyList(), hidden = false))
        assertEquals(true, trendingShown(viewed, hidden = false))
        assertEquals(false, trendingShown(viewed, hidden = true))
        assertEquals(true, trendingShown(fresh, hidden = true))
    }
}
