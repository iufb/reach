package io.github.iufb.reach

import android.app.Application
import android.util.Log
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.iufb.reach.calls.CallManager
import io.github.iufb.reach.chat.ReactionsRepository
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.GroupsRepository
import io.github.iufb.reach.contacts.ContactSync
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.td.TdLibInfo
import io.github.iufb.reach.files.FilesRepository
import io.github.iufb.reach.files.TdFileFetcher
import io.github.iufb.reach.fonts.DeviceFonts
import io.github.iufb.reach.media.AutoDownload
import io.github.iufb.reach.notifications.Notifier
import io.github.iufb.reach.push.PushRegistrar
import io.github.iufb.reach.ui.PerfFlags
import io.github.iufb.reach.ui.format.AppLanguages
import io.github.iufb.reach.users.UsersRepository
import javax.inject.Inject

@HiltAndroidApp
class ReachApp : Application(), SingletonImageLoader.Factory {

    // Создаём клиент сразу при старте: TDLib начинает присылать апдейты (#14)
    @Inject lateinit var tdClient: TdClient

    // Репозитории, собирающие состояние из апдейтов, должны подписаться до того, как они пойдут
    @Inject lateinit var chatsRepository: ChatsRepository

    @Inject lateinit var usersRepository: UsersRepository

    @Inject lateinit var groupsRepository: GroupsRepository

    @Inject lateinit var filesRepository: FilesRepository

    @Inject lateinit var reactionsRepository: ReactionsRepository

    @Inject lateinit var pushRegistrar: PushRegistrar

    @Inject lateinit var notifier: Notifier

    @Inject lateinit var autoDownload: AutoDownload

    @Inject lateinit var contactSync: ContactSync

    // Звонки: входящий updateCall (в том числе разбуженный пушем) должен застать подписчика (#410)
    @Inject lateinit var callManager: CallManager

    // Свои шрифты (#252) собираются синхронно при инъекции в super.onCreate(), до первого кадра;
    // время загрузки — в логе «Reach»
    @Inject lateinit var deviceFonts: DeviceFonts

    override fun onCreate() {
        super.onCreate()
        PerfFlags.init(this)
        // Язык интерфейса (#405): с Android 13 его применяет система, на Android 12 — мы
        AppLanguages.applyAtStartup(this)
        // Единые эмодзи (PRODUCT.md §4.5): шрифт Noto из APK вместо системного, на любом устройстве.
        // Compose-текст подхватывает EmojiCompat сам, как только шрифт загружен.
        // Со своим emoji-шрифтом (#254) EmojiCompat не включаем: setReplaceAll заменил бы его эмодзи
        // на Noto. Эмодзи тогда рисует fallback шрифтов темы (DeviceFontFamilies), без EmojiCompat
        // Compose emojiSupportMatch ни на что не влияет.
        if (deviceFonts.emojiFile == null) EmojiCompat.init(BundledEmojiCompatConfig(this).setReplaceAll(true))
        Log.i("Reach", "TDLib ${TdLibInfo.version()}")
        notifier.start()
        pushRegistrar.start()
        autoDownload.start()
        contactSync.start()
    }

    /** Coil умеет грузить [io.github.iufb.reach.files.TdFile] через TDLib. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(TdFileFetcher.Factory(filesRepository))
            add(TdFileFetcher.Keyer)
        }
        .build()
}
