package com.chuckiehelper.mobile.nativeui

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CloudQrCoordinatorTest {
    private val secret = CloudQrProtocol.secret()
    private val id = "a".repeat(32)
    private fun url(flow: String) = "https://api.xbyham.com/QrLogin/Scan#v=1&flow=$flow&code=$secret"
    private fun pending(flow: String, now: Long) = JSONObject().put("request_id",id).put("flow",flow)
        .put("status",if (flow=="offer") "claimed" else "pending").put("qr_ticket",secret).put("confirmation_code","000123")
        .put("qr_payload",url("receive")).put("expires_in",180).put("expires_at",Instant.ofEpochMilli(now+180_000).toString()).put("interval",3)
    private fun approval(now: Long) = JSONObject().put("request_id",id).put("flow","receive").put("status","pending")
        .put("target_kind","web").put("application_name","网站").put("client_name","浏览器")
        .put("permissions",JSONArray().put("登录网站账户")).put("confirmation_code","000123")
        .put("expires_in",180).put("expires_at",Instant.ofEpochMilli(now+180_000).toString())

    @Test fun pollingBackoffAndTokenFieldsFollowProtocol() = runBlocking {
        var now = 1_000_000L
        val waits = mutableListOf<Long>()
        val calls = mutableListOf<CloudQrRequest>()
        var polls = 0; var completed = false
        val owner = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val qr = CloudQrCoordinator(owner,"client","openid",true,true,CloudQrTransport { call ->
            calls += call
            when (call.path) {
                CloudQrProtocol.API+"/requests" -> pending("receive",now)
                "/connect/token" -> when (polls++) {
                    0 -> throw CloudHttpFailure(400,JSONObject().put("error","authorization_pending"))
                    1 -> throw CloudHttpFailure(400,JSONObject().put("error","slow_down"))
                    2 -> throw CloudHttpFailure(429,null,12_000)
                    else -> JSONObject().put("access_token","token")
                }
                else -> JSONObject()
            }
        }, { error("unused") }, { null }, { _,_,_,current -> completed=current() }, { now }, { waits+=it; now+=it })
        try {
            qr.create()
            assertTrue(completed)
            assertEquals(listOf(3000L,8000L,12000L),waits)
            val form = calls.first { it.path=="/connect/token" }.form!!
            assertEquals(setOf("grant_type","client_id","qr_ticket","code_verifier","wait_seconds"),form.keys)
            assertEquals("20",form["wait_seconds"])
            assertEquals(CloudQrProtocol.GRANT,form["grant_type"])
            assertEquals(CloudQrProtocol.challenge(form.getValue("code_verifier")),calls.first().json!!.getString("code_challenge"))
            assertNull(qr.waiting)
        } finally { qr.close(); owner.cancel() }
    }
    @Test fun inspectionDoesNotApproveAndDecisionIsSentOnlyOnce() = runBlocking {
        var decisions = 0
        val now=1_000_000L
        val owner = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val qr = CloudQrCoordinator(owner,"client","openid",true,true,CloudQrTransport { call ->
            assertEquals("access",call.accessToken)
            if (call.path.endsWith("inspect")) approval(now) else {
                decisions++
                assertEquals("allow",call.json!!.getString("decision"))
                assertEquals("000123",call.json.getString("confirmation_code"))
                JSONObject().put("request_id",id).put("status","approved")
            }
        }, { CloudQrIdentity("user","name","access") }, { "user" }, { _,_,_,_ -> }, { now })
        try {
            qr.inspect(url("receive")); assertNotNull(qr.approval); assertEquals(0,decisions)
            qr.decide(true); qr.decide(true)
            assertEquals(1,decisions); assertNull(qr.approval)
        } finally { qr.close(); owner.cancel() }
    }
    @Test fun disabledFeaturesNeverContactServer() {
        val owner = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val qr=CloudQrCoordinator(owner,"client","openid",false,false,CloudQrTransport { error("must not call") },
            { error("must not call") }, { null }, { _,_,_,_ -> error("must not call") })
        try { qr.create(); qr.claim(url("offer")); qr.inspect(url("receive")); assertFalse(qr.busy) }
        finally { qr.close(); owner.cancel() }
    }
    @Test fun lostDecisionResponseIsNotAutomaticallyReplayed() = runBlocking {
        var decisions=0
        val now=1_000_000L
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val qr=CloudQrCoordinator(owner,"client","openid",true,true,CloudQrTransport { call ->
            if (call.path.endsWith("inspect")) approval(now) else { decisions++; throw java.io.IOException("lost") }
        }, { CloudQrIdentity("user","name","access") }, { "user" }, { _,_,_,_ -> }, { now })
        try {
            qr.inspect(url("receive")); qr.decide(true); qr.decide(true)
            assertEquals(1,decisions); assertNull(qr.approval); assertNotNull(qr.error)
        } finally { qr.close(); owner.cancel() }
    }
    @Test fun changingAccountBeforeDecisionPreventsAuthorization() = runBlocking {
        var user="original"; var decisions=0
        val now=1_000_000L
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val qr=CloudQrCoordinator(owner,"client","openid",true,true,CloudQrTransport { call ->
            if (call.path.endsWith("inspect")) approval(now) else { decisions++; JSONObject() }
        }, { CloudQrIdentity(user,"name","access") }, { user }, { _,_,_,_ -> }, { now })
        try {
            qr.inspect(url("receive")); user="other"; qr.decide(true)
            assertEquals(0,decisions); assertNull(qr.approval); assertNotNull(qr.error)
        } finally { qr.close(); owner.cancel() }
    }
    @Test fun offerClaimsOnceAndUnknownTokenResultStopsPolling() = runBlocking {
        var claims=0; var polls=0
        val now=1_000_000L
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val qr=CloudQrCoordinator(owner,"client","openid",true,true,CloudQrTransport { call ->
            when {
                call.path.endsWith("/claim") -> {
                    claims++; assertEquals(secret,call.json!!.getString("scan_code")); assertNull(call.accessToken)
                    pending("offer",now)
                }
                call.path=="/connect/token" -> { polls++; throw java.io.IOException("lost") }
                else -> JSONObject()
            }
        }, { error("unused") }, { null }, { _,_,_,_ -> fail("must not complete") }, { now }, { })
        try {
            qr.claim(url("offer"))
            assertEquals(1,claims); assertEquals(1,polls); assertNull(qr.waiting); assertNotNull(qr.error)
        } finally { qr.close(); owner.cancel() }
    }
    @Test fun cancelStopsPollingAndSendsPrivateCancellation() = runBlocking {
        val owner = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val cancelled=CompletableDeferred<CloudQrRequest>()
        var polls=0
        val now=1_000_000L
        val qr=CloudQrCoordinator(owner,"client","openid",true,true,CloudQrTransport { call ->
            when {
                call.path.endsWith("/requests") -> pending("receive",now)
                call.path.endsWith("/cancel") -> { cancelled.complete(call); JSONObject() }
                else -> { polls++; awaitCancellation() }
            }
        }, { error("unused") }, { null }, { _,_,_,_ -> }, { now }, { awaitCancellation() })
        try {
            qr.create(); assertNotNull(qr.waiting); qr.cancel()
            val call=withTimeout(3000) { cancelled.await() }
            assertNull(call.accessToken); assertEquals(secret,call.json!!.getString("qr_ticket"))
            assertNull(qr.waiting); assertEquals(1,polls)
        } finally { qr.close(); owner.cancel() }
    }

    @Test fun normalLongPollReconnectsImmediatelyWithoutThreeSecondGap() = runBlocking {
        var now=1_000_000L; var polls=0
        val starts=mutableListOf<Long>()
        val delays=mutableListOf<Long>()
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val qr=CloudQrCoordinator(owner,"client","openid",true,true,CloudQrTransport { call ->
            if (call.path.endsWith("/requests")) pending("receive",now).put("default_wait_seconds",20) else {
                starts+=now
                assertEquals("20",call.form!!["wait_seconds"])
                if (polls++ == 0) {
                    now+=20_000
                    throw CloudHttpFailure(400,JSONObject().put("error","authorization_pending"))
                }
                JSONObject()
            }
        }, { error("unused") }, { null }, { _,_,_,_ -> }, { now }, { delays+=it; now+=it })
        try {
            qr.create()
            assertEquals(listOf(1_000_000L,1_020_000L),starts)
            assertTrue(delays.isEmpty()); assertNull(qr.error)
        } finally { qr.close(); owner.cancel() }
    }

    @Test fun shortPollingStillWaitsAndNeverSpins() = runBlocking {
        var now=1_000_000L; var polls=0
        val delays=mutableListOf<Long>()
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val qr=CloudQrCoordinator(owner,"client","openid",true,true,CloudQrTransport { call ->
            if (call.path.endsWith("/requests")) pending("receive",now).put("default_wait_seconds",0) else {
                assertEquals("0",call.form!!["wait_seconds"])
                if (polls++ == 0) throw CloudHttpFailure(400,JSONObject().put("error","authorization_pending"))
                JSONObject()
            }
        }, { error("unused") }, { null }, { _,_,_,_ -> }, { now }, { delays+=it; now+=it })
        try {
            qr.create(); assertEquals(listOf(3000L,3000L),delays); assertNull(qr.error)
        } finally { qr.close(); owner.cancel() }
    }
}
