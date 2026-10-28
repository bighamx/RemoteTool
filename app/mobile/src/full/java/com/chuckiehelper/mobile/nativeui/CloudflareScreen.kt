@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect

private data class CfEditor(val title: String, val fields: List<Pair<String,String>>, val initial: JSONObject = JSONObject(), val note: String? = null,
    val secrets: Set<String> = emptySet(), val multiline: Set<String> = emptySet(), val remove: (() -> Unit)? = null, val save: (JSONObject) -> Unit)
private data class CfConfirm(val title: String, val message: String, val action: () -> Unit)

@Composable
internal fun CloudflareScreen(api: NativeApi, onError: (String) -> Unit, onClose: () -> Unit) {
    val cloud: CloudAccountModel = androidx.lifecycle.viewmodel.compose.viewModel()
    key(cloud.subject) { CloudflareContent(api, cloud, onError, onClose) }
}

@Composable
private fun CloudflareContent(api: NativeApi, cloud: CloudAccountModel, onError: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(api, cloud.subject) { CloudflareModel(api, scope, onError, cloud) }
    var editor by remember { mutableStateOf<CfEditor?>(null) }
    var confirm by remember { mutableStateOf<CfConfirm?>(null) }
    var inspect by remember { mutableStateOf<JSONObject?>(null) }
    var menu by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<JSONObject?>(null) }
    var routeConfig by remember { mutableStateOf<JSONObject?>(null) }
    val scrollPositions=remember(model){mutableMapOf<String,Pair<Int,Int>>()}
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.upload(context, it) } }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = download; if (uri != null && file != null) model.download(context, file, uri); download = null
    }
    LaunchedEffect(model) { model.initialize() }
    BackHandler { model.back(onClose) }
    val where = model.location
    val args = where.args
    fun accountEditor() {
        editor = CfEditor(if (model.account == null) "添加账户" else "账户配置", listOf(
            "name" to "名称", "accountId" to "Account ID", "apiToken" to "API Token", "accessKeyId" to "R2 Access Key（选填）",
            "secretAccessKey" to "R2 Secret Key（选填）", "bucket" to "默认桶（选填）"),
            model.account ?: JSONObject(), note = "保存到当前云账户，切换设备仍可使用。", secrets = setOf("apiToken","accessKeyId","secretAccessKey"), remove = model.account?.let { current ->
                { confirm=CfConfirm("移除账户",current.optString("name")) { model.removeAccount() } }
            }) { model.saveAccount(it) }
    }
    fun dnsEditor(row: JSONObject? = null) {
        val initial = JSONObject((row ?: JSONObject()).toString())
            .put("zone", args.getString("zone")).put("record", row?.optString("id").orEmpty())
        if (row == null) initial.put("type","A").put("ttl",1).put("proxied",false)
        editor = CfEditor(if (row == null) "添加 DNS 记录" else "编辑 DNS 记录", listOf("type" to "类型", "name" to "名称", "content" to "内容",
            "ttl" to "TTL（1 为自动）", "proxied" to "代理（true / false）", "priority" to "MX 优先级", "comment" to "备注"), initial, multiline = setOf("content")) {
            it.put("ttl", it.getString("ttl").toInt())
            it.put("proxied", it.getString("proxied").toBooleanStrict())
            it.optString("priority").takeIf { value -> value.isNotBlank() && value != "null" }?.let { value -> it.put("priority", value.toInt()) } ?: it.remove("priority")
            model.mutate("dns.save", it)
        }
    }
    fun sqlEditor() {
        val initial = JSONObject(args.toString()).put("sql", if (args.has("table")) "SELECT * FROM \"${args.getString("table").replace("\"","\"\"")}\" LIMIT 100" else "SELECT 1").put("parameters","[]")
        editor = CfEditor("SQL", listOf("sql" to "SQL", "parameters" to "参数（JSON 数组）"), initial, multiline = setOf("sql","parameters")) {
            val sql = it.getString("sql"); val parameters = it.getString("parameters"); JSONArray(parameters)
            if (cloudflareReadOnlySql(sql)) model.query(sql, parameters)
            else confirm = CfConfirm("执行 SQL", "此语句可能修改数据，确认执行？") { model.query(sql, parameters) }
        }
    }
    routeConfig?.let { config ->
        LaunchedEffect(model, config) { model.loadRouteDomains() }
        CloudflareTunnelRoutesScreen(config,where.title,model.busy,model.error,model.routeDomains,onClose={routeConfig=null},onSave={changed->model.saveTunnelConfig(changed){routeConfig=null}})
        return
    }
    val listingKey=model.listingKey
    val listState=remember(listingKey){
        val saved=scrollPositions[listingKey]
        LazyListState(saved?.first?:0,saved?.second?:0)
    }
    var scrollReady by remember(listingKey){mutableStateOf(false)}
    LaunchedEffect(listingKey) {
        snapshotFlow { !model.busy&&(model.items.isNotEmpty()||model.results.isNotEmpty()||model.detail!=null) }.first{it}
        scrollPositions[listingKey]?.let{(index,offset)->listState.scrollToItem(index,offset)}
        scrollReady=true
        snapshotFlow{listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset}.collect{if(!model.busy)scrollPositions[listingKey]=it}
    }
    DisposableEffect(listingKey){onDispose{if(scrollReady)scrollPositions[listingKey]=listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset}}
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (model.stack.isEmpty()) "Cloudflare" else where.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { model.back(onClose) }, enabled = !model.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = { model.reload() }, enabled = !model.busy && model.account != null) { Icon(Icons.Outlined.Refresh, "刷新") }
                IconButton(onClick = { if (cloud.loggedIn) accountEditor() else cloud.open() }, enabled = !model.busy) { Icon(Icons.Outlined.Settings, "账户配置") }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { menu = true }, enabled = !model.busy && model.accounts.isNotEmpty()) {
                        Text(model.account?.optString("name") ?: if(model.accountsRefreshing)"加载账户…" else "未添加账户"); Icon(Icons.Outlined.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        model.accounts.forEach { account -> DropdownMenuItem(text = { Text(account.optString("name")) }, onClick = { menu = false; model.choose(account) }) }
                    }
                }
                if (!cloud.loggedIn) TextButton(onClick = { cloud.open() }) { Text("登录云账户") }
                else TextButton(onClick = { editor = CfEditor("添加账户", listOf("name" to "名称", "accountId" to "Account ID", "apiToken" to "API Token", "accessKeyId" to "R2 Access Key（选填）", "secretAccessKey" to "R2 Secret Key（选填）", "bucket" to "默认桶（选填）"), note = "保存到当前云账户，切换设备仍可使用。", secrets = setOf("apiToken","accessKeyId","secretAccessKey")) { model.saveAccount(it) } }, enabled = !model.busy) { Text("添加") }
            }
            if (model.legacyCount > 0 && model.accounts.isEmpty() && !model.busy && cloud.loggedIn)
                TextButton(onClick = { confirm = CfConfirm("导入已有账户", "将当前设备的 ${model.legacyCount} 个 Cloudflare 账户保存到当前云账户。") { model.importLegacy() } }, modifier=Modifier.padding(horizontal=12.dp)) { Text("导入当前设备已有账户") }
            TabRow(selectedTabIndex = model.tab) {
                listOf("DNS 记录","R2 储存","D1 数据库","Tunnels").forEachIndexed { index, title ->
                    Tab(selected = model.tab == index, onClick = { model.selectTab(index) }, enabled = !model.busy && model.account != null, text = { Text(title) })
                }
            }
            if (model.busy || model.refreshing || model.accountsRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            model.info?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            if (model.account == null && !model.busy) {
                Text(if(model.accountsRefreshing)"正在加载云账户…" else "添加 Cloudflare 账户以管理资源", Modifier.padding(20.dp)); return@Column
            }
            if(where.operation=="dns.list")CfDnsControls(args,model.busy,model::dnsFilter){dnsEditor()}
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (where.operation) {
                    "r2.buckets" -> TextButton(onClick = {
                        editor = CfEditor("打开存储桶", listOf("bucket" to "桶名称"), JSONObject().put("bucket",model.account?.optString("bucket"))) {
                            val bucket = it.getString("bucket").trim(); require(bucket.isNotBlank()) { "请填写桶名称" }
                            model.open(CfLocation("r2.list",bucket,JSONObject().put("bucket",bucket).put("prefix","")))
                        }
                    }, enabled = !model.busy) { Text("指定桶") }
                    "r2.list" -> TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !model.busy) { Text("上传文件") }
                    "d1.tables", "d1.rows" -> {
                        TextButton(onClick = { sqlEditor() }, enabled = !model.busy) { Text("SQL 查询") }
                        if (where.operation == "d1.rows") TextButton(onClick = { model.schema() }, enabled = !model.busy) { Text("表结构") }
                        if (where.operation == "d1.tables") TextButton(onClick={confirm=CfConfirm("删除数据库", "删除 ${where.title} 及其中全部数据？") {model.removeDatabase()}},enabled=!model.busy) {Text("删除数据库",color=MaterialTheme.colorScheme.error)}
                    }
                    "d1.list" -> TextButton(onClick={editor=CfEditor("创建数据库",listOf("name" to "名称")) {model.mutate("d1.create",it)}},enabled=!model.busy) {Text("创建数据库")}
                    "tunnels.list" -> TextButton(onClick = {
                        editor = CfEditor("创建隧道",listOf("name" to "名称")) { model.mutate("tunnels.create",it) }
                    }, enabled = !model.busy) { Text("创建隧道") }
                }
            }
            LazyColumn(Modifier.weight(1f), state=listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(when(where.operation){"d1.tables"->2.dp;"dns.list"->4.dp;else->8.dp})) {
                if (where.operation == "tunnels.detail") {
                    model.detail?.let { snapshot ->
                        val tunnel = snapshot.optJSONObject("tunnel") ?: JSONObject()
                        item { SelectionContainer { Text("${tunnel.optString("name")} · ${tunnel.optString("status")}\n${tunnel.optString("id")}", Modifier.padding(8.dp)) } }
                        item {
                            Column {
                                Row {
                                    TextButton(onClick = { editor = CfEditor("重命名",listOf("name" to "名称"),JSONObject(args.toString()).put("name",tunnel.optString("name"))) { model.mutate("tunnels.rename",it) } }, enabled = !model.busy) { Text("重命名") }
                                    TextButton(onClick = { model.inspect("tunnels.config") { value ->
                                        routeConfig=value.optJSONObject("config")?:JSONObject()
                                    } }, enabled = !model.busy) { Text("路由") }
                                    TextButton(onClick = { model.inspect("tunnels.token") { inspect = it } }, enabled = !model.busy) { Text("Token") }
                                }
                                TextButton(onClick = { confirm=CfConfirm("删除隧道",tunnel.optString("name")) { model.removeTunnel() } },enabled=!model.busy) { Text("删除隧道",color=MaterialTheme.colorScheme.error) }
                            }
                        }
                        val connections=snapshot.optJSONArray("connections")?.objects().orEmpty()
                        item { Text("连接 · ${connections.size}",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(8.dp)) }
                        items(connections) { connection ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${connection.optString("coloName")} · ${connection.optString("originIp")}")
                                    Text(connection.optString("clientVersion"),style=MaterialTheme.typography.bodySmall)
                                    if(connection.optString("clientId").isNotBlank()) TextButton(onClick={confirm=CfConfirm("断开连接","断开此 connector？") { model.mutate("tunnels.disconnect",JSONObject(args.toString()).put("clientId",connection.getString("clientId"))) }},enabled=!model.busy) { Text("断开") }
                                }
                            }
                        }
                    }
                } else {
                    items(model.items) { row ->
                        if(where.operation=="dns.list") {
                            CfDnsRecordRow(row,model.busy,edit={dnsEditor(row)},delete={confirm=CfConfirm("删除记录",row.optString("name")){model.mutate("dns.delete",JSONObject(args.toString()).put("record",row.getString("id")))}})
                            return@items
                        }
                        if(where.operation=="d1.tables") {
                            val table=row.getString("name")
                            Card(onClick={model.open(CfLocation("d1.rows",table,JSONObject(args.toString()).put("table",table).put("offset",0)))},enabled=!model.busy,modifier=Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Text(table,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Text(if(row.optString("type")=="view")"视图" else "表",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            return@items
                        }
                        val title=when(where.operation) { "dns.list" -> "${row.optString("type")} · ${row.optString("name")}";else -> row.optString("name") }
                        val subtitle=when(where.operation) { "dns.list" -> row.optString("content");"tunnels.list" -> row.optString("status");"r2.list" -> if(row.optBoolean("directory")) "文件夹" else android.text.format.Formatter.formatShortFileSize(context,row.optLong("size"));else -> row.optString("status",row.optString("uuid")) }
                        Card(onClick={ if(!model.busy) when(where.operation) {
                            "zones" -> model.open(CfLocation("dns.list",title,JSONObject().put("zone",row.getString("id"))))
                            "dns.list" -> dnsEditor(row)
                            "r2.buckets" -> model.open(CfLocation("r2.list",title,JSONObject().put("bucket",title).put("prefix","")))
                            "r2.list" -> if(row.optBoolean("directory"))model.open(CfLocation("r2.list",row.getString("key"),JSONObject(args.toString()).put("prefix",row.getString("key")))) else inspect=row
                            "d1.list" -> model.open(CfLocation("d1.tables",title,JSONObject().put("database",row.getString("uuid"))))
                            "d1.tables" -> model.open(CfLocation("d1.rows",title,JSONObject(args.toString()).put("table",title).put("offset",0)))
                            "tunnels.list" -> model.open(CfLocation("tunnels.detail",title,JSONObject().put("tunnel",row.getString("id"))))
                        } },modifier=Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.titleSmall); if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis) }
                                if(where.operation=="r2.list"&&!row.optBoolean("directory")) IconButton(onClick={ download=row;saveFile.launch(row.optString("name")) },enabled=!model.busy) { Icon(Icons.Outlined.Download,"下载") }
                                if(where.operation=="dns.list"||where.operation=="r2.list"&&!row.optBoolean("directory")) IconButton(onClick={
                                    confirm=CfConfirm("删除",title) { model.mutate(if(where.operation=="dns.list")"dns.delete" else "r2.delete",JSONObject(args.toString()).put(if(where.operation=="dns.list")"record" else "key",row.getString(if(where.operation=="dns.list")"id" else "key"))) }
                                },enabled=!model.busy) { Icon(Icons.Outlined.Delete,"删除") }
                            }
                        }
                    }
                    items(model.results) { result -> CfQueryResult(result) { inspect=JSONObject().put("value",it) } }
                    if (where.operation=="d1.rows"&&!model.queryMode) item {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={model.previousRows()},enabled=!model.busy&&args.optInt("offset")>0) {Text("上一页")}
                            Text("第 ${args.optInt("offset")/100+1} 页")
                            TextButton(onClick={model.nextRows()},enabled=!model.busy&&model.hasMore) {Text("下一页")}
                        }
                    } else if(model.hasMore)item { TextButton(onClick={model.more()},enabled=!model.busy) {Text("加载更多")} }
                    if(model.items.isEmpty()&&model.results.isEmpty()&&!model.busy&&model.account!=null)item {Text(if(model.refreshing)"正在加载…" else "暂无数据",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
            }
        }
    }
    editor?.let { value -> CfEditorDialog(value,onDismiss={editor=null}) { body -> value.save(body);editor=null } }
    confirm?.let { value -> AlertDialog(onDismissRequest={confirm=null},title={Text(value.title)},text={Text(value.message)},confirmButton={TextButton(onClick={confirm=null;value.action()}) {Text("确认")}},dismissButton={TextButton(onClick={confirm=null}) {Text("取消")}}) }
    inspect?.let { value -> AlertDialog(onDismissRequest={inspect=null},title={Text("详情")},text={SelectionContainer { Text(value.toString(2),Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) }},confirmButton={TextButton(onClick={inspect=null}) {Text("关闭")}}) }
}

