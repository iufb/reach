package io.github.iufb.reach.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import io.github.iufb.reach.files.TdFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Сеть, для которой свои правила автозагрузки. */
enum class NetworkKind { Mobile, WiFi, Roaming }

/** Что качается автоматически. */
enum class MediaKind { Photo, Video, File, Gif, Voice, Sticker }

/**
 * Правила автозагрузки для одной сети. Фото, видео и файлы — настройки Telegram (`AutoDownloadSettings`,
 * лимит 0 — выключено); GIF, голосовые и стикеры в Telegram отдельно не настраиваются — храним локально.
 */
data class NetworkRules(
    val enabled: Boolean = true,
    val maxPhoto: Long = DEFAULT_PHOTO,
    val maxVideo: Long = DEFAULT_VIDEO,
    val maxFile: Long = DEFAULT_FILE,
    val gifs: Boolean = true,
    val voice: Boolean = true,
    val stickers: Boolean = true,
) {
    /** Качать ли сразу файл вида [kind] размером [size] байт (0 — неизвестен, считаем маленьким). */
    fun allows(kind: MediaKind, size: Long = 0): Boolean = enabled && when (kind) {
        MediaKind.Photo -> maxPhoto > 0 && size <= maxPhoto
        MediaKind.Video -> maxVideo > 0 && size <= maxVideo
        MediaKind.File -> maxFile > 0 && size <= maxFile
        MediaKind.Gif -> gifs
        MediaKind.Voice -> voice
        MediaKind.Sticker -> stickers
    }

    companion object {
        const val MB = 1024L * 1024
        const val DEFAULT_PHOTO = 1 * MB
        const val DEFAULT_VIDEO = 10 * MB
        const val DEFAULT_FILE = 1 * MB
    }
}

/**
 * Автозагрузка медиа (PRODUCT.md §4.4) — единственная точка, где решается «качать сразу или по тапу».
 *
 * - Пресеты Telegram: `getAutoDownloadSettingsPresets` → `medium` — мобильная сеть, `high` — Wi-Fi,
 *   `low` — роуминг (так же сохраняет `setAutoDownloadSettings` по типу сети).
 * - Текущая сеть берётся из `ConnectivityManager` и передаётся в TDLib (`setNetworkType`).
 */
