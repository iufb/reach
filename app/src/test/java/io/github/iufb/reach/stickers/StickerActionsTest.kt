package io.github.iufb.reach.stickers

import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.stickers.StickerAction.AddFavorite
import io.github.iufb.reach.stickers.StickerAction.OpenSet
import io.github.iufb.reach.stickers.StickerAction.RemoveFavorite
import io.github.iufb.reach.stickers.StickerAction.RemoveRecent
import io.github.iufb.reach.stickers.StickerAction.Send
import org.junit.Assert.assertEquals
import org.junit.Test

class StickerActionsTest {

    private fun sticker(setId: Long = 10) = StickerInfo(
        width = 512, height = 512, emoji = "🙂", setId = setId, isStatic = true, isTgs = false, isWebm = false,
        file = TdFile(1, "u1"), thumbnail = null,
    )

    @Test fun packStickerInPanel() {
        val actions = stickerActions(StickerPick(sticker()), favorite = false, canSend = true, canOpenSet = true)
        assertEquals(listOf(Send, AddFavorite, OpenSet), actions)
    }

    @Test fun recentFavoriteGetsRemoveActions() {
        val actions = stickerActions(StickerPick(sticker(), recent = true), favorite = true, canSend = true, canOpenSet = true)
        assertEquals(listOf(Send, RemoveFavorite, OpenSet, RemoveRecent), actions)
    }

    @Test fun viewOnlySheetHasNoSendOrOpenSet() {
        val actions = stickerActions(StickerPick(sticker()), favorite = false, canSend = false, canOpenSet = false)
        assertEquals(listOf(AddFavorite), actions)
    }

    @Test fun stickerWithoutPackCannotOpenSet() {
        val actions = stickerActions(StickerPick(sticker(setId = 0)), favorite = false, canSend = true, canOpenSet = true)
        assertEquals(listOf(Send, AddFavorite), actions)
    }
}
