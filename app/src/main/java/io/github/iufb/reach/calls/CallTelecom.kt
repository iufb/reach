package io.github.iufb.reach.calls

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Звонок в системе Android через Jetpack core-telecom (ADR-0015): система знает о звонке, сама ставит
 * режим связи и аудиофокус и отдаёт выходы звука (наушник / динамик / Bluetooth / провод).
 * Если Telecom отказал (прошивка, нет разрешения), звук ведём сами через [AudioManager].
 */
@Singleton
class CallTelecom @Inject constructor(@ApplicationContext private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val calls = CallsManager(context)
    private var registered = false

    private var job: Job? = null
    private var control: CallControlScope? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var focus: AudioFocusRequest? = null

    private val _route = MutableStateFlow<AudioRoute?>(null)
    val route: StateFlow<AudioRoute?> = _route.asStateFlow()

    private val _routes = MutableStateFlow<List<AudioRoute>>(emptyList())
    val routes: StateFlow<List<AudioRoute>> = _routes.asStateFlow()

    /** Регистрирует звонок; [onSystemDisconnect] — система завершила его сама (другой звонок, гарнитура). */
    @SuppressLint("MissingPermission") // MANAGE_OWN_CALLS — обычное разрешение из манифеста core-telecom
    fun start(
        scope: CoroutineScope,
        name: String,
        userId: Long,
        outgoing: Boolean,
        onSystemDisconnect: () -> Unit,
        onSystemAnswer: () -> Unit = {},
    ) {
        stop()
        job = scope.launch {
            try {
                if (!registered) {
                    calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                    registered = true
                }
                val attributes = CallAttributesCompat(
                    displayName = name,
                    address = Uri.fromParts("tg", userId.toString(), null),
                    direction = if (outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                )
                calls.addCall(
                    attributes,
                    onAnswer = { onSystemAnswer() }, // гарнитура, часы, авто
                    onDisconnect = { onSystemDisconnect() },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    control = this
                    launch {
                        availableEndpoints.collect { list ->
                            endpoints = list
                            _routes.value = list.map { it.toRoute() }
                        }
                    }
                    launch { currentCallEndpoint.collect { _route.value = it.toRoute() } }
                }
            } catch (e: CancellationException) {
                throw e // звонок завершён (stop) — это не отказ Telecom
            } catch (e: Exception) {
                Log.w(TAG, "core-telecom недоступен, звук без Telecom", e)
                control = null
                startFallback()
            }
        }
    }

    /** Собеседник ответил: звонок активен для системы. */
    fun setActive(scope: CoroutineScope) {
        val control = control ?: return
        scope.launch { runCatching { control.setActive() }.onFailure { Log.w(TAG, "setActive", it) } }
    }

    /** Ответили на входящий в приложении: сообщить системе. */
    fun answer(scope: CoroutineScope) {
        val control = control ?: return
        scope.launch {
            runCatching { control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }.onFailure { Log.w(TAG, "answer", it) }
        }
    }

    /** Переключает динамик ↔ наушник (или Bluetooth / провод, если подключены). */
    fun toggleSpeaker(scope: CoroutineScope) {
        val speaker = _route.value?.kind != AudioRoute.Kind.Speaker
        val control = control
        if (control == null) {
            setFallbackSpeaker(speaker)
            return
        }
        val target = if (speaker) {
            endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        } else {
            endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
                ?: endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
                ?: endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
        } ?: return
        scope.launch { runCatching { control.requestEndpointChange(target) }.onFailure { Log.w(TAG, "endpoint", it) } }
    }

    /** Звонок завершён: снять его с учёта в системе и отпустить звук. */
    fun stop() {
        val control = control
        val job = job
        this.control = null
        this.job = null
        if (control != null) {
            control.launch {
                runCatching { control.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                job?.cancel()
            }
        } else {
            job?.cancel()
        }
        stopFallback()
        _route.value = null
        _routes.value = emptyList()
    }

    private fun startFallback() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audio.requestAudioFocus(request)
        focus = request
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        _routes.value = listOf(AudioRoute(AudioRoute.Kind.Earpiece, ""), AudioRoute(AudioRoute.Kind.Speaker, ""))
        setFallbackSpeaker(false)
    }

    private fun setFallbackSpeaker(speaker: Boolean) {
        if (speaker) {
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let(audio::setCommunicationDevice)
        } else {
            audio.clearCommunicationDevice()
        }
        _route.value = AudioRoute(if (speaker) AudioRoute.Kind.Speaker else AudioRoute.Kind.Earpiece, "")
    }

    private fun stopFallback() {
        val request = focus ?: return
        focus = null
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
        audio.abandonAudioFocusRequest(request)
    }

    private fun CallEndpointCompat.toRoute() = AudioRoute(
        kind = when (type) {
            CallEndpointCompat.TYPE_EARPIECE -> AudioRoute.Kind.Earpiece
            CallEndpointCompat.TYPE_SPEAKER -> AudioRoute.Kind.Speaker
            CallEndpointCompat.TYPE_BLUETOOTH -> AudioRoute.Kind.Bluetooth
            CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRoute.Kind.Wired
            else -> AudioRoute.Kind.Other
        },
        name = name.toString(),
    )

    private companion object {
        const val TAG = "CallTelecom"
    }
}
