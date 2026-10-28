package com.chuckiehelper.mobile.nativeui

import java.io.IOException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudflareLoadingTest {
    private fun account(id: String = "one", token: String = "token") = obj("id" to id, "name" to id,
        "accountId" to "a".repeat(32), "apiToken" to token, "accessKeyId" to "", "secretAccessKey" to "", "bucket" to "")
    private class Accounts(val cached: JSONArray, val fresh: CompletableDeferred<JSONArray>, override val subject: String = "user") : CloudflareAccountSource {
        override fun cachedCloudflareAccounts() = JSONArray(cached.toString())
        override suspend fun cloudflareAccounts() = JSONArray(fresh.await().toString())
        override suspend fun updateCloudflareAccounts(transform: (JSONArray, Boolean) -> JSONArray) = error("not used")
    }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun cachedAccountsAndResourcesAppearBeforeCloudOrNetworkRepliesAndOldChecksAreSkipped() = runBlocking {
        val rows=JSONArray().put(account())
        val cache=CloudflareListingCache()
        val firstScope=scope()
        try {
            val first=CloudflareModel(NativeApi("http://test"),firstScope,{},Accounts(rows,CompletableDeferred(rows)),cache) { path,_ ->
                assertEquals("/api/cloudflare/execute",path)
                obj("data" to obj("items" to JSONArray().put(obj("name" to "cached.example.com"))))
            }
            first.initialize()
            assertEquals("cached.example.com",first.items.single().getString("name"))
        } finally { firstScope.cancel() }
        val fresh=CompletableDeferred<JSONArray>();val reply=CompletableDeferred<JSONObject>();val nextScope=scope()
        try {
            val next=CloudflareModel(NativeApi("http://test"),nextScope,{},Accounts(rows,fresh),cache) { path,_ ->
                assertEquals("/api/cloudflare/execute",path); reply.await()
            }
            next.initialize()
            assertEquals("one",next.account!!.getString("id"))
            assertEquals("cached.example.com",next.items.single().getString("name"))
            assertTrue(next.accountsRefreshing); assertTrue(next.refreshing); assertFalse(next.busy)
            reply.complete(obj("data" to obj("items" to JSONArray().put(obj("name" to "fresh.example.com")))))
            yield()
            assertEquals("fresh.example.com",next.items.single().getString("name"))
            fresh.complete(rows);yield()
            assertFalse(next.accountsRefreshing)
        } finally { nextScope.cancel() }
    }

    @Test fun slowReadsDoNotPreventTabSwitchingAndLateRepliesCannotReplaceTheNewTab() = runBlocking {
        val rows=JSONArray().put(account());val pending=CompletableDeferred<JSONObject>();val taskScope=scope()
        try {
            val model=CloudflareModel(NativeApi("http://test"),taskScope,{},Accounts(rows,CompletableDeferred(rows)),CloudflareListingCache()) { _,body ->
                if(body!!.getString("operation")=="zones") withContext(NonCancellable) { pending.await() }
                else obj("data" to obj("items" to JSONArray().put(obj("name" to "database"))))
            }
            model.initialize();assertTrue(model.refreshing);assertFalse(model.busy)
            model.selectTab(2)
            assertEquals(2,model.tab);assertEquals("database",model.items.single().getString("name"))
            pending.complete(obj("data" to obj("items" to JSONArray().put(obj("name" to "late-zone")))));yield()
            assertEquals("database",model.items.single().getString("name"));assertFalse(model.refreshing)
        } finally { taskScope.cancel() }
    }

    @Test fun failedRefreshKeepsVisibleRowsAndChangingCredentialsDoesNotReuseOldResources() = runBlocking {
        val cache=CloudflareListingCache();val rows=JSONArray().put(account());val taskScope=scope()
        try {
            val first=CloudflareModel(NativeApi("http://test"),taskScope,{},Accounts(rows,CompletableDeferred(rows)),cache) { _,_ ->
                obj("data" to obj("items" to JSONArray().put(obj("name" to "old"))))
            }
            first.initialize()
            val offline=CloudflareModel(NativeApi("http://test"),taskScope,{},Accounts(rows,CompletableDeferred(rows)),cache) { _,_ -> throw IOException("offline") }
            offline.initialize()
            assertEquals("old",offline.items.single().getString("name"));assertEquals("offline",offline.error)
            val changed=JSONArray().put(account(token="new-token"));val waiting=CompletableDeferred<JSONObject>()
            val newer=CloudflareModel(NativeApi("http://test"),taskScope,{},Accounts(changed,CompletableDeferred(changed)),cache) { _,_ -> waiting.await() }
            newer.initialize();assertTrue(newer.items.isEmpty());assertTrue(newer.refreshing)
        } finally { taskScope.cancel() }
    }

    @Test fun legacyDiscoveryIsIndependentOfFirstTimeCloudLoadingAndOtherUsersHaveNoSharedCache() = runBlocking {
        val fresh=CompletableDeferred<JSONArray>();val legacy=CompletableDeferred<JSONObject>();val taskScope=scope()
        try {
            val model=CloudflareModel(NativeApi("http://test"),taskScope,{},Accounts(JSONArray(),fresh),CloudflareListingCache()) { path,_ ->
                assertEquals("/api/cloudflare/accounts",path);legacy.await()
            }
            model.initialize();assertTrue(model.accountsRefreshing);assertFalse(model.busy)
            fresh.complete(JSONArray());yield()
            assertFalse(model.accountsRefreshing);assertFalse(model.busy);assertEquals(0,model.legacyCount)
            legacy.complete(obj("data" to JSONArray().put(account())));yield();assertEquals(1,model.legacyCount)
            val one=CloudflarePageCache.forOwner("first")
            one.put("resource",CfListingSnapshot(items="[{\"name\":\"private\"}]"))
            val two=CloudflarePageCache.forOwner("second")
            assertNull(two.get("resource"));assertNull(one.get("resource"))
        } finally { taskScope.cancel() }
    }
}
