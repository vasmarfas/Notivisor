package com.vasmarfas.notivisor.core.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.transfer.FileSendService

@Composable
fun SendFilesButton(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> FileSendService.send(context, uris) }

    OutlinedButton(
        onClick = {
            runCatching { picker.launch(arrayOf("*/*")) }.onFailure {
                Toast.makeText(context, R.string.file_picker_missing, Toast.LENGTH_LONG).show()
            }
        },
        enabled = enabled,
        modifier = modifier,
    ) { Text(stringResource(R.string.action_send_files)) }
}
