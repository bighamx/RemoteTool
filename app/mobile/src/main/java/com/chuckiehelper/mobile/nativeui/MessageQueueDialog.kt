@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MessageQueueDialog(queue: DeferredChatQueue, onClose: ()->Unit) {
    DisposableEffect(queue) { queue.panelOpen=true; onDispose { queue.panelOpen=false } }
    var editing by remember { mutableStateOf<QueuedChatMessage?>(null) }
    var forgetting by remember { mutableStateOf<QueuedChatMessage?>(null) }
    var text by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    AlertDialog(onDismissRequest=onClose,title={Text("待发送消息 · ${queue.visible.size}")},
        text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            val native=queue.visible.any{it.mode=="native"}
            Text("当前任务结束后按顺序发送，不插话。发送前可以编辑或移除。",style=MaterialTheme.typography.bodySmall)
            Text(if(native) "已加入 Codex 原生队列的消息会自动执行，离开 App 也有效；开始执行后无法再编辑。" else "手机队列会在 App 进程运行时继续按会话推进；查看或编辑队列时暂缓发送。支持时会使用 Codex 原生队列。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("暂停手机队列",Modifier.weight(1f));Switch(queue.paused,{queue.pause(it)})
            }
            if(queue.visible.isEmpty())Text("还没有排队消息")
            queue.visible.forEachIndexed { index,row ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("${index+1} · ${if(row.mode=="native") "Codex 原生队列" else "手机队列"}",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                        IconButton({editing=row;text=row.text;files=row.files},enabled=!queue.busy && row.status!="发送中" && !row.status.contains("待核对") && !row.status.contains("所属账号")) {Icon(Icons.Outlined.Edit,"编辑排队消息")}
                        IconButton({if(row.mode=="native" && (row.status.contains("待核对") || row.status.contains("待恢复") || row.status.contains("所属账号"))) forgetting=row else queue.remove(row)},enabled=!queue.busy && row.status!="发送中") {Icon(Icons.Outlined.Close,"移除排队消息")}
                    }
                    Text(row.text,maxLines=4,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if(row.files.isNotEmpty())Text("${row.files.size} 个附件",style=MaterialTheme.typography.bodySmall)
                    Text(row.status,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }},confirmButton={TextButton(onClose){Text("完成")}})
    editing?.let { row -> AlertDialog(onDismissRequest={editing=null},title={Text("编辑待发送消息")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text,{text=it},Modifier.fillMaxWidth().heightIn(max=240.dp),label={Text("消息内容")})
            files.forEach { file -> InputChip(true,{},{Text(file.optString("name"))},trailingIcon={IconButton({files=files.filterNot{it.optString("id")==file.optString("id")}}){Icon(Icons.Outlined.Close,"移除附件")}}) }
        }},confirmButton={TextButton({queue.edit(row,text,files);editing=null},enabled=!queue.busy && (text.isNotBlank() || files.isNotEmpty())){Text("保存")}},
        dismissButton={TextButton({editing=null}){Text("取消")}}) }
    forgetting?.let { row -> AlertDialog(onDismissRequest={forgetting=null},title={Text("移除本机队列记录？")},
        text={Text("原生队列连接暂时无法核对。此操作只移除手机上的记录；服务端消息仍可能执行。")},
        confirmButton={TextButton({queue.forgetUncertainNative(row);forgetting=null}){Text("仅移除本机记录")}},
        dismissButton={TextButton({forgetting=null}){Text("取消")}}) }
}
