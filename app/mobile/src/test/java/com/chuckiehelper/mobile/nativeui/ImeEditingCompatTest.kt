package com.chuckiehelper.mobile.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImeEditingCompatTest {
    private fun fixture(password: Boolean = false): Triple<EditText,ImeEditingConnection,ClipboardManager> {
        val context = RuntimeEnvironment.getApplication()
        val edit = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or if(password)InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
            setText("hello world"); setSelection(0,5)
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("","previous"))
        val connection = edit.onCreateInputConnection(EditorInfo())!!
        return Triple(edit,ImeEditingConnection(connection,clipboard,edit.inputType),clipboard)
    }
    @Test fun cutDeletesExactlyTheSelectedTextAndSupportsPaste() {
        val (edit,connection,clipboard)=fixture()
        assertTrue(connection.performContextMenuAction(android.R.id.cut))
        assertEquals("hello",clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(" world",edit.text.toString())
        assertTrue(connection.performContextMenuAction(android.R.id.paste))
        assertEquals("hello world",edit.text.toString())
    }
    @Test fun selectAllAndCutLeaveAnEmptyEditor() {
        val (edit,connection,_)=fixture()
        assertTrue(connection.performContextMenuAction(android.R.id.selectAll))
        assertTrue(connection.performContextMenuAction(android.R.id.cut))
        assertEquals("",edit.text.toString())
    }
    @Test fun passwordAndDeactivatedConnectionsCannotCopyOrCut() {
        val (edit,connection,clipboard)=fixture(password=true)
        assertFalse(connection.performContextMenuAction(android.R.id.copy))
        assertFalse(connection.performContextMenuAction(android.R.id.cut))
        assertEquals("hello world",edit.text.toString())
        assertEquals("previous",clipboard.primaryClip!!.getItemAt(0).text.toString())
        connection.deactivate()
        assertFalse(connection.performContextMenuAction(android.R.id.paste))
    }
}
