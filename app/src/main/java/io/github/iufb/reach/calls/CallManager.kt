package io.github.iufb.reach.calls

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.voip.CallInstance
import io.github.iufb.reach.core.voip.TgCalls
import io.github.iufb.reach.di.ApplicationScope
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Звонок один-на-один (#409, ADR-0015). TDLib ведёт сигнализацию и ключи (`createCall`, `updateCall`),
 * tgcalls — звук. Одновременно один звонок. Всё состояние меняется в одном потоке ([serial]).
 *
 * Жизнь звонка: [startCall] → `callStatePending` (гудки) → `callStateExchangingKeys` → `callStateReady`
 * (запуск tgcalls) → разговор → [hangUp] или `callStateDiscarded` → [CallPhase.Ended], экран показывает
 * итог и, если сервер просит, оценку; [dismiss] убирает звонок.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    private val users: UsersRepository,
    private val telecom: CallTelecom,
    private val userErrors: UserErrors,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(appScope.coroutineContext + serial)

    private val _call = MutableStateFlow<CallUi?>(null)

    /** Текущий звонок; `null` — звонка нет. */
    val call: StateFlow<CallUi?> = _call.asStateFlow()

    private var instance: CallInstance? = null
    /** Сигнализация, пришедшая до запуска tgcalls. */
    private val pendingSignaling = mutableListOf<ByteArray>()
    private var hangUpRequested = false
    private var sendDebugLog = false
    private var tones: ToneGenerator? = null
    private var clearJob: Job? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateCall -> scope.launch { onCall(update.call) }
                    is TdApi.UpdateNewCallSignalingData -> scope.launch { onSignaling(update.callId, update.data) }
                }
            }
        }
        scope.launch { telecom.route.collect { route -> _call.update { it?.copy(route = route) } } }
        scope.launch { telecom.routes.collect { routes -> _call.update { it?.copy(routes = routes) } } }
    }

    /** Исходящий аудиозвонок. Разрешение `RECORD_AUDIO` проверяет вызывающий. Уже есть звонок — ничего. */
    fun startCall(userId: Long) {
        scope.launch {
            val current = _call.value
            if (current != null && !current.isEnded) return@launch
            reset()
            TgCalls.init(context)
            val protocol = runCatching { protocol() }.getOrElse {
                Log.e(TAG, "tgcalls не загрузился", it)
                _call.value = CallUi(0, userId, true, CallPhase.Ended(EndReason.Failed), error = it.message)
                return@launch
            }
            _call.value = CallUi(callId = 0, userId = userId, isOutgoing = true, phase = CallPhase.Requesting)
            CallService.start(context)
            val name = users.users.value[userId]?.fullName.orEmpty()
            telecom.start(scope, name, userId, outgoing = true, onSystemDisconnect = { scope.launch { hangUp() } })
            runCatching { td.send(TdApi.CreateCall(userId, protocol, false)) }
                .onSuccess { result ->
                    if (_call.value?.callId == 0) _call.update { it?.copy(callId = result.id) }
                    if (hangUpRequested) discard()
                }
                .onFailure { error ->
                    Log.w(TAG, "createCall", error)
                    finish(EndReason.Failed, needRating = false, error = userErrors.message(error))
                }
        }
    }

    /** Положить трубку. До ответа сервера на `createCall` — сразу после него. */
    fun hangUp() {
        scope.launch {
            val call = _call.value ?: return@launch
            when {
                call.isEnded -> dismiss()
                call.callId == 0 -> {
                    hangUpRequested = true
                    _call.update { it?.copy(phase = CallPhase.HangingUp) }
                }
                else -> discard()
            }
        }
    }

    fun toggleMute() {
        scope.launch {
            val call = _call.value ?: return@launch
            val muted = !call.muted
            instance?.setMuted(muted)
            _call.value = call.copy(muted = muted)
        }
    }

    fun toggleSpeaker() {
        scope.launch { telecom.toggleSpeaker(scope) }
    }

    /** Оценка после звонка (`sendCallRating`), 1..5; затем звонок убирается. */
    fun rate(stars: Int) {
        scope.launch {
            val call = _call.value ?: return@launch
            if (call.callId != 0) {
                runCatching { td.send(TdApi.SendCallRating(TdApi.InputCallDiscarded(call.callId), stars, "", emptyArray())) }
                    .onFailure { Log.w(TAG, "sendCallRating", it) }
            }
            dismiss()
        }
    }

    /** Убрать завершённый звонок (экран закрыт или оценка пропущена). */
    fun dismiss() {
        scope.launch {
            if (_call.value?.isEnded == true) {
                _call.value = null
                clearJob?.cancel()
            }
        }
    }

    private suspend fun discard() {
        hangUpRequested = false
        val call = _call.value ?: return
        if (call.callId == 0 || call.isEnded) return
        _call.value = call.copy(phase = CallPhase.HangingUp)
        stopRingback()
        val relay = instance?.preferredRelayId() ?: 0L
        val duration = call.connectedAt?.let { ((SystemClock.elapsedRealtime() - it) / 1000).toInt() } ?: 0
        runCatching { td.send(TdApi.DiscardCall(call.callId, false, "", duration, false, relay)) }
            .onFailure {
                Log.w(TAG, "discardCall", it)
                finish(EndReason.HungUp, needRating = false)
            }
        // Итог придёт в updateCall (callStateDiscarded)
    }

    private suspend fun onCall(call: TdApi.Call) {
        val current = _call.value ?: return
        if (current.isEnded) return
        // updateCall может обогнать ответ на createCall
        if (current.callId == 0) {
            if (!call.isOutgoing || call.userId != current.userId) return
            _call.value = current.copy(callId = call.id)
            if (hangUpRequested) {
                discard()
                return
            }
        } else if (call.id != current.callId) {
            return
        }
        when (val state = call.state) {
            is TdApi.CallStatePending -> {
                val phase = when {
                    state.isReceived -> CallPhase.Ringing
                    state.isCreated -> CallPhase.Waiting
                    else -> CallPhase.Requesting
                }
                setPhase(phase)
                if (phase == CallPhase.Ringing) startRingback()
            }
            is TdApi.CallStateExchangingKeys -> {
                stopRingback()
                setPhase(CallPhase.ExchangingKeys)
                telecom.setActive(scope)
            }
            is TdApi.CallStateReady -> {
                stopRingback()
                _call.update { it?.copy(emojis = state.emojis.toList()) }
                if (instance == null) startInstance(call, state)
            }
            is TdApi.CallStateHangingUp -> setPhase(CallPhase.HangingUp)
            is TdApi.CallStateDiscarded -> {
                sendDebugLog = state.needDebugInformation
                val reason = when (state.reason) {
                    is TdApi.CallDiscardReasonDeclined -> EndReason.Declined
                    is TdApi.CallDiscardReasonMissed -> EndReason.Missed
                    is TdApi.CallDiscardReasonDisconnected -> EndReason.Disconnected
                    else -> EndReason.HungUp
                }
                finish(reason, needRating = state.needRating)
            }
            is TdApi.CallStateError -> finish(EndReason.Failed, needRating = false, error = state.error.message)
        }
    }

    private suspend fun startInstance(call: TdApi.Call, ready: TdApi.CallStateReady) {
        setPhase(CallPhase.Connecting)
        val ours = TgCalls.versions().toSet()
        val version = ready.protocol.libraryVersions.firstOrNull { it in ours } ?: ready.protocol.libraryVersions.firstOrNull()
        if (version == null) {
            finish(EndReason.Failed, needRating = false, error = "no common call protocol")
            return
        }
        val callId = call.id
        val servers = ready.servers.map { server ->
            when (val type = server.type) {
                is TdApi.CallServerTypeTelegramReflector -> CallInstance.Server(
                    server.id, server.ipAddress, server.ipv6Address, server.port, type.peerTag, type.isTcp,
                    isWebrtc = false, username = "", password = "", supportsTurn = false, supportsStun = false,
                )
                is TdApi.CallServerTypeWebrtc -> CallInstance.Server(
                    server.id, server.ipAddress, server.ipv6Address, server.port, null, false,
                    isWebrtc = true, username = type.username, password = type.password,
                    supportsTurn = type.supportsTurn, supportsStun = type.supportsStun,
                )
                else -> null
            }
        }.filterNotNull()
        val logDir = File(context.cacheDir, "calls").apply { mkdirs() }
        instance = runCatching {
            CallInstance.start(
                version = version,
                initializationTimeoutSec = option("call_connect_timeout_ms", 30_000) / 1000.0,
                receiveTimeoutSec = option("call_packet_timeout_ms", 10_000) / 1000.0,
                allowP2p = ready.allowP2p,
                maxLayer = ready.protocol.maxLayer,
                customParameters = ready.customParameters,
                servers = servers,
                encryptionKey = ready.encryptionKey,
                isOutgoing = call.isOutgoing,
                persistentStatePath = File(context.filesDir, "voip_persistent_state.bin").path,
                logPath = File(logDir, "$callId.log").path,
                networkType = networkType(),
                muted = _call.value?.muted == true,
                listener = Listener(callId),
            )
        }.onFailure { Log.e(TAG, "tgcalls start", it) }.getOrNull()
        if (instance == null) {
            discard()
            return
        }
        pendingSignaling.forEach { instance?.receiveSignalingData(it) }
        pendingSignaling.clear()
    }

    private fun onSignaling(callId: Int, data: ByteArray) {
        if (_call.value?.callId != callId) return
        val current = instance
        if (current != null) current.receiveSignalingData(data) else pendingSignaling.add(data)
    }

    /** Колбэки tgcalls (его потоки) → [serial]. */
    private inner class Listener(private val callId: Int) : CallInstance.Listener {
        override fun onState(state: CallInstance.ConnectionState) {
            scope.launch {
                if (_call.value?.callId != callId || _call.value?.isEnded == true) return@launch
                when (state) {
                    CallInstance.ConnectionState.Established -> _call.update {
                        it?.copy(phase = CallPhase.Active, connectedAt = it.connectedAt ?: SystemClock.elapsedRealtime())
                    }
                    CallInstance.ConnectionState.Reconnecting -> setPhase(CallPhase.Reconnecting)
                    CallInstance.ConnectionState.Failed -> {
                        Log.w(TAG, "tgcalls failed")
                        discard()
                    }
                    else -> Unit
                }
            }
        }

        override fun onSignalBars(bars: Int) {
            scope.launch { if (_call.value?.callId == callId) _call.update { it?.copy(signalBars = bars) } }
        }

        override fun onSignalingData(data: ByteArray) {
            scope.launch {
                runCatching { td.send(TdApi.SendCallSignalingData(callId, data)) }.onFailure { Log.w(TAG, "signaling", it) }
            }
        }

        override fun onRemoteMuted(muted: Boolean) {
            scope.launch { if (_call.value?.callId == callId) _call.update { it?.copy(remoteMuted = muted) } }
        }

        override fun onStopped(debugLog: String) {
            scope.launch {
                if (sendDebugLog && debugLog.isNotEmpty()) {
                    runCatching { td.send(TdApi.SendCallDebugInformation(TdApi.InputCallDiscarded(callId), debugLog)) }
                        .onFailure { Log.w(TAG, "sendCallDebugInformation", it) }
                }
            }
        }
    }

    private suspend fun finish(reason: EndReason, needRating: Boolean, error: String? = null) {
        stopRingback()
        instance?.stop()
        instance = null
        pendingSignaling.clear()
        telecom.stop()
        if (reason == EndReason.Declined) playTone(ToneGenerator.TONE_SUP_BUSY, BUSY_TONE_MS)
        _call.update { it?.copy(phase = CallPhase.Ended(reason), needRating = needRating, error = error) }
        // CallService останавливается сам, увидев Ended: stopService до startForeground уронил бы процесс
        // Без оценки итог висит пару секунд и пропадает сам; с оценкой — пока её не дадут или не закроют
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(if (needRating) RATING_TIMEOUT_MS else ENDED_VISIBLE_MS)
            if (_call.value?.isEnded == true) _call.value = null
        }
    }

    private fun reset() {
        instance?.stop()
        instance = null
        pendingSignaling.clear()
        hangUpRequested = false
        sendDebugLog = false
        clearJob?.cancel()
    }

    private fun setPhase(phase: CallPhase) = _call.update { it?.copy(phase = phase) }

    private fun protocol() = TdApi.CallProtocol(true, true, TgCalls.MIN_LAYER, TgCalls.maxLayer(), TgCalls.versions().toTypedArray())

    private suspend fun option(name: String, default: Long): Long =
        (runCatching { td.send(TdApi.GetOption(name)) }.getOrNull() as? TdApi.OptionValueInteger)?.value ?: default

    private fun networkType(): CallInstance.NetworkType {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return CallInstance.NetworkType.Unknown
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> CallInstance.NetworkType.WiFi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> CallInstance.NetworkType.Ethernet
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CallInstance.NetworkType.Lte
            else -> CallInstance.NetworkType.Unknown
        }
    }

    private fun startRingback() {
        if (tones != null) return
        tones = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME) }.getOrNull()
        tones?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
    }

    private fun stopRingback() {
        tones?.stopTone()
        tones?.release()
        tones = null
    }

    private fun playTone(tone: Int, durationMs: Int) {
        val generator = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME) }.getOrNull() ?: return
        generator.startTone(tone, durationMs)
        scope.launch {
            delay(durationMs + 200L)
            generator.release()
        }
    }

    private companion object {
        const val TAG = "CallManager"
        const val TONE_VOLUME = 80
        const val BUSY_TONE_MS = 2500
        const val ENDED_VISIBLE_MS = 2500L
        const val RATING_TIMEOUT_MS = 60_000L
    }
}
