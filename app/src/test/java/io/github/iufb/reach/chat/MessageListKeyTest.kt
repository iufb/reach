package io.github.iufb.reach.chat

import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.RichText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ключ элемента ленты переживает смену временного id на постоянный (#295). */
class MessageListKeyTest {

    private fun message(id: Long, text: String = "🔥", sending: SendingState? = null) = Message(
        id = id, sender = Sender.User(1), date = 0, isOutgoing = true,
        content = MessageBody.Text(RichText(text)), authorSignature = "", viewCount = 0, sendingState = sending,
    )

    @Test
    fun `new message keys by its own id`() {
        assertEquals(5L, message(5).listKey)
    }

    @Test
    fun `send succeeded keeps the temporary key`() {
        val pending = message(101, sending = SendingState.Pending)
        val list = listOf(pending, message(3), message(1))
        val sent = list.replaceSent(101, message(4))
        assertEquals(listOf(4L, 3L, 1L), sent.map { it.id })
        assertEquals(listOf(101L, 3L, 1L), sent.map { it.listKey })
        assertEquals(null, sent.first().sendingState)
    }

    @Test
    fun `send succeeded after the permanent message already arrived keeps the temporary key`() {
        val list = listOf(message(101, sending = SendingState.Pending), message(4), message(1))
        val sent = list.replaceSent(101, message(4, text = "❤️"))
        assertEquals(listOf(4L, 1L), sent.map { it.id })
        assertEquals(101L, sent.first().listKey)
        assertEquals(MessageBody.Text(RichText("❤️")), sent.first().content)
    }

    @Test
    fun `merge of a reloaded message keeps its key`() {
        val list = listOf(message(101).copy(id = 4), message(1))
        val merged = list.mergeMessages(listOf(message(4, text = "😂"), message(2)))
        assertEquals(listOf(4L, 2L, 1L), merged.map { it.id })
        assertEquals(listOf(101L, 2L, 1L), merged.map { it.listKey })
        assertEquals(MessageBody.Text(RichText("😂")), merged.first().content)
    }

    @Test
    fun `send response after send succeeded does not bring back the pending copy`() {
        // #313: UpdateNewMessage(101) → UpdateMessageSendSucceeded(101 → 4) → ответ sendMessage(101)
        val afterUpdates = listOf(message(101, sending = SendingState.Pending), message(1)).replaceSent(101, message(4))
        val merged = afterUpdates.mergeSent(listOf(message(101, sending = SendingState.Pending)), finished = setOf(101L))
        assertEquals(listOf(4L, 1L), merged.map { it.id })
        assertEquals(null, merged.first().sendingState)
    }

    @Test
    fun `send response before send succeeded leaves one message`() {
        // ответ sendMessage(101) → UpdateMessageSendSucceeded(101 → 4)
        val pending = listOf(message(1)).mergeSent(listOf(message(101, sending = SendingState.Pending)), finished = emptySet())
        val sent = pending.replaceSent(101, message(4))
        assertEquals(listOf(4L, 1L), sent.map { it.id })
    }
}
