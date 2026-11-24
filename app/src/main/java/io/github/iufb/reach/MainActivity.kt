package io.github.iufb.reach

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.window.SplashScreenView
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.iufb.reach.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.iufb.reach.fonts.DeviceFonts
import io.github.iufb.reach.fonts.InterfaceFonts
import io.github.iufb.reach.navigation.ReachNavHost
import io.github.iufb.reach.photoedit.OverlayPainter
import io.github.iufb.reach.ui.theme.ReachTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deviceFonts: DeviceFonts

    /** Чат, который просили открыть из уведомления; `null` — запроса нет или он уже выполнен. */
    private val openChatRequest = MutableStateFlow<Long?>(null)

    /** Ссылка `t.me/…` / `tg://…`, с которой открыли приложение. */
    private val openLinkRequest = MutableStateFlow<String?>(null)

    @Inject lateinit var interfaceFonts: InterfaceFonts

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withInterfaceLanguage())
    }

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        holdSplashUntilAuthKnown()
        OverlayPainter.typeface = interfaceFonts.bold()
        // После пересоздания активити запрос уже выполнен
        // Пересоздание активити идёт без сплэша
        if (savedInstanceState != null) SplashGate.dismissed.value = true
        if (savedInstanceState == null) {
            openChatRequest.value = intent.chatIdExtra()
            openLinkRequest.value = intent.linkData()
        }
        enableEdgeToEdge()
        setContent {
            ReachTheme(textFont = interfaceFonts.text, fonts = deviceFonts) {
                if (hasApiCredentials()) {
                    val openChatId by openChatRequest.collectAsStateWithLifecycle()
                    val openLink by openLinkRequest.collectAsStateWithLifecycle()
                    ReachRoot(
                        openChatId,
                        onOpenChatHandled = { openChatRequest.value = null },
                        openLink = openLink,
                        onOpenLinkHandled = { openLinkRequest.value = null },
                    )
                } else {
                    MissingCredentialsScreen()
                }
            }
        }
    }

    /**
     * Системный сплэш (ADR-0012) держится, пока TDLib не скажет, куда вести: вход или список чатов.
     * Иначе залогиненный пользователь видит мелькание загрузки. Не дольше [SPLASH_MAX_HOLD_MS].
     */
    private fun holdSplashUntilAuthKnown() {
        val startedAt = SystemClock.uptimeMillis()
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val ready = !hasApiCredentials() ||
                    appViewModel.authState.value != AuthState.Loading ||
                    SystemClock.uptimeMillis() - startedAt > SPLASH_MAX_HOLD_MS
                if (ready) content.viewTreeObserver.removeOnPreDrawListener(this)
                return ready
            }
        })
        splashScreen.setOnExitAnimationListener { splash -> playSplashExit(splash) }
    }

    /** Знак увеличивается и тает, под ним проявляется экран. Анимация знака доигрывает до конца. */
    private fun playSplashExit(splash: SplashScreenView) {
        val iconStart = splash.iconAnimationStart?.toEpochMilli() ?: 0L
        val iconDuration = splash.iconAnimationDuration?.toMillis() ?: 0L
        val iconLeft = (iconStart + iconDuration - System.currentTimeMillis())
            .coerceIn(0L, SPLASH_ICON_MS)
        val icon = splash.iconView
        icon?.animate()
            ?.scaleX(1.6f)?.scaleY(1.6f)?.alpha(0f)
            ?.setStartDelay(iconLeft)
            ?.setDuration(SPLASH_EXIT_MS)
            ?.setInterpolator(AccelerateInterpolator())
            ?.start()
        splash.animate()
            .alpha(0f)
            .setStartDelay(iconLeft + SPLASH_EXIT_MS / 3)
            .setDuration(SPLASH_EXIT_MS)
            .withEndAction {
                splash.remove()
                SplashGate.dismissed.value = true
            }
            .start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.chatIdExtra()?.let { openChatRequest.value = it }
        intent.linkData()?.let { openLinkRequest.value = it }
    }

    private fun Intent.linkData(): String? = data?.takeIf { action == Intent.ACTION_VIEW }?.toString()

    private fun Intent.chatIdExtra(): Long? = getLongExtra(EXTRA_CHAT_ID, 0).takeIf { it != 0L }

    companion object {
        /** Открыть этот чат: тап по уведомлению. */
        const val EXTRA_CHAT_ID = "io.github.iufb.reach.extra.CHAT_ID"

        private const val SPLASH_MAX_HOLD_MS = 2_000L
        private const val SPLASH_ICON_MS = 800L
        private const val SPLASH_EXIT_MS = 300L
    }
}

@Composable
private fun ReachRoot(
    openChatId: Long?,
    onOpenChatHandled: () -> Unit,
    openLink: String?,
    onOpenLinkHandled: () -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    if (authState == AuthState.Ready) NotificationPermissionRequest()
    ReachNavHost(authState, openChatId, onOpenChatHandled, openLink, onOpenLinkHandled)
}

/** Один раз за запуск просим разрешение на уведомления, если его нет (Android 13+). */
@Composable
private fun NotificationPermissionRequest() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // До Android 13 разрешения нет: уведомления разрешены по умолчанию
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Ключи пробрасываются из GitHub Secrets только при сборке в CI (ADR-0003). */
private fun hasApiCredentials(): Boolean = BuildConfig.TD_API_ID != 0 && BuildConfig.TD_API_HASH.isNotEmpty()

@Composable
private fun MissingCredentialsScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.error_missing_api_credentials),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Ушёл ли системный сплэш: анимации под ним никто не видит, экран входа ждёт этого флага. */
object SplashGate {
    val dismissed = MutableStateFlow(false)

    /**
     * Дождаться ухода сплэша, но не дольше [WAIT_MS] — на случай, если сплэша не было.
     * Анимации и автофокус (клавиатура) на экране под сплэшем запускаются только после этого.
     */
    suspend fun awaitDismissed() {
        withTimeoutOrNull(WAIT_MS) { dismissed.first { it } }
    }

    private const val WAIT_MS = 3_000L
}
