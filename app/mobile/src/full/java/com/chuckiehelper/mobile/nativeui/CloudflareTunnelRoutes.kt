@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CfIngressRule(val hostname: String = "", val path: String = "", val service: String = "http://localhost:8080",
    val original: String = "{}", val id: String = UUID.randomUUID().toString())

internal fun cfRouteDomain(host: String, domains: List<String>): String? = domains.sortedByDescending { it.length }
    .firstOrNull { host.equals(it, true) || host.endsWith(".$it", true) }
internal fun cfRoutePrefix(host: String, domain: String?): String = when {
    domain == null -> host
    host.equals(domain, true) -> ""
    host.endsWith(".$domain", true) -> host.dropLast(domain.length + 1)
    else -> host
}
internal fun cfRouteHost(prefix: String, domain: String?): String {
    val host=prefix.trim().trimEnd('.')
    return when {
        domain == null -> prefix
        host.isBlank() -> domain
        host.equals(domain,true) || host.endsWith(".$domain",true) -> host
        else -> "$host.$domain"
    }
}

internal fun cfIngressRules(config: JSONObject): Pair<List<CfIngressRule>, CfIngressRule> {
    val values = config.optJSONArray("ingress")?.objects().orEmpty().map { row ->
        CfIngressRule(row.optString("hostname").takeUnless { it == "null" }.orEmpty(),
            row.optString("path").takeUnless { it == "null" }.orEmpty(),row.optString("service"),row.toString())
    }
    val fallback = values.lastOrNull()?.takeIf { it.hostname.isBlank() && it.path.isBlank() }
    return (if (fallback == null) values else values.dropLast(1)) to (fallback ?: CfIngressRule(service = "http_status:404"))
}

internal fun cfIngressConfig(base: JSONObject, rules: List<CfIngressRule>, fallback: CfIngressRule): JSONObject {
    require(fallback.service.isNotBlank()) { "请填写未匹配请求的处理方式" }
    val seen = mutableSetOf<Pair<String,String>>()
    val ingress = JSONArray()
    for (rule in rules) {
        val host = rule.hostname.trim(); val path = rule.path.trim()
        require(host.isNotBlank() || path.isNotBlank()) { "普通路由至少需要主机名或路径；兜底规则已单独配置" }
        require(rule.service.isNotBlank()) { "请填写目标服务" }
        require(!host.contains("://") && '/' !in host && !host.any { it.isWhitespace() }) { "主机名不应包含协议、斜杠或空格" }
        require(seen.add(host.lowercase() to path)) { "存在重复的主机名和路径，请调整路由" }
        val json = JSONObject(rule.original).put("service",rule.service.trim())
        if (host.isBlank()) json.remove("hostname") else json.put("hostname",host)
        if (path.isBlank()) json.remove("path") else json.put("path",path)
        ingress.put(json)
    }
    ingress.put(JSONObject(fallback.original).apply { remove("hostname");remove("path");put("service",fallback.service.trim()) })
    return JSONObject(base.toString()).put("ingress",ingress)
}

