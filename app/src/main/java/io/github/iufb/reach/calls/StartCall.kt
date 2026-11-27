package io.github.iufb.reach.calls

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.iufb.reach.R

/**
 * Позвонить пользователю: сначала микрофон (без него tgcalls не запустить), потом звонок и [onStarted] —
 * обычно переход на экран звонка. Общее для профиля, шапки чата и «перезвонить» в истории (#409, #412).
 */
@Composable
fun rememberStartCall(onStarted: () -> Unit, viewModel: CallViewModel = hiltViewModel()): (userId: Long) -> Unit {
    val context = LocalContext.current
    val started by rememberUpdatedState(onStarted)
    var pending by rememberSaveable { mutableStateOf<Long?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val userId = pending
        pending = null
        if (granted && userId != null) {
            viewModel.startCall(userId)
            started()
        } else if (!granted) {
            Toast.makeText(context, R.string.call_mic_permission, Toast.LENGTH_SHORT).show()
        }
    }
    return remember(viewModel, permission) {
        { userId ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                viewModel.startCall(userId)
                started()
            } else {
                pending = userId
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