@Composable
private fun CfEditorDialog(value: CfEditor,onDismiss:()->Unit,onSave:(JSONObject)->Unit) {
    val values=remember(value) { mutableStateMapOf<String,String>().apply {
        value.fields.forEach { (key,_) -> put(key,if(key in value.secrets)"" else value.initial.optString(key).takeUnless {it=="null"}.orEmpty()) }
    } }
    var error by remember(value) {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(value.title)},text={
        Column(Modifier.heightIn(max=490.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            value.note?.let { Text(it, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
            value.fields.forEach { (key,label) ->
                if(key=="proxied") Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Cloudflare 代理",Modifier.weight(1f));Switch(checked=values[key]=="true",onCheckedChange={values[key]=it.toString()})
                } else OutlinedTextField(value=values[key].orEmpty(),onValueChange={values[key]=it},label={Text(label)},
                placeholder={if(key in value.secrets&&value.initial.has("id"))Text("留空保留")},singleLine=key !in value.multiline,
                minLines=if(key in value.multiline)3 else 1,maxLines=if(key in value.multiline)12 else 1,
                visualTransformation=if(key in value.secrets)PasswordVisualTransformation() else VisualTransformation.None,modifier=Modifier.fillMaxWidth()) }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            value.remove?.let { remove -> TextButton(onClick={onDismiss();remove()}) {Text("移除账户",color=MaterialTheme.colorScheme.error)} }
        }
    },confirmButton={TextButton(onClick={try {val body=JSONObject(value.initial.toString());values.forEach { (key,text)->body.put(key,text) };onSave(body)}catch(failure:Exception){error=failure.message?:("输入无效")}}) {Text("确定")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable
private fun CfQueryResult(result:JSONObject,onValue:(String)->Unit) {
    val rows=result.optJSONArray("results")?.objects().orEmpty()
    val columns=rows.flatMap {it.keys().asSequence().toList()}.distinct()
    Column {
        Text("${rows.size} 行${if(result.optBoolean("truncated"))" · 仅显示前 200 行" else ""}",style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(8.dp))
        if(rows.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
            columns.forEach { column -> Column(Modifier.widthIn(min=56.dp,max=240.dp).width(IntrinsicSize.Max)) {
                Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh) {Text(column,Modifier.fillMaxWidth().padding(8.dp),maxLines=1,overflow=TextOverflow.Ellipsis)}
                rows.forEach { row -> val text=if(row.isNull(column))"NULL" else row.opt(column).toString()
                    Text(text,Modifier.fillMaxWidth().height(74.dp).clickable{onValue(text)}.padding(8.dp),maxLines=3,overflow=TextOverflow.Ellipsis);HorizontalDivider()
                }
            } }
        }
        if(!result.optBoolean("success",true))Text("查询未成功",color=MaterialTheme.colorScheme.error)
    }
}