@Composable
internal fun CloudflareTunnelRoutesScreen(config: JSONObject, name: String, busy: Boolean, serverError: String?,
    domains: List<String>,
    onClose: () -> Unit, onSave: (JSONObject) -> Unit) {
    val parsed = remember(config) { cfIngressRules(config) }
    val rules = remember(config) { parsed.first.toMutableStateList() }
    var fallback by remember(config) { mutableStateOf(parsed.second) }
    var expanded by remember(config) { mutableStateOf<String?>(null) }
    var error by remember(config) { mutableStateOf<String?>(null) }
    var pending by remember(config) { mutableStateOf<JSONObject?>(null) }
    var discard by remember { mutableStateOf(false) }
    val selectedDomains = remember(config) { mutableStateMapOf<String,String?>() }
    LaunchedEffect(domains) {
        for (rule in rules) if (!selectedDomains.containsKey(rule.id) && domains.isNotEmpty())
            selectedDomains[rule.id] = cfRouteDomain(rule.hostname, domains) ?: if (rule.hostname.isBlank()) domains.first() else null
    }
    val dirty = rules.toList() != parsed.first || fallback != parsed.second
    fun back() { if (!busy) { if (dirty) discard = true else onClose() } }
    BackHandler { back() }
    Scaffold(topBar = { TopAppBar(title = { Text("Tunnels - $name",maxLines=1,overflow=TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick={back()},enabled=!busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回") } },
        actions = { TextButton(onClick={ try { pending=cfIngressConfig(config,rules,fallback);error=null } catch(failure:IllegalArgumentException) {error=failure.message} },enabled=!busy&&dirty) {Text("保存")} }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: serverError)?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp)) }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("路由 · 按顺序匹配",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                    TextButton(onClick={ val added=CfIngressRule();rules+=added;domains.firstOrNull()?.let{selectedDomains[added.id]=it};expanded=added.id },enabled=!busy) {Text("添加路由")}
                } }
                itemsIndexed(rules,key={_,rule->rule.id}) { index,rule ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable(enabled=!busy){expanded=if(expanded==rule.id)null else rule.id}) {
                                    Text("${index+1}. ${rule.hostname.ifBlank { "全部主机" }}${rule.path.takeIf {it.isNotBlank()}?.let { " · $it" }.orEmpty()}",style=MaterialTheme.typography.titleSmall)
                                    Text(rule.service,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                }
                                IconButton(onClick={rules.removeAt(index);rules.add(index-1,rule)},enabled=!busy&&index>0,modifier=Modifier.size(36.dp)) {Icon(Icons.Outlined.ArrowUpward,"上移")}
                                IconButton(onClick={rules.removeAt(index);rules.add(index+1,rule)},enabled=!busy&&index<rules.lastIndex,modifier=Modifier.size(36.dp)) {Icon(Icons.Outlined.ArrowDownward,"下移")}
                                IconButton(onClick={rules.removeAt(index)},enabled=!busy,modifier=Modifier.size(36.dp)) {Icon(Icons.Outlined.Delete,"删除路由")}
                            }
                            if(expanded==rule.id) {
                                Spacer(Modifier.height(8.dp))
                                val domain = selectedDomains[rule.id]
                                var domainMenu by remember(rule.id) { mutableStateOf(false) }
                                OutlinedTextField(cfRoutePrefix(rule.hostname,domain),{rules[index]=rule.copy(hostname=cfRouteHost(it,domain))},
                                    label={Text(if(domain==null)"主机名" else "子域名")},placeholder={Text(if(domain==null)"app.example.com" else "app")},
                                    suffix={domain?.let{Text(".$it")}},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                                if(domains.isNotEmpty()) Box {
                                    TextButton(onClick={domainMenu=true},enabled=!busy) {Text(domain?.let{".$it"}?:"完整域名");Icon(Icons.Outlined.ArrowDropDown,null)}
                                    DropdownMenu(expanded=domainMenu,onDismissRequest={domainMenu=false}) {
                                        DropdownMenuItem(text={Text("完整域名")},onClick={domainMenu=false;selectedDomains[rule.id]=null})
                                        domains.forEach { selected -> DropdownMenuItem(text={Text(".$selected")},onClick={
                                            domainMenu=false;selectedDomains[rule.id]=selected
                                            if(rule.hostname.isNotBlank()) rules[index]=rule.copy(hostname=cfRouteHost(cfRoutePrefix(rule.hostname,domain),selected))
                                        }) }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(rule.path,{rules[index]=rule.copy(path=it)},label={Text("路径正则（选填）")},placeholder={Text("/api/.*")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                ProtocolAddressField(rule.service,{rules[index]=rule.copy(service=it)},label="目标主机与端口",
                                    protocols=listOf("http","https","tcp","ssh","rdp","smb"),allowCustom=true,enabled=!busy,placeholder="localhost:8080")
                            }
                        }
                    }
                }
                item { Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("未匹配请求",style=MaterialTheme.typography.titleSmall)
                    OutlinedTextField(fallback.service,{fallback=fallback.copy(service=it)},label={Text("兜底服务")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    Row { listOf(404,503).forEach { status -> TextButton(onClick={fallback=fallback.copy(service="http_status:$status")},enabled=!busy) {Text("返回 $status")} } }
                }} }
            }
        }
    }
    pending?.let { changed -> AlertDialog(onDismissRequest={pending=null},title={Text("保存路由")},text={Text("更新 $name 的 ${rules.size} 条路由及兜底规则？")},
        confirmButton={TextButton(onClick={pending=null;onSave(changed)}){Text("保存")}},dismissButton={TextButton(onClick={pending=null}){Text("取消")}}) }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃修改？")},confirmButton={TextButton(onClick={discard=false;onClose()}){Text("放弃")}},dismissButton={TextButton(onClick={discard=false}){Text("继续编辑")}})
}
