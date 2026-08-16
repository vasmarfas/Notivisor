package com.vasmarfas.notivisor.phone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.core.PhoneBridge

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val url = shared?.let { URL.find(it)?.value }

        when {
            url == null -> toast(R.string.share_no_link)
            else -> {
                PhoneBridge.init(this)
                PhoneBridge.sendToHeadset(url)
                toast(R.string.share_sent)
                BridgeLog.i(SCOPE, "link shared to the headset")
            }
        }
        finish()
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SCOPE = "share"

        val URL = Regex("""https?://\S+""")
    }
}
