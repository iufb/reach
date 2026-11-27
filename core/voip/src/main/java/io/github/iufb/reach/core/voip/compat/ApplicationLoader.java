package io.github.iufb.reach.core.voip.compat;

import android.content.Context;

/** Вместо org.telegram.messenger.ApplicationLoader: контекст задаёт TgCalls.init. */
public final class ApplicationLoader {
    public static volatile Context applicationContext;

    private ApplicationLoader() {}
}
