package com.brownllama.continuum.sticker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.brownllama.continuum.R

/**
 * Opened from the sticker panel's +: shows Android's photo picker (no storage permission needed),
 * turns the picked image into a sticker, and closes. It has no screen of its own.
 */
class AddStickerActivity : ComponentActivity() {

    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }
        val store = StickerStore(applicationContext)
        store.add(uri) { ok ->
            Toast.makeText(
                applicationContext, if (ok) R.string.sticker_added else R.string.sticker_add_failed, Toast.LENGTH_SHORT,
            ).show()
            store.shutdown()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}
