package io.github.iufb.reach

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import io.github.iufb.reach.ui.components.IosAlert
import io.github.iufb.reach.ui.components.SheetAction
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
import androidx.lifecycle.lifecycleScope
import io.github.iufb.reach.calls.CallManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.format.AppLanguages
import io.github.iufb.reach.ui.format.InterfaceLanguage
import io.github.iufb.reach.ui.format.withInterfaceLanguage
import io.github.iufb.reach.ui.theme.ReachTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deviceFonts: DeviceFonts

    /** Чат, который просили открыть из уведомления; `null` — запроса нет или он уже выполнен. */
    private val openChatRequest = MutableStateFlow<Long?>(null)

    /** Ссылка `t.me/…` / `tg://…`, с которой открыли приложение. */
    private val openLinkRequest = MutableStateFlow<String?>(null)

    /** Тап по уведомлению звонка: открыть экран звонка (#409). */
    private val openCallRequest = MutableStateFlow(false)

    @Inject lateinit var calls: CallManager

    @Inject lateinit var interfaceFonts: InterfaceFonts

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withInterfaceLanguage())
    }

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 12/24 часа в датах на английском — как в системе (#405); читается при каждом пересоздании
        Dates.use24Hour = android.text.format.DateFormat.is24HourFormat(this)
        holdSplashUntilAuthKnown()
        OverlayPainter.typeface = interfaceFonts.bold()
        // После пересоздания активити запрос уже выполнен
        // Пересоздание активити идёт без сплэша
        if (savedInstanceState != null) SplashGate.dismissed.value = true
        if (savedInstanceState == null) {
            openChatRequest.value = intent.chatIdExtra()
            openLinkRequest.value = intent.linkData()
            handleCallIntent(intent)
        }
        showOverLockScreenDuringCalls()
        enableEdgeToEdge()
        setContent {
            InterfaceLanguage {
                ReachTheme(textFont = interfaceFonts.text, fonts = deviceFonts) {
                    if (hasApiCredentials()) {
                        val openChatId by openChatRequest.collectAsStateWithLifecycle()
                        val openLink by openLinkRequest.collectAsStateWithLifecycle()
                        val openCall by openCallRequest.collectAsStateWithLifecycle()
                        ReachRoot(
                            openChatId,
                            onOpenChatHandled = { openChatRequest.value = null },
                            openLink = openLink,
                            onOpenLinkHandled = { openLinkRequest.value = null },
                            openCall = openCall,
                            onOpenCallHandled = { openCallRequest.value = false },
                        )
                    } else {
                        MissingCredentialsScreen()
                    }
                }
            }
        }
    }

    /** Смена языка приходит сюда, а не пересозданием активити (`configChanges`, #430). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AppLanguages.changed() else AppLanguages.reapplyLegacy(this)
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
        splashScreen.setOnExitAnimationListener { splash ->
            splashHandedOver = true
            playSplashExit(splash)
        }
    }

    /** Система передала сплэш нам ([playSplashExit]): его уход отметит конец нашей анимации. */
    private var splashHandedOver = false

    /**
     * Часть прошивок (vivo, Android 12) убирает сплэш сама, не вызывая обработчик ухода, — тогда
     * [SplashGate] ждал бы запасной таймаут (клавиатура и анимации входа на 3 с позже, #375).
     * Окно приложения получает фокус после того, как окно сплэша убрано; если к этому моменту
     * сплэш нам не передали, он уже ушёл.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || SplashGate.dismissed.value) return
        window.decorView.postDelayed({ if (!splashHandedOver) SplashGate.dismissed.value = true }, SPLASH_FOCUS_GRACE_MS)
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
        handleCallIntent(intent)
    }

    /** Тап по уведомлению звонка или «Ответить» в нём (#410). Без микрофона ответ — с экрана звонка. */
    private fun handleCallIntent(intent: Intent) {
        when (intent.action) {
            ACTION_OPEN_CALL -> openCallRequest.value = true
            ACTION_ANSWER_CALL -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    calls.accept()
                }
                openCallRequest.value = true
            }
        }
    }

    /** Пока есть звонок, экран звонка виден поверх блокировки и будит экран (full-screen intent входящего). */
    private fun showOverLockScreenDuringCalls() {
        lifecycleScope.launch {
            calls.call.map { it != null && !it.isEnded }.distinctUntilChanged().collect { active ->
                setShowWhenLocked(active)
                setTurnScreenOn(active)
            }
        }
    }

    private fun Intent.linkData(): String? = data?.takeIf { action == Intent.ACTION_VIEW }?.toString()

    private fun Intent.chatIdExtra(): Long? = getLongExtra(EXTRA_CHAT_ID, 0).takeIf { it != 0L }

    companion object {
        /** Открыть этот чат: тап по уведомлению. */
        const val EXTRA_CHAT_ID = "io.github.iufb.reach.extra.CHAT_ID"

        /** Открыть экран текущего звонка: тап по уведомлению звонка. */
        const val ACTION_OPEN_CALL = "io.github.iufb.reach.action.OPEN_CALL"

        /** «Ответить» во входящем уведомлении. */
        const val ACTION_ANSWER_CALL = "io.github.iufb.reach.action.ANSWER_CALL"

        private const val SPLASH_MAX_HOLD_MS = 2_000L
        private const val SPLASH_ICON_MS = 800L
        private const val SPLASH_EXIT_MS = 300L
        /** Передача сплэша и фокус окна приходят почти вместе — даём обработчику ухода опередить. */
        private const val SPLASH_FOCUS_GRACE_MS = 100L
    }
}

@Composable
private fun ReachRoot(
    openChatId: Long?,
    onOpenChatHandled: () -> Unit,
    openLink: String?,
    onOpenLinkHandled: () -> Unit,
    openCall: Boolean,
    onOpenCallHandled: () -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    if (authState == AuthState.Ready) {
        NotificationPermissionRequest()
        FullScreenIntentRequest()
    }
    ReachNavHost(authState, openChatId, onOpenChatHandled, openLink, onOpenLinkHandled, openCall, onOpenCallHandled)
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

/**
 * Android 14+: экран входящего звонка поверх блокировки (full-screen intent) вне Google Play
 * может быть выключен. Один раз предлагаем включить в настройках (#410).
 */
@Composable
private fun FullScreenIntentRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val context = LocalContext.current
    var show by rememberSaveable {
        val prefs = context.getSharedPreferences(CALL_PREFS, Context.MODE_PRIVATE)
        val allowed = context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        mutableStateOf(!allowed && !prefs.getBoolean(KEY_FSI_ASKED, false))
    }
    if (!show) return
    val close = {
        show = false
        context.getSharedPreferences(CALL_PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_FSI_ASKED, true) }
    }
    IosAlert(
        title = stringResource(R.string.call_fullscreen_title),
        message = stringResource(R.string.call_fullscreen_text),
        onDismiss = close,
        buttons = listOf(
            SheetAction(stringResource(R.string.call_fullscreen_later)) { close() },
            SheetAction(stringResource(R.string.call_fullscreen_open), bold = true) {
                close()
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
                    )
                }
            },
        ),
    )
}

private const val CALL_PREFS = "calls"
private const val KEY_FSI_ASKED = "full_screen_intent_asked"

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
