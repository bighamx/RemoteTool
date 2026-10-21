@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.chuckiehelper.mobile.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import java.lang.ref.WeakReference

/** Handle IME toolbar commands directly, instead of Compose 1.7's synthesized cut/copy/paste keys. */
@Composable
internal fun ImeEditingCompat(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val interceptor = remember(clipboard) {
        PlatformTextInputInterceptor { request, next ->
            val connections = mutableListOf<WeakReference<ImeEditingConnection>>()
            try {
                next.startInputMethod(PlatformTextInputMethodRequest { attributes ->
                    ImeEditingConnection(request.createInputConnection(attributes), clipboard, attributes.inputType)
                        .also { connections.add(WeakReference(it)) }
                })
            } finally {
                connections.forEach { it.get()?.deactivate() }
            }
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

internal class ImeEditingConnection(
    private val editor: InputConnection,
    private val clipboard: ClipboardManager,
    inputType: Int,
) : InputConnectionWrapper(editor, false) {
    private var active = true
    private val password = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> (inputType and InputType.TYPE_MASK_VARIATION) in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        InputType.TYPE_CLASS_NUMBER -> (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }

    fun deactivate() { active = false }
    override fun closeConnection() { deactivate(); super.closeConnection() }

    override fun performContextMenuAction(id: Int): Boolean {
        if (!active) return false
        return when (id) {
            android.R.id.selectAll -> {
                val text = editor.getExtractedText(ExtractedTextRequest(), 0)?.text ?: return false
                editor.setSelection(0, text.length)
            }
            android.R.id.cut, android.R.id.copy -> {
                if (password) return false
                val selected = editor.getSelectedText(0)?.takeIf { it.isNotEmpty() } ?: return false
                clipboard.setPrimaryClip(ClipData.newPlainText("", selected))
                if (id == android.R.id.copy) true else {
                    // Replacing the selection deletes exactly those characters, including a full selection.
                    // Finish composition first so an old composing region cannot restore the removed text.
                    editor.finishComposingText()
                    editor.commitText("", 1)
                }
            }
            android.R.id.paste -> {
                val clip = clipboard.primaryClip ?: return false
                if (clip.itemCount == 0) return false
                // Text only: URI grants and attachments keep their existing dedicated import paths.
                val text = clip.getItemAt(0).text ?: return super.performContextMenuAction(id)
                editor.finishComposingText()
                editor.commitText(text, 1)
            }
            else -> super.performContextMenuAction(id)
        }
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (!active) return false
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_CUT -> android.R.id.cut
            KeyEvent.KEYCODE_COPY -> android.R.id.copy
            KeyEvent.KEYCODE_PASTE -> android.R.id.paste
            KeyEvent.KEYCODE_X -> if (event.isCtrlPressed) android.R.id.cut else null
            KeyEvent.KEYCODE_C -> if (event.isCtrlPressed) android.R.id.copy else null
            KeyEvent.KEYCODE_V -> if (event.isCtrlPressed) android.R.id.paste else null
            KeyEvent.KEYCODE_A -> if (event.isCtrlPressed) android.R.id.selectAll else null
            else -> null
        } ?: return super.sendKeyEvent(event)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> performContextMenuAction(action)
            KeyEvent.ACTION_UP -> true
            else -> false
        }
    }
}
