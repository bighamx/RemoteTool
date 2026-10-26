package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun PhoneSessionOptions(api: NativeApi, value: String, changed: (String) -> Unit) {
    val options = JSONObject(value); val mode = options.optString("mode", "off")
    var phones by remember(api.base) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api.base, mode) {
        if (mode != "off") try {
            phones = api.json("/api/phone/v1/devices").array("devices").objects()
            error = if (phones.isEmpty()) {
                if (mode == "description") "手机未连接，创建时将跳过手机说明" else "请先在手机操控中连接当前通道"
            } else null
            if (phones.isNotEmpty() && phones.none { it.optString("id") == options.optString("phone_id") }) changed(JSONObject(value).put("phone_id", phones.first().optString("id")).toString())
        } catch (e: Exception) { error = e.message }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("手机能力", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("off" to "关闭", "description" to "注入说明", "tools" to "启用工具").forEach { (id, label) ->
                FilterChip(mode == id, { changed(JSONObject(value).put("mode", id).toString()) }, label = { Text(label) })
            }
        }
        if (mode != "off") {
            phones.forEach { phone -> Row {
                RadioButton(options.optString("phone_id") == phone.optString("id"), { changed(JSONObject(value).put("phone_id", phone.optString("id")).toString()) })
                Text(phone.optString("name"), modifier = Modifier.padding(top = 12.dp))
            } }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("允许高级操作", Modifier.weight(1f))
                Switch(options.optBoolean("allow_advanced"), { changed(JSONObject(value).put("allow_advanced", it).toString()) })
            }
            error?.let { Text(it, color = if (mode == "description") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun PhoneNewSessionDialog(api: NativeApi, model: HermesModel, close: () -> Unit, done: () -> Unit) {
    var options by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("{\"mode\":\"off\"}") }
    AlertDialog(onDismissRequest = { if (!model.submitting) close() }, title = { Text("新建 Hermes 会话") },
        text = { Column { PhoneSessionOptions(api, options) { options = it }; model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(enabled = !model.submitting, onClick = {
            model.newSession(newHermesChatName(), { close(); done() }, obj("phone_tools" to JSONObject(options)))
        }) { Text(if (model.submitting) "创建中…" else "创建") } },
        dismissButton = { TextButton(onClick = close, enabled = !model.submitting) { Text("取消") } })
}
