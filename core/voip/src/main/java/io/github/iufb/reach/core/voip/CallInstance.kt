package io.github.iufb.reach.core.voip

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Звонок один-на-один в tgcalls (ADR-0015). Создаётся из `callStateReady` TDLib, живёт до [stop].
 * Колбэки [Listener] приходят из потоков tgcalls — переключайтесь на свой поток сами.
 * Не потокобезопасен для вызовов после [stop]: они игнорируются.
 */
class CallInstance private constructor(private val listener: Listener) {

    interface Listener {
        /** [ConnectionState] соединения tgcalls. */
        fun onState(state: ConnectionState)
        /** 0..4 «палочки» качества связи. */
        fun onSignalBars(bars: Int)
        /** Отправить собеседнику: `sendCallSignalingData`. */
        fun onSignalingData(data: ByteArray)
        /** Микрофон собеседника выключен (`true`). */
        fun onRemoteMuted(muted: Boolean)
        /** tgcalls остановился; [debugLog] — для `sendCallDebugInformation`. */
        fun onStopped(debugLog: String)
    }

    enum class ConnectionState { Unknown, WaitInit, WaitInitAck, Established, Failed, Reconnecting }

    /** Сервер из `callStateReady.servers`: реле Telegram или WebRTC (STUN/TURN). Поля читает натив по именам. */
    class Server(
        @JvmField val id: Long,
        @JvmField val ipv4: String,
        @JvmField val ipv6: String,
        @JvmField val port: Int,
        @JvmField val peerTag: ByteArray?,
        @JvmField val isTcp: Boolean,
        @JvmField val isWebrtc: Boolean,
        @JvmField val username: String,
        @JvmField val password: String,
        @JvmField val supportsTurn: Boolean,
        @JvmField val supportsStun: Boolean,
    )

    /** Тип сети — порядок как у `tgcalls::NetworkType`. */
    enum class NetworkType { Unknown, Gprs, Edge, ThirdGeneration, Hspa, Lte, WiFi, Ethernet, OtherHighSpeed, OtherLowSpeed, OtherMobile, Dialup }

    @Volatile private var ptr = 0L
    private val stopped = AtomicBoolean(false)

    fun receiveSignalingData(data: ByteArray) {
        if (!stopped.get()) nativeReceiveSignalingData(ptr, data)
    }

    fun setMuted(muted: Boolean) {
        if (!stopped.get()) nativeSetMuted(ptr, muted)
    }

    fun setNetworkType(type: NetworkType) {
        if (!stopped.get()) nativeSetNetworkType(ptr, type.ordinal)
    }

    /** id реле, через которое шёл звонок — `connectionId` для `discardCall`. Вызывать до [stop]. */
    fun preferredRelayId(): Long = if (stopped.get()) 0L else nativePreferredRelayId(ptr)

    /** Останавливает звонок; дальше придёт [Listener.onStopped]. Повторный вызов ничего не делает. */
    fun stop() {
        if (stopped.compareAndSet(false, true)) nativeStop(ptr)
    }

    // Колбэки из натива (имена — в tgcalls_jni.cpp)
    private fun onState(state: Int) = listener.onState(ConnectionState.entries.getOrElse(state) { ConnectionState.Unknown })
    private fun onSignalBars(bars: Int) = listener.onSignalBars(bars)
    private fun onSignalingData(data: ByteArray) = listener.onSignalingData(data)
    private fun onAudioLevels(mine: Float, remote: Float) = Unit
    private fun onRemoteMediaState(audio: Int, video: Int) = listener.onRemoteMuted(audio == 0)
    private fun onStopped(debugLog: String) = listener.onStopped(debugLog)

    companion object {
        /**
         * Запускает tgcalls. [version] — версия реализации из `callStateReady.protocol.libraryVersions`,
         * [encryptionKey] — 256 байт `callStateReady.encryptionKey`.
         */
        fun start(
            version: String,
            initializationTimeoutSec: Double,
            receiveTimeoutSec: Double,
            allowP2p: Boolean,
            maxLayer: Int,
            customParameters: String,
            servers: List<Server>,
            encryptionKey: ByteArray,
            isOutgoing: Boolean,
            persistentStatePath: String,
            logPath: String,
            networkType: NetworkType,
            muted: Boolean,
            listener: Listener,
        ): CallInstance {
            TgCalls.requireLoaded()
            val instance = CallInstance(listener)
            instance.ptr = nativeCreate(
                instance, version, initializationTimeoutSec, receiveTimeoutSec, allowP2p, maxLayer, customParameters,
                servers.toTypedArray(), encryptionKey, isOutgoing, persistentStatePath, logPath, networkType.ordinal, muted,
            )
            return instance
        }

        @JvmStatic private external fun nativeCreate(
            peer: CallInstance, version: String, initializationTimeout: Double, receiveTimeout: Double,
            enableP2p: Boolean, maxLayer: Int, customParameters: String, servers: Array<Server>,
            encryptionKey: ByteArray, isOutgoing: Boolean, persistentStatePath: String, logPath: String,
            networkType: Int, muted: Boolean,
        ): Long

        @JvmStatic private external fun nativeReceiveSignalingData(ptr: Long, data: ByteArray)
        @JvmStatic private external fun nativeSetMuted(ptr: Long, muted: Boolean)
        @JvmStatic private external fun nativeSetNetworkType(ptr: Long, type: Int)
        @JvmStatic private external fun nativePreferredRelayId(ptr: Long): Long
        @JvmStatic private external fun nativeStop(ptr: Long)
    }
}
