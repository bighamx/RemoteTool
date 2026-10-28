package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.URI

internal data class ProtocolAddress(val protocol: String, val address: String)

internal fun splitProtocolAddress(value: String, protocols: List<String>, allowCustom: Boolean): ProtocolAddress {
    val match = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.*)$").matchEntire(value)
    if (match != null && match.groupValues[1].lowercase() in protocols)
        return ProtocolAddress(match.groupValues[1].lowercase(), match.groupValues[2])
    return if (allowCustom && value.isNotEmpty()) ProtocolAddress("", value) else ProtocolAddress(protocols.first(), value)
}

internal fun joinProtocolAddress(protocol: String, address: String): String {
    val text = address.trim()
    // Pasting a complete URL switches the selector instead of duplicating its protocol.
    return if (protocol.isEmpty() || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text else "$protocol://$text"
}

internal fun normalizedDeviceAddress(raw: String): String {
    val uri = URI(raw.trim().trimEnd('/'))
    require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "请输入完整的 http:// 或 https:// 根地址" }
    return uri.toString()
}

internal fun replacedDeviceAddresses(addresses: List<String>, old: String, updated: String): List<String> {
    require(old in addresses) { "原地址已被修改，请重新打开编辑" }
    return addresses.map { if (it == old) updated else it }.distinct()
}

@Composable
internal fun ProtocolAddressField(value: String, onValueChange: (String) -> Unit, label: String = "主机与端口",
    protocols: List<String> = listOf("http", "https"), allowCustom: Boolean = false, enabled: Boolean = true,
    placeholder: String = "192.168.1.2:8888") {
    var custom by remember { mutableStateOf(false) }
    val parts = if (custom && allowCustom) ProtocolAddress("", value) else splitProtocolAddress(value, protocols, allowCustom)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.widthIn(min = 86.dp).padding(top = 8.dp)) {
            // 选择框按内容自适应（http/https/自定义），不再固定 112dp 占掉地址栏宽度。
            OutlinedButton(onClick={menu=true}, enabled=enabled,
                contentPadding=PaddingValues(start = 12.dp, end = 6.dp),
                modifier=Modifier.height(56.dp)) {
                Text(parts.protocol.ifEmpty { "自定义" }, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded=menu, onDismissRequest={menu=false}) {
                (protocols + if(allowCustom) listOf("") else emptyList()).forEach { protocol ->
                    DropdownMenuItem(text={Text(protocol.ifEmpty { "自定义" })}, onClick={
                        menu=false
                        custom=protocol.isEmpty()
                        onValueChange(if(protocol.isEmpty()) value else "$protocol://${parts.address.substringAfter("://", parts.address)}")
                    })
                }
            }
        }
        OutlinedTextField(parts.address, { onValueChange(joinProtocolAddress(parts.protocol, it)) },
            label={Text(if(parts.protocol.isEmpty()) "服务地址" else label)}, placeholder={Text(placeholder)},
            singleLine=true, enabled=enabled, modifier=Modifier.weight(1f))
    }
}
