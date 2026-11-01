package com.chuckiehelper.mobile.nativeui
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LocalStorageTest {
    @Test fun previewCleanupIsSelectiveAndChangedFilesArePreserved()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder=File(context.cacheDir,"hermes-open").apply{mkdirs()}
        val target=File(folder,"target.pdf").apply{writeText("original")}
        val other=File(folder,"other.pdf").apply{writeText("keep")}
        val credentials=File(context.filesDir,"private-auth.json").apply{writeText("never clean")}
        val backend=LocalStorageBackend(context)
        var scan=backend.scan()
        var rows=backend.items(scan.getString("snapshot"),mapOf("category" to "previews"),0).array("data").objects()
        var row=rows.first{it.optString("name").contains("target")||it.optLong("bytes")==8L}
        var plan=backend.prepare(scan.getString("snapshot"),setOf(row.getString("id")),"delete")
        target.appendText(" changed")
        assertEquals(1,backend.execute(plan.getString("plan")).optInt("failed"))
        assertTrue(target.exists());assertTrue(other.exists());assertTrue(credentials.exists())
        scan=backend.scan();rows=backend.items(scan.getString("snapshot"),mapOf("category" to "previews"),0).array("data").objects()
        row=rows.first{it.optLong("bytes")==target.length()}
        plan=backend.prepare(scan.getString("snapshot"),setOf(row.getString("id")),"delete")
        val result=backend.execute(plan.getString("plan"));assertEquals(1,result.optInt("processed"))
        assertFalse(target.exists());assertTrue(other.exists());assertTrue(credentials.exists())
        assertEquals(result.toString(),backend.execute(plan.getString("plan")).toString())
        assertFalse(storageSafeFile(folder,File(folder,"../private-auth.json")))
        val linux=JSONObject("""{"platform":"Linux","types":[{"id":"shell","name":"Shell"},{"id":"cmd","name":"CMD"}]}""")
        assertEquals(listOf("shell"),terminalChoices(linux).map{it.first})
    }
}
