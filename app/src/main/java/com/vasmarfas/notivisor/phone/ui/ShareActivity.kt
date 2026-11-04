package com.vasmarfas.notivisor.phone.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.vasmarfas.notivisor.AppRole
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.transfer.FileSendService
import com.vasmarfas.notivisor.core.util.BridgeLog

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val files = sharedFiles()
        if (files.isEmpty()) shareText() else FileSendService.send(this, files)
        finish()
    }

    private fun sharedFiles(): List<Uri> {
        val shared = intent ?: return emptyList()
        val uris = if (shared.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(shared, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(shared, Intent.EXTRA_STREAM, Uri::class.java))
        }
        return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
    }

    private fun shareText() {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (text.isNullOrEmpty()) {
            toast(R.string.share_empty)
            return
        }
        val link = AppRole.link(this)
        if (!link.state.value.isConnected) {
            toast(R.string.share_needs_link)
            return
        }
        val url = URL.find(text)?.value
        if (url != null) {
            link.sendDirect(Envelope(action = Action.OPEN, data = url))
        } else {
            link.sendIfItFits(Envelope(action = Action.CLIP, data = text), "shared text")
        }
        toast(R.string.share_sent)
        BridgeLog.i(SCOPE, "text shared to the other device")
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SCOPE = "share"

        val URL = Regex("""https?://\S+""")
    }
}
