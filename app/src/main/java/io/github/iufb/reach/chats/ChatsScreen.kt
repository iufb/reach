package io.github.iufb.reach.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.ReachTheme

@Composable
fun ChatsScreen(viewModel: ChatsViewModel = hiltViewModel()) {
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    ChatsContent(loggingOut, viewModel::logOut)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsContent(loggingOut: Boolean, onLogOut: () -> Unit) {
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { confirmLogOut = true }, enabled = !loggingOut) {
                        Text(stringResource(R.string.logout))
                    }
                },
            )
        },
    ) { padding ->
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            if (loggingOut) {
                CircularProgressIndicator()
            } else {
                // Заглушка до #19
                Text(
                    text = stringResource(R.string.chats_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (confirmLogOut) {
        LogOutDialog(
            onConfirm = {
                confirmLogOut = false
                onLogOut()
            },
            onDismiss = { confirmLogOut = false },
        )
    }
}

@Composable
private fun LogOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logout_confirm_title)) },
        text = { Text(stringResource(R.string.logout_confirm_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.logout)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Preview
@Composable
private fun ChatsPreview() {
    ReachTheme { ChatsContent(loggingOut = false, onLogOut = {}) }
}
