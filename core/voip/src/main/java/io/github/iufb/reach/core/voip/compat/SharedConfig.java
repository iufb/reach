package io.github.iufb.reach.core.voip.compat;

/** Вместо org.telegram.messenger.SharedConfig: системные AEC / NS не отключаем. */
public final class SharedConfig {
    public static boolean disableVoiceAudioEffects = false;

    private SharedConfig() {}
}
