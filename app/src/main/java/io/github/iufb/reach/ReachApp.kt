package io.github.iufb.reach

import android.app.Application
import android.util.Log
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import dagger.hilt.android.HiltAndroidApp
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.td.TdLibInfo
import javax.inject.Inject

@HiltAndroidApp
class ReachApp : Application() {

    // Создаём клиент сразу при старте: TDLib начинает присылать апдейты (#14)
    @Inject lateinit var tdClient: TdClient

    // Репозитории, собирающие состояние из апдейтов, должны подписаться до того, как они пойдут
    @Inject lateinit var chatsRepository: ChatsRepository

    override fun onCreate() {
        super.onCreate()
        // Единые эмодзи (PRODUCT.md §4.5): шрифт Noto из APK вместо системного, на любом устройстве.
        // Compose-текст подхватывает EmojiCompat сам, как только шрифт загружен.
        EmojiCompat.init(BundledEmojiCompatConfig(this).setReplaceAll(true))
        Log.i("Reach", "TDLib ${TdLibInfo.version()}")
    }
}
