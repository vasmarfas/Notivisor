package com.vasmarfas.notivisor.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.ui.theme.NotivisorTheme
import com.vasmarfas.notivisor.phone.core.PhoneBridge
import com.vasmarfas.notivisor.phone.core.TypePrompt

class RemoteTypeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PhoneBridge.init(this)

        setContent {
            NotivisorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TypeSheet(
                        onSend = { text ->
                            PhoneBridge.sendTypedText(text)
                            TypePrompt.dismiss(this@RemoteTypeActivity)
                            finish()
                        },
                        onCancel = {
                            TypePrompt.dismiss(this@RemoteTypeActivity)
                            finish()
                        },
                    )
                }

                LaunchedEffect(Unit) { PhoneBridge.typeDismiss.collect { finish() } }
            }
        }
    }
}

@Composable
private fun TypeSheet(onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(stringResource(R.string.type_on_phone_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.type_on_phone_hint)) },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.quit_cancel)) }
            Button(
                onClick = { if (text.isNotBlank()) onSend(text) },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(stringResource(R.string.type_on_phone_send)) }
        }
    }
}
