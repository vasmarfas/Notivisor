package com.vasmarfas.notivisor.headset.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.util.Clipboard

class CopyActivity : Activity() {

    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true

        val text = intent.getStringExtra(EXTRA_TEXT)
        if (!text.isNullOrEmpty()) {
            val ok = Clipboard.write(this, text, intent.getBooleanExtra(EXTRA_SENSITIVE, false))
            Toast.makeText(
                this,
                getString(if (ok) R.string.copy_done else R.string.copy_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) getSystemService(NotificationManager::class.java)?.cancel(id)
        finish()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_SENSITIVE = "sensitive"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun intent(context: Context, text: String, sensitive: Boolean): Intent =
            Intent(context, CopyActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SENSITIVE, sensitive)
    }
}
