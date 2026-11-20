package io.github.iufb.reach

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import io.github.iufb.reach.navigation.ReachNavHost
import io.github.iufb.reach.ui.theme.ReachTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Чат, который просили открыть из уведомления; `null` — запроса нет или он уже выполнен. */
    private val openChatRequest = MutableStateFlow<Long?>(null)

    /** Ссылка `t.me/…` / `tg://…`, с которой открыли приложение. */
    private val openLinkRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // После пересоздания активити запрос уже выполнен
        if (savedInstanceState == null) {
            openChatRequest.value = intent.chatIdExtra()
            openLinkRequest.value = intent.linkData()
        }
        enableEdgeToEdge()
        setContent {
            ReachTheme {
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
