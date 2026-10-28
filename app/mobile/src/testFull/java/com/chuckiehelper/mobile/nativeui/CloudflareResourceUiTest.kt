package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudflareResourceUiTest {
    @Test fun routeDomainsUseLongestOwnedSuffixAndAvoidDuplicatingPastedDomains() {
        val domains=listOf("example.com","sub.example.com")
        assertEquals("sub.example.com",cfRouteDomain("api.sub.example.com",domains))
        assertNull(cfRouteDomain("notexample.com",domains))
        assertEquals("*",cfRoutePrefix("*.example.com","example.com"))
        assertEquals("api.example.com",cfRouteHost("api","example.com"))
        assertEquals("api.example.com",cfRouteHost("api.example.com","example.com"))
        assertEquals("example.com",cfRouteHost("","example.com"))
        assertEquals("custom.net",cfRoutePrefix("custom.net",null))
    }
    @Test fun ingressEditsPreserveBindingsUnknownFieldsFallbackAndOrder() {
        val base=JSONObject("""{"warp-routing":{"enabled":true},"future":{"x":1},"originRequest":{"connectTimeout":"30s"},"ingress":[{"hostname":"a.example.com","service":"http://localhost:80","originRequest":{"noTLSVerify":true},"extra":"keep"},{"hostname":"b.example.com","path":"/v1/.*","service":"http://localhost:81"},{"service":"http_status:404","originRequest":{"httpHostHeader":"fallback"}}]}""")
        val before=base.toString()
        val (rows,fallback)=cfIngressRules(base)
        val edited=listOf(rows[1].copy(service="https://localhost:443"),rows[0].copy(hostname="new.example.com"))
        val result=cfIngressConfig(base,edited,fallback.copy(service="http_status:503"))
        assertEquals(before,base.toString())
        assertTrue(result.getJSONObject("warp-routing").getBoolean("enabled"))
        assertEquals(1,result.getJSONObject("future").getInt("x"))
        val saved=result.getJSONArray("ingress")
        assertEquals("b.example.com",saved.getJSONObject(0).getString("hostname"))
        assertTrue(saved.getJSONObject(1).getJSONObject("originRequest").getBoolean("noTLSVerify"))
        assertEquals("keep",saved.getJSONObject(1).getString("extra"))
        assertEquals("http_status:503",saved.getJSONObject(2).getString("service"))
        assertFalse(saved.getJSONObject(2).has("hostname"))
        assertEquals("fallback",saved.getJSONObject(2).getJSONObject("originRequest").getString("httpHostHeader"))
        assertEquals(2,cfIngressConfig(base,listOf(rows[1]),fallback).getJSONArray("ingress").length())
    }
    @Test fun ingressRejectsBlankRoutesAndDuplicatesWhileAllowingPathOnlyRoutes() {
        val fallback=CfIngressRule(service="http_status:404")
        fun rejected(rows:List<CfIngressRule>) { try {cfIngressConfig(JSONObject(),rows,fallback);fail("invalid route accepted")}catch(_:IllegalArgumentException){} }
        rejected(listOf(CfIngressRule()))
        rejected(listOf(CfIngressRule(hostname="https://bad.example.com")))
        rejected(listOf(CfIngressRule(hostname="A.example.com"),CfIngressRule(hostname="a.example.com")))
        val result=cfIngressConfig(JSONObject(),listOf(CfIngressRule(path="/api/.*")),fallback)
        assertFalse(result.getJSONArray("ingress").getJSONObject(0).has("hostname"))
        assertEquals("http_status:404",cfIngressRules(JSONObject()).second.service)
    }
    @Test fun listingKeysIsolateAccountsResourcesFiltersAndLiteralR2Paths() {
        val first=CfLocation("r2.list","bucket",JSONObject().put("bucket","one").put("prefix","a/"))
        val reordered=first.copy(args=JSONObject().put("prefix","a/").put("bucket","one"))
        assertEquals(first.key("account"),reordered.key("account"))
        assertNotEquals(first.key("account"),first.key("other"))
        assertNotEquals(first.key("account"),first.copy(args=JSONObject(first.args.toString()).put("prefix","A/")).key("account"))
        assertNotEquals(first.key("account"),first.copy(args=JSONObject(first.args.toString()).put("prefix","a/../")).key("account"))
        val dns=CfLocation("dns.list","example.com",JSONObject().put("zone","z"))
        assertNotEquals(dns.key("a"),dns.copy(args=JSONObject(dns.args.toString()).put("query","mail")).key("a"))
        assertEquals("D1 - users",CfLocation("d1.rows","users").displayTitle)
    }
    @Test fun cacheOwnsImmutablePagesAndEvictsTheLeastRecentlyUsedEntry() {
        val cache=CloudflareListingCache(capacity=2,maxChars=1000)
        val row=JSONObject().put("key","a/").put("name","a/")
        val snapshot=cfListingSnapshot("r2.list",JSONObject().put("items",JSONArray().put(row)).put("cursor","next"))
        cache.put("a",snapshot);row.put("name","changed")
        assertEquals("a/",JSONArray(cache.get("a")!!.items).getJSONObject(0).getString("name"))
        cache.put("b",CfListingSnapshot());cache.get("a");cache.put("c",CfListingSnapshot())
        assertNull(cache.get("b"));assertNotNull(cache.get("a"))
        val second=cfListingSnapshot("r2.list",JSONObject().put("items",JSONArray().put(JSONObject().put("key","a/")).put(JSONObject().put("key","file"))),2,JSONArray(snapshot.items).objects())
        assertEquals(2,JSONArray(second.items).length());assertEquals(2,second.page);assertFalse(second.hasMore)
        cache.clear();assertNull(cache.get("a"))
    }
}
