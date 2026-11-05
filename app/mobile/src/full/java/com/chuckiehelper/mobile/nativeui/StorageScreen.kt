@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun StorageScreen(api:NativeApi?, deviceId:String?, onClose:()->Unit) {
    val context=LocalContext.current
    SceneBackHandler(onBack=onClose)
    var server by remember{mutableStateOf(false)}
    var operating by remember{mutableStateOf(false)}
    BackHandler(operating){}
    val local=remember(context){LocalStorageBackend(context.applicationContext)}
    val remote=remember(api){api?.let{ServerStorageBackend(it)}}
    Scaffold(topBar={TopAppBar(title={Text("空间管理")},navigationIcon={IconButton(onClick=onClose,enabled=!operating){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}})}){padding->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex=if(server)1 else 0){
                Tab(selected=!server,onClick={server=false},text={Text("本机")},enabled=!operating)
                Tab(selected=server,onClick={server=true},text={Text("服务端")},enabled=remote!=null&&!operating)
            }
            key(server,api?.base){StoragePane(if(server)remote!! else local,server,if(server)api?.base.orEmpty() else "本机 · 所有已保存设备"){operating=it}}
        }
    }
}

@Composable
internal fun StoragePane(backend:StorageBackend,server:Boolean,location:String,onBusy:(Boolean)->Unit) {
    val scope=rememberCoroutineScope()
    var inventory by remember{mutableStateOf<JSONObject?>(null)}
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var category by remember{mutableStateOf("")}
    var kind by remember{mutableStateOf("")}
    var search by remember{mutableStateOf("")}
    var sort by remember{mutableStateOf("size_desc")}
    var age by remember{mutableStateOf("0")}
    var group by remember{mutableStateOf(true)}
    var collapsedGroups by remember{mutableStateOf(setOf<String>())}
    var loading by remember{mutableStateOf(false)}
    var scanning by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var notice by remember{mutableStateOf<String?>(null)}
    var total by remember{mutableIntStateOf(0)}
    var more by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf(setOf<String>())}
    var confirmation by remember{mutableStateOf<JSONObject?>(null)}
    var menu by remember{mutableStateOf(false)}
    var showScope by remember{mutableStateOf(false)}
    SideEffect{onBusy(busy)}
    fun filter()=mapOf("category" to category,"kind" to kind,"search" to search,"sort" to sort,"olderDays" to age)
    suspend fun scan() {
        scanning=true;error=null;selected=emptySet()
        try {inventory=backend.scan()}
        catch(e:CancellationException){throw e}
        catch(e:Exception){error=if(server)"无法读取服务端空间，请确认连接及服务端版本：${e.message}" else e.message}
        finally{scanning=false}
    }
    LaunchedEffect(backend){scan()}
    val snapshot=inventory?.optString("snapshot").orEmpty()
    LaunchedEffect(snapshot,category,kind,search,sort,age){
        if(snapshot.isEmpty())return@LaunchedEffect
        selected=emptySet();rows=emptyList();loading=true;error=null
        try {delay(250);val result=backend.items(snapshot,filter(),0);rows=result.array("data").objects();total=result.optInt("total");more=result.optBoolean("hasMore")}
        catch(e:CancellationException){throw e}
        catch(e:Exception){error=e.message}
        finally{loading=false}
    }
    fun select(visible:List<JSONObject>,checked:Boolean){val ids=visible.filterNot{it.optBoolean("protected")}.map{it.getString("id")}.toSet();selected=if(checked)(selected+ids).take(500).toSet() else selected-ids}
    fun prepare(action:String){
        busy=true;error=null
        scope.launch{try{confirmation=backend.prepare(snapshot,selected,action)}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}finally{busy=false}}
    }
    BackHandler(selected.isNotEmpty()&&!busy){selected=emptySet()}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text(if(server)"服务端可管理文件" else "App 可管理数据",style=MaterialTheme.typography.labelMedium);Text(bytes((inventory?.optLong("totalBytes")?:0).toDouble()),style=MaterialTheme.typography.headlineMedium);Text(location,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)}
            IconButton(onClick={scope.launch{scan()}},enabled=!scanning&&!busy){Icon(Icons.Outlined.Refresh,"重新扫描")}
            IconButton(onClick={showScope=!showScope}){Icon(Icons.Outlined.Info,"管理范围与保留数据")}
        }
        if(showScope)Text(inventory?.optString("note").orEmpty(),Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.bodySmall)
        if(showScope&&!server)Text("安装文件 ${bytes((inventory?.optLong("appBytes")?:0).toDouble())} · 其他私有数据 ${bytes((inventory?.optLong("otherPrivateBytes")?:0).toDouble())}（保留）",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall)
        if(inventory?.optBoolean("partial")==true)Text("部分文件无法读取或超出扫描上限，当前显示已扫描结果。",Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            ChoiceChip(selected=category.isEmpty(),onClick={if(!busy)category=""},label={Text("全部")})
            inventory?.array("categories")?.objects()?.forEach{c->ChoiceChip(selected=category==c.getString("id"),onClick={if(!busy)category=c.getString("id")},label={Text("${c.getString("name")} ${bytes(c.optLong("bytes").toDouble())}")})}
        }
        Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            OutlinedTextField(search,{if(!busy)search=it},Modifier.weight(1f),placeholder={Text("搜索文件 / 设备 / 会话")},singleLine=true)
            Box{IconButton(onClick={menu=true},enabled=!busy){Icon(Icons.Outlined.Tune,"筛选与排序")}
                DropdownMenu(menu,{menu=false}){
                    Text("附件类型",Modifier.padding(12.dp),style=MaterialTheme.typography.labelSmall)
                    listOf("" to "全部类型","image" to "图片","video" to "视频","audio" to "音频","document" to "文档","session" to "离线会话","package" to "安装包","other" to "其他").forEach{(v,n)->DropdownMenuItem(text={Text((if(kind==v)"✓ " else "")+n)},onClick={kind=v;menu=false})}
                    HorizontalDivider()
                    listOf("size_desc" to "大文件优先","newest" to "最近写入优先","oldest" to "最早写入优先","name" to "名称").forEach{(v,n)->DropdownMenuItem(text={Text((if(sort==v)"✓ " else "")+n)},onClick={sort=v;menu=false})}
                    HorizontalDivider()
                    listOf("0" to "不限时间","7" to "7 天前","30" to "30 天前").forEach{(v,n)->DropdownMenuItem(text={Text((if(age==v)"✓ " else "")+n)},onClick={age=v;menu=false})}
                    DropdownMenuItem(text={Text(if(group)"取消按会话分组" else "按会话分组")},onClick={group=!group;menu=false})
                }
            }
        }
        if(scanning||loading||busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=6.dp))
        error?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        notice?.let{Text(it,Modifier.padding(horizontal=12.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)}
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            Text("${rows.size} / $total 项",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
            TextButton(enabled=rows.isNotEmpty()&&!busy,onClick={select(rows,true)}){Text("全选已显示")}
            if(selected.isNotEmpty())TextButton(onClick={selected=emptySet()}){Text("取消选择")}
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=12.dp)){
            val groups=if(group)rows.groupBy{it.optString("group")} else mapOf("" to rows)
            groups.forEach{(title,children)->
                val collapsible=title.isNotBlank()&&children.size>1
                val collapsed=collapsible&&title in collapsedGroups
                if(title.isNotBlank())item(key="group:$title"){
                    Row(Modifier.fillMaxWidth()
                        .semantics { if(collapsible)stateDescription=if(collapsed)"已折叠" else "已展开" }
                        .clickable(enabled=collapsible&&!busy,role=Role.Button){
                            collapsedGroups=if(collapsed)collapsedGroups-title else collapsedGroups+title
                        },verticalAlignment=Alignment.CenterVertically){
                        val selectable=children.filterNot{it.optBoolean("protected")}
                        Checkbox(selectable.isNotEmpty()&&selectable.all{it.optString("id") in selected},{select(children,it)},enabled=selectable.isNotEmpty()&&!busy)
                        val selectedCount=children.count{it.optString("id") in selected}
                        Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.labelMedium,maxLines=2,overflow=TextOverflow.Ellipsis);Text("${children.size} 项 · ${bytes(children.sumOf{it.optLong("bytes")}.toDouble())}"+(if(selectedCount>0)" · 已选 $selectedCount 项" else ""),style=MaterialTheme.typography.labelSmall)}
                        if(collapsible)Icon(if(collapsed)Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                            if(collapsed)"展开分组" else "折叠分组",Modifier.padding(horizontal=8.dp))
                    }
                }
                if(!collapsed)items(children,key={it.getString("id")}){row->
                    val protected=row.optBoolean("protected");val id=row.getString("id")
                    Row(Modifier.fillMaxWidth().clickable(enabled=!protected&&!busy){select(listOf(row),id !in selected)}.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
                        Checkbox(id in selected,{select(listOf(row),it)},enabled=!protected&&!busy)
                        Icon(if(protected)Icons.Outlined.Lock else when(row.optString("kind")){"image"->Icons.Outlined.Image;"video"->Icons.Outlined.Movie;"audio"->Icons.Outlined.Audiotrack;"session"->Icons.Outlined.ChatBubbleOutline;else->Icons.Outlined.Description},null,Modifier.size(24.dp))
                        Column(Modifier.weight(1f).padding(start=10.dp)){
                            Text(row.optString("name"),style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text("${bytes(row.optLong("bytes").toDouble())} · ${storageDate(row.optLong("modifiedAt"))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(protected)Text(row.optString("reason"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                            else if(row.optString("source").isNotBlank())Text(row.optString("source"),style=MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
                }
            }
            if(rows.isEmpty()&&!loading&&!scanning)item{Text(if(snapshot.isEmpty())"点击重新扫描读取空间信息" else "没有符合条件的数据",Modifier.padding(20.dp))}
            if(more)item{TextButton(enabled=!loading&&!busy,onClick={scope.launch{
                loading=true
                val requestedFilter=filter();val requestedSnapshot=snapshot
                try{val result=backend.items(requestedSnapshot,requestedFilter,rows.size);if(inventory?.optString("snapshot")==requestedSnapshot&&filter()==requestedFilter){rows=rows+result.array("data").objects();more=result.optBoolean("hasMore")}}
                catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}finally{loading=false}
            }}){Text("加载更多")}}
        }
        if(selected.isNotEmpty())Surface(tonalElevation=3.dp){
            Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)){
                Text("已选 ${selected.size} 项 · ${bytes(rows.filter{it.optString("id") in selected}.sumOf{it.optLong("bytes")}.toDouble())}",style=MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    if(server&&category=="trash")OutlinedButton(enabled=!busy,onClick={prepare("restore")}){Text("恢复所选")}
                    Button(enabled=!busy,onClick={prepare(if(!server)"delete" else if(category=="trash")"purge" else "trash")}){Text(if(!server)"清理所选" else if(category=="trash")"永久删除" else "移入回收站")}
                }
            }
        }
    }
    confirmation?.let{plan->
        AlertDialog(onDismissRequest={if(!busy)confirmation=null},title={Text(if(plan.optString("action")=="restore")"恢复所选数据" else "确认清理所选数据")},text={Text("${plan.optInt("count")} 项 · ${bytes(plan.optLong("bytes").toDouble())}\n\n${plan.optString("warning")}")},
            confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch{
                try{val result=backend.execute(plan.getString("plan"));confirmation=null;notice="完成 ${result.optInt("processed")} 项，释放 ${bytes(result.optLong("freedBytes").toDouble())}"+(if(result.optInt("failed")>0)"；${result.optInt("failed")} 项未处理："+result.array("failures").objects().take(2).joinToString{it.optString("message")} else "");scan()}
                catch(e:CancellationException){throw e}catch(e:Exception){error="操作结果未确认，请重试同一操作或刷新核对：${e.message}"}finally{busy=false}
            }}){Text(if(busy)"处理中" else if(plan.optString("action")=="purge")"永久删除" else "确认")}},dismissButton={TextButton(enabled=!busy,onClick={confirmation=null}){Text("取消")}})
    }
}

private fun storageDate(value:Long):String=if(value<=0)"时间未知" else java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(value))
