package io.github.iufb.reach

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import android.util.Log
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
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
import io.github.iufb.reach.ui.format.APP_LANGUAGE
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

    // Свои шрифты (#252) собираются синхронно при инъекции в super.onCreate(), до первого кадра;
    // время загрузки — в логе «Reach»
    @Inject lateinit var deviceFonts: DeviceFonts

    override fun onCreate() {
        super.onCreate()
        useInterfaceLanguage()
        // Единые эмодзи (PRODUCT.md §4.5): шрифт Noto из APK вместо системного, на любом устройстве.
        // Compose-текст подхватывает EmojiCompat сам, как только шрифт загружен.
        EmojiCompat.init(BundledEmojiCompatConfig(this).setReplaceAll(true))
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

    /**
     * Интерфейс только на русском, а системные компоненты (пикеры дат и времени, формат чисел)
     * берут язык из конфигурации. Назначаем приложению язык интерфейса — система применит его
     * к конфигурации и к `Locale.getDefault()` и запомнит (#218). Если в «Языке приложения»
     * системы вернуть «по умолчанию», при следующем запуске язык снова станет русским.
     */
    private fun useInterfaceLanguage() {
        val locales = getSystemService(LocaleManager::class.java) ?: return
        if (locales.applicationLocales.isEmpty) locales.applicationLocales = LocaleList.forLanguageTags(APP_LANGUAGE)
    }
}
