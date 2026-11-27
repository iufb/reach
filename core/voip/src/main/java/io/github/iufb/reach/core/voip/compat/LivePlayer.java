package io.github.iufb.reach.core.voip.compat;

/** Вместо org.telegram.ui.Stories.LivePlayer: трансляций нет, запись никогда не идёт. */
public final class LivePlayer {
    public static final Object recording = null;

    private LivePlayer() {}
}
