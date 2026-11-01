package com.chuckiehelper.mobile.nativeui
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class StorageUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun cleanupRequiresExplicitConfirmationAndCannotSelectProtectedData(){
        var executed=0;var prepared=emptySet<String>()
        val backend=object:StorageBackend{
            override suspend fun scan()=obj("snapshot" to "s","totalBytes" to 20,"categories" to JSONArray())
            override suspend fun items(snapshot:String,filter:Map<String,String>,offset:Int)=obj("data" to JSONArray(listOf(
                obj("id" to "a","name" to "可清理.pdf","bytes" to 10,"protected" to false),obj("id" to "b","name" to "正在使用.pdf","bytes" to 10,"protected" to true,"reason" to "会话正在使用"))),"total" to 2,"hasMore" to false)
            override suspend fun prepare(snapshot:String,ids:Set<String>,action:String):JSONObject{prepared=ids;return obj("plan" to "p","count" to ids.size,"bytes" to 10,"warning" to "可恢复的回收站操作","action" to action)}
            override suspend fun execute(plan:String):JSONObject{executed++;return obj("processed" to 1,"freedBytes" to 0,"failed" to 0)}
        }
        compose.setContent{MaterialTheme{StoragePane(backend,true,"测试服务端") {}}}
        compose.waitUntil(5000){compose.onAllNodesWithText("可清理.pdf").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("全选已显示").performClick()
        compose.onNodeWithText("移入回收站").performClick()
        compose.onNodeWithText("可恢复的回收站操作",substring=true).assertIsDisplayed()
        assertEquals(setOf("a"),prepared);assertEquals(0,executed)
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick()
        assertEquals(0,executed)
    }
}
