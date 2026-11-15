package io.github.iufb.reach

import android.app.Application
import android.util.Log
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.td.TdLibInfo
import io.github.iufb.reach.files.FilesRepository
import io.github.iufb.reach.files.TdFileFetcher
import javax.inject.Inject

@HiltAndroidApp
class ReachApp : Application(), SingletonImageLoader.Factory {

    // Создаём клиент сразу при старте: TDLib начинает присылать апдейты (#14)
    @Inject lateinit var tdClient: TdClient

    // Репозитории, собирающие состояние из апдейтов, должны подписаться до того, как они пойдут
    @Inject lateinit var chatsRepository: ChatsRepository

    @Inject lateinit var filesRepository: FilesRepository

    override fun onCreate() {
        super.onCreate()
        // Единые эмодзи (PRODUCT.md §4.5): шрифт Noto из APK вместо системного, на любом устройстве.
        // Compose-текст подхватывает EmojiCompat сам, как только шрифт загружен.
        EmojiCompat.init(BundledEmojiCompatConfig(this).setReplaceAll(true))
        Log.i("Reach", "TDLib ${TdLibInfo.version()}")
    }

    /** Coil умеет грузить [io.github.iufb.reach.files.TdFile] через TDLib. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(TdFileFetcher.Factory(filesRepository))
            add(TdFileFetcher.Keyer)
        }
        .build()
}
