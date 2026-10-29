@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun CfDnsControls(args: JSONObject, busy: Boolean, filter: (String,String) -> Unit, add: () -> Unit) {
    var query by remember(args.optString("zone"),args.optString("query")) { mutableStateOf(args.optString("query")) }
    Column(Modifier.padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(query,{query=it},label={Text("搜索名称、内容或备注")},singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={filter("query",query.trim())}),
                modifier=Modifier.weight(1f))
            TextButton(onClick={filter("query",query.trim())},enabled=!busy){Text("搜索")}
            IconButton(onClick=add,enabled=!busy){Icon(Icons.Outlined.Add,"添加记录")}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
            CfChoice("类型",args.optString("recordType"),listOf("" to "全部类型")+listOf("A","AAAA","CNAME","TXT","MX","NS","SRV","CAA","HTTPS","SVCB","PTR").map{it to it},!busy){filter("recordType",it)}
            CfChoice("代理",args.optString("proxy"),listOf("" to "全部状态","proxied" to "已代理","dns" to "仅 DNS"),!busy){filter("proxy",it)}
            CfChoice("排序",args.optString("sort","name"),listOf("name" to "名称","type" to "类型","content" to "内容","ttl" to "TTL"),!busy){filter("sort",it)}
            IconButton(onClick={filter("direction",if(args.optString("direction","asc")=="asc")"desc" else "asc")},enabled=!busy,modifier=Modifier.size(36.dp)) {
                Icon(if(args.optString("direction","asc")=="asc")Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,"切换升降序")
            }
        }
    }
}

@Composable
private fun CfChoice(label:String,current:String,options:List<Pair<String,String>>,enabled:Boolean,choose:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    Box {
        TextButton(onClick={expanded=true},enabled=enabled,contentPadding=PaddingValues(horizontal=6.dp)) {
            Text("$label：${options.firstOrNull{it.first==current}?.second?:current}",style=MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ArrowDropDown,null,Modifier.size(18.dp))
        }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            options.forEach { (key,name)->DropdownMenuItem(text={Text(name)},onClick={expanded=false;choose(key)},trailingIcon={if(key==current)Icon(Icons.Outlined.Check,null)}) }
        }
    }
}

@Composable
internal fun CfDnsRecordRow(row:JSONObject,busy:Boolean,edit:()->Unit,delete:()->Unit) {
    Card(onClick=edit,enabled=!busy,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.small) {
                Text(row.optString("type"),Modifier.widthIn(min=40.dp).padding(horizontal=6.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Text(row.optString("name"),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(row.optString("content"),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val proxy=if(row.optBoolean("proxied"))"已代理" else "仅 DNS"
                Text("$proxy · TTL ${if(row.optInt("ttl")==1)"自动" else row.optInt("ttl").toString()+"s"}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                cloudflareDnsProtection(row)?.let { protection ->
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Warning, null, Modifier.size(14.dp), tint=MaterialTheme.colorScheme.tertiary)
                        Text(protection.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            IconButton(onClick=delete,enabled=!busy,modifier=Modifier.size(32.dp)){Icon(Icons.Outlined.Delete,"删除记录",Modifier.size(18.dp))}
        }
    }
}
