package io.github.iufb.reach.core.voip.compat;

import android.util.Log;

/** Вместо org.telegram.messenger.FileLog в Java-части WebRTC (scripts/vendor-webrtc-java.sh). */
public final class FileLog {
    private static final String TAG = "tgcalls";

    private FileLog() {}

    public static void e(Throwable e) {
        Log.e(TAG, "WebRTC", e);
    }

    public static void d(String message) {
        Log.d(TAG, message);
    }
}
