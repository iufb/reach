package io.github.iufb.reach.stickers

import io.github.iufb.reach.chat.ChatScope
import io.github.iufb.reach.chat.SendOptions
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.chats.MemberStatus
import io.github.iufb.reach.chats.toInfo
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSetsTest {

    private fun file(id: Int, remoteId: String = "remote$id") =
        TdApi.File(id, 100, 100, TdApi.LocalFile(), TdApi.RemoteFile(remoteId, "unique$id", false, true, 100))

    private fun sticker(id: Long, fileId: Int, remoteId: String = "remote$fileId") = TdApi.Sticker(
        id, 7, 512, 480, "🙂", TdApi.StickerFormatTgs(), TdApi.StickerFullTypeRegular(), null, file(fileId, remoteId),
    )

    private fun set(id: Long, installed: Boolean = false, viewed: Boolean = false) = StickerSetInfo(
        id = id, title = "t$id", name = "n$id", thumbnail = null, stickers = emptyList(), size = 3,
        isInstalled = installed, isArchived = false, isOfficial = false, isViewed = viewed,
    )

    // --- Маппинг

    @Test fun stickerKeepsIdAndRemoteIdForSending() {
        val info = sticker(42, 5).toInfo()
        assertEquals(42L, info.id)
        assertEquals("remote5", info.remoteId)
        assertTrue(info.isTgs)
    }

    @Test fun listSetHasOnlyCovers() {
        val td = TdApi.StickerSetInfo(
            1, "Cats", "cats", TdApi.Thumbnail(TdApi.ThumbnailFormatWebm(), 100, 100, file(9)), null,
            false, true, false, true, TdApi.StickerTypeRegular(), false, false, false, 30, arrayOf(sticker(1, 1)),
        )
        val set = td.toModel()
        assertEquals(1, set.stickers.size)
        assertEquals(30, set.size)
        assertFalse(set.isFull)
        assertTrue(set.isInstalled)
        assertTrue(set.isOfficial)
        assertTrue(set.thumbnail!!.isWebm)
        assertFalse(set.thumbnail!!.isTgs)
    }

    @Test fun fullSetIsFull() {
        val td = TdApi.StickerSet(
            2, "Dogs", "dogs", null, null, false, false, false, false, TdApi.StickerTypeRegular(),
            false, false, true, arrayOf(sticker(1, 1), sticker(2, 2)), emptyArray(),
        )
        val set = td.toModel()
        assertEquals(2, set.size)
        assertTrue(set.isFull)
        assertNull(set.thumbnail)
        assertTrue(set.isViewed)
    }

    @Test fun unknownThumbnailFormatIsDropped() {
        assertNull(TdApi.Thumbnail(TdApi.ThumbnailFormatGif(), 1, 1, file(1)).toSetThumbnail())
        assertTrue(TdApi.Thumbnail(TdApi.ThumbnailFormatTgs(), 1, 1, file(1)).toSetThumbnail()!!.isTgs)
    }

    // --- Отправка

    @Test fun sendUsesRemoteFileSizeAndEmoji() {
        val content = sticker(1, 5).toInfo().toInputContent()
        assertEquals("remote5", (content.sticker.sticker as TdApi.InputFileRemote).id)
        assertEquals(512, content.sticker.width)
        assertEquals(480, content.sticker.height)
        assertEquals("🙂", content.emoji)
    }

    @Test fun sendFallsBackToSessionFileId() {
        val input = sticker(1, 5, remoteId = "").toInfo().inputFile()
        assertEquals(5, (input as TdApi.InputFileId).id)
    }

    @Test fun sendHonorsTopicAndReply() {
        val request = stickerMessage(10, ChatScope.Topic(3), 77, sticker(1, 5).toInfo(), SendOptions())
        assertEquals(10L, request.chatId)
        assertEquals(3, (request.topicId as TdApi.MessageTopicForum).forumTopicId)
        assertEquals(77L, (request.replyTo as TdApi.InputMessageReplyToMessage).messageId)
        assertNull(request.options)
        assertTrue(request.inputMessageContent is TdApi.InputMessageSticker)
    }

    @Test fun sendToWholeChatWithoutReply() {
        val request = stickerMessage(10, ChatScope.Whole, null, sticker(1, 5).toInfo(), SendOptions(silent = true))
        assertNull(request.topicId)
        assertNull(request.replyTo)
        assertTrue(request.options!!.disableNotification)
    }

    // --- Слияние апдейтов

    @Test fun reorderKnownAppliesNewOrder() {
        assertEquals(listOf("c", "a"), reorderKnown(listOf("a", "b", "c"), listOf('c', 'a')) { it[0] })
    }

    @Test fun reorderWithUnknownIdNeedsReload() {
        assertNull(reorderKnown(listOf("a", "b"), listOf('a', 'z')) { it[0] })
    }

    @Test fun installedFlagFollowsList() {
        val installed = set(1).withInstalled(setOf(1))
        assertTrue(installed.isInstalled)
        assertFalse(installed.withInstalled(emptySet()).isInstalled)
        val same = set(2)
        assertTrue(same === same.withInstalled(setOf(1)))
    }

    @Test fun trendingPagesSkipDuplicatesAndStopAtTotal() {
        val first = TrendingStickers().appendPage(listOf(set(1), set(2)), totalCount = 3)
        assertTrue(first.hasMore)
        val second = first.appendPage(listOf(set(2), set(3)), totalCount = 3)
        assertEquals(listOf(1L, 2L, 3L), second.sets.map { it.id })
        assertFalse(second.hasMore)
        assertFalse(second.isLoading)
    }

    @Test fun emptyTrendingPageEndsPaging() {
        assertFalse(TrendingStickers().appendPage(emptyList(), totalCount = 100).hasMore)
    }

    @Test fun trendingUpdateReplacesLoadedPages() {
        val loaded = TrendingStickers().appendPage(listOf(set(1), set(2)), 10).appendPage(listOf(set(3)), 10)
        val updated = trendingFromUpdate(listOf(set(5)), 10)
        assertEquals(3, loaded.sets.size)
        assertEquals(listOf(5L), updated.sets.map { it.id })
        assertTrue(updated.hasMore)
    }

    // --- Права

    private fun chat(kind: ChatKind, group: GroupRef?, canSendOther: Boolean = true) = Chat(
        1, kind, group, "chat", null, emptyMap(), 0, 0, null, 0, true, canSendOtherMessages = canSendOther,
    )

    @Test fun privateChatAlwaysAllowed() {
        assertTrue(chat(ChatKind.Private, null).canSendStickers(null))
    }

    @Test fun unknownOrLeftStatusDenied() {
        val group = chat(ChatKind.Group, GroupRef.Super(1))
        assertFalse(group.canSendStickers(null))
        assertFalse(group.canSendStickers(MemberStatus.Left))
    }

    @Test fun memberFollowsChatPermission() {
        assertTrue(chat(ChatKind.Group, GroupRef.Super(1)).canSendStickers(MemberStatus.Member))
        assertFalse(chat(ChatKind.Group, GroupRef.Super(1), canSendOther = false).canSendStickers(MemberStatus.Member))
    }

    @Test fun restrictedNeedsOwnAndChatPermission() {
        val group = chat(ChatKind.Group, GroupRef.Super(1))
        assertTrue(group.canSendStickers(MemberStatus.Restricted(isMember = true, canSendText = false, canSendOther = true)))
        assertFalse(group.canSendStickers(MemberStatus.Restricted(isMember = true, canSendText = true, canSendOther = false)))
        assertFalse(group.canSendStickers(MemberStatus.Restricted(isMember = false, canSendText = true, canSendOther = true)))
    }

    @Test fun adminIgnoresChatRestriction() {
        val group = chat(ChatKind.Group, GroupRef.Super(1), canSendOther = false)
        assertTrue(group.canSendStickers(MemberStatus.Administrator(canPostMessages = false)))
        assertTrue(group.canSendStickers(MemberStatus.Creator(isMember = true)))
    }

    @Test fun channelOnlyForPosters() {
        val channel = chat(ChatKind.Channel, GroupRef.Super(1))
        assertFalse(channel.canSendStickers(MemberStatus.Member))
        assertFalse(channel.canSendStickers(MemberStatus.Administrator(canPostMessages = false)))
        assertTrue(channel.canSendStickers(MemberStatus.Administrator(canPostMessages = true)))
    }
}
