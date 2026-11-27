package io.github.iufb.reach.calls

/** Этап звонка для экрана: из `updateCall` TDLib и состояния соединения tgcalls. */
sealed interface CallPhase {
    /** Входящий: звонит у нас, ждём «Ответить» / «Отклонить» (#410). */
    data object Incoming : CallPhase

    /** `createCall` отправлен, сервер ещё не создал звонок. */
    data object Requesting : CallPhase

    /** Звонок создан, телефон собеседника ещё не получил его. */
    data object Waiting : CallPhase

    /** У собеседника звонит — играют гудки. */
    data object Ringing : CallPhase

    /** Собеседник ответил, TDLib обменивается ключами. */
    data object ExchangingKeys : CallPhase

    /** Ключи есть, tgcalls соединяется. */
    data object Connecting : CallPhase

    /** Разговор: идёт таймер. */
    data object Active : CallPhase

    /** Связь пропала, tgcalls переподключается. */
    data object Reconnecting : CallPhase

    data object HangingUp : CallPhase

    data class Ended(val reason: EndReason) : CallPhase
}

enum class EndReason { HungUp, Declined, Missed, Disconnected, Failed }

/** Выход звука: из `availableEndpoints` core-telecom. */
data class AudioRoute(val kind: Kind, val name: String) {
    enum class Kind { Earpiece, Speaker, Bluetooth, Wired, Other }
}

data class CallUi(
    /** id звонка TDLib; 0 — `createCall` ещё не ответил. */
    val callId: Int,
    val userId: Long,
    val isOutgoing: Boolean,
    val phase: CallPhase,
    /** Когда соединение установилось впервые (`SystemClock.elapsedRealtime`), для таймера. */
    val connectedAt: Long? = null,
    /** Эмодзи ключа шифрования из `callStateReady.emojis`. */
    val emojis: List<String> = emptyList(),
    val muted: Boolean = false,
    val remoteMuted: Boolean = false,
    /** 0..4; `null` — tgcalls ещё не сообщил. */
    val signalBars: Int? = null,
    val route: AudioRoute? = null,
    val routes: List<AudioRoute> = emptyList(),
    /** Сервер просит оценку (`callStateDiscarded.needRating`). */
    val needRating: Boolean = false,
    /** Текст ошибки TDLib для [EndReason.Failed]. */
    val error: String? = null,
) {
    val isEnded: Boolean get() = phase is CallPhase.Ended
    val isSpeaker: Boolean get() = route?.kind == AudioRoute.Kind.Speaker
}