@Singleton
class AutoDownload @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Исходные настройки Telegram по сетям — чтобы при сохранении не потерять поля, которых нет в UI. */
    private val telegram = HashMap<NetworkKind, TdApi.AutoDownloadSettings>()

    private val _rules = MutableStateFlow(NetworkKind.entries.associateWith { readLocal(it, NetworkRules()) })
    /** Правила по всем сетям — для экрана настроек. */
    val rules: StateFlow<Map<NetworkKind, NetworkRules>> = _rules.asStateFlow()

    private val _network = MutableStateFlow(NetworkKind.WiFi)
    val network: StateFlow<NetworkKind> = _network.asStateFlow()

    /** Правила текущей сети. */
    val current: StateFlow<NetworkRules> = combine(_rules, _network) { rules, network -> rules.getValue(network) }
        .stateIn(scope, SharingStarted.Eagerly, _rules.value.getValue(NetworkKind.WiFi))

    fun start() {
        watchNetwork()
        scope.launch {
            // Пресеты доступны только после входа
            td.updates.filterIsInstance<TdApi.UpdateAuthorizationState>().collect {
                if (it.authorizationState is TdApi.AuthorizationStateReady) refresh()
            }
        }
        scope.launch { refresh() }
    }

    /** Сохраняет правила сети: Telegram-часть — на сервер, остальное — локально. */
    fun update(network: NetworkKind, rules: NetworkRules) {
        _rules.update { it + (network to rules) }
        writeLocal(network, rules)
        val base = telegram[network] ?: TdApi.AutoDownloadSettings().apply { videoUploadBitrate = DEFAULT_BITRATE }
        val settings = TdApi.AutoDownloadSettings(
            rules.enabled,
            rules.maxPhoto.toInt(),
            rules.maxVideo,
            rules.maxFile,
            base.videoUploadBitrate,
            base.preloadLargeVideos,
            base.preloadNextAudio,
            base.preloadStories,
            base.useLessDataForCalls,
        )
        telegram[network] = settings
        scope.launch {
            runCatching { td.send(TdApi.SetAutoDownloadSettings(settings, network.toTd())) }
                .onFailure { Log.w(TAG, "save $network failed", it) }
        }
    }

    /** Фоновая загрузка файла, который правила разрешают качать сразу (видео, голосовые, мелкие файлы). */
    fun preload(file: TdFile) {
        scope.launch { runCatching { td.send(TdApi.DownloadFile(file.id, PRIORITY_PRELOAD, 0, 0, false)) } }
    }

    private suspend fun refresh() {
        val presets = runCatching { td.send(TdApi.GetAutoDownloadSettingsPresets()) }.getOrNull() ?: return
        val byNetwork = mapOf(
            NetworkKind.Mobile to presets.medium,
            NetworkKind.WiFi to presets.high,
            NetworkKind.Roaming to presets.low,
        )
        telegram.putAll(byNetwork)
        _rules.value = byNetwork.mapValues { (network, settings) ->
            readLocal(
                network,
                NetworkRules(
                    enabled = settings.isAutoDownloadEnabled,
                    maxPhoto = settings.maxPhotoFileSize.toLong(),
                    maxVideo = settings.maxVideoFileSize,
                    maxFile = settings.maxOtherFileSize,
                ),
            )
        }
    }

    private fun watchNetwork() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val type: TdApi.NetworkType = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TdApi.NetworkTypeWiFi()
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                            TdApi.NetworkTypeMobile()
                        } else {
                            TdApi.NetworkTypeMobileRoaming()
                        }
                    else -> TdApi.NetworkTypeOther()
                }
                when (type) {
                    is TdApi.NetworkTypeMobile -> _network.value = NetworkKind.Mobile
                    is TdApi.NetworkTypeMobileRoaming -> _network.value = NetworkKind.Roaming
                    // Прочие сети (VPN без транспорта и т. п.) считаем безлимитными
                    else -> _network.value = NetworkKind.WiFi
                }
                setNetworkType(type)
            }

            override fun onLost(network: Network) {
                setNetworkType(TdApi.NetworkTypeNone())
            }
        })
    }

    private var lastType: String? = null

    private fun setNetworkType(type: TdApi.NetworkType) {
        val name = type.javaClass.simpleName
        if (name == lastType) return
        lastType = name
        scope.launch { runCatching { td.send(TdApi.SetNetworkType(type)) } }
    }

    private fun readLocal(network: NetworkKind, base: NetworkRules) = base.copy(
        gifs = prefs.getBoolean("${network.name}.gifs", base.gifs),
        voice = prefs.getBoolean("${network.name}.voice", base.voice),
        stickers = prefs.getBoolean("${network.name}.stickers", base.stickers),
    )

    private fun writeLocal(network: NetworkKind, rules: NetworkRules) {
        prefs.edit()
            .putBoolean("${network.name}.gifs", rules.gifs)
            .putBoolean("${network.name}.voice", rules.voice)
            .putBoolean("${network.name}.stickers", rules.stickers)
            .apply()
    }

    private fun NetworkKind.toTd(): TdApi.NetworkType = when (this) {
        NetworkKind.Mobile -> TdApi.NetworkTypeMobile()
        NetworkKind.WiFi -> TdApi.NetworkTypeWiFi()
        NetworkKind.Roaming -> TdApi.NetworkTypeMobileRoaming()
    }

    private companion object {
        const val TAG = "AutoDownload"
        const val PREFS = "auto_download"
        const val PRIORITY_PRELOAD = 1
        const val DEFAULT_BITRATE = 1000
    }
}

/** Правила автозагрузки текущей сети — для медиа в ленте. */
@Composable
internal fun rememberAutoDownload(): AutoDownload {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).autoDownload() }
}

@Composable
internal fun currentAutoDownloadRules(): NetworkRules {
    val rules by rememberAutoDownload().current.collectAsState()
    return rules
}
