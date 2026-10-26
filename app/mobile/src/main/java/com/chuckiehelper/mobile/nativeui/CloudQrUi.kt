package com.chuckiehelper.mobile.nativeui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

@Composable internal fun rememberCloudQrScanner(model: CloudAccountModel): (Boolean) -> Unit {
    var approve by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { model.scanQr(it,approve) }
    }
    return { authorization ->
        if (model.prepareQrScan(authorization)) {
            approve = authorization
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                .setBarcodeImageEnabled(false).setOrientationLocked(true).setCaptureActivity(CloudQrCaptureActivity::class.java)
                .setPrompt(if (authorization) "扫描待登录设备的二维码" else "扫描已登录网页的二维码"))
        }
    }
}

@Composable internal fun CloudQrHost(model: CloudAccountModel) {
    val qr = model.qr
    qr.waiting?.let { pending ->
        var now by remember(pending.requestId) { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(pending.requestId) { while (now < pending.expiresAt) { delay(1000); now = System.currentTimeMillis() } }
        val bitmap = remember(pending.requestId) { pending.payload?.let { BarcodeEncoder().encodeBitmap(it,BarcodeFormat.QR_CODE,512,512) } }
        DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
        AlertDialog(onDismissRequest = { qr.cancel() }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(if (pending.flow == "offer") "扫码登录" else "二维码登录") },
            text = {
                Column(Modifier.fillMaxWidth(),verticalArrangement = Arrangement.spacedBy(10.dp),horizontalAlignment = Alignment.CenterHorizontally) {
                    bitmap?.let { Image(it.asImageBitmap(),"本次登录二维码",Modifier.size(220.dp)) }
                    Text(pending.confirmationCode,fontFamily = FontFamily.Monospace,style = MaterialTheme.typography.headlineMedium)
                    Text(if (pending.flow == "offer") "请在网页核对并确认登录" else "请用已登录 App 扫码并确认")
                    Text("${((pending.expiresAt-now).coerceAtLeast(0)+999)/1000} 秒后过期",style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { if (pending.flow == "receive") TextButton(onClick = { model.createQr() }) { Text("重新生成") } },
            dismissButton = { TextButton(onClick = { qr.cancel() }) { Text("取消") } })
    }
    qr.approval?.let { approval ->
        var now by remember(approval.requestId) { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(approval.requestId) { while (now < approval.expiresAt) { delay(1000); now = System.currentTimeMillis() } }
        AlertDialog(onDismissRequest = { if (!qr.busy) qr.dismissApproval() },properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("授权登录") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前账户：${approval.accountName}")
                Text(approval.application,style = MaterialTheme.typography.titleMedium)
                Text(approval.client)
                Text(approval.confirmationCode,fontFamily = FontFamily.Monospace,style = MaterialTheme.typography.headlineMedium)
                approval.permissions.forEach { Text("• $it",style = MaterialTheme.typography.bodySmall) }
                Text(if (now >= approval.expiresAt) "请求已过期" else "请与出码设备核对",style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = !qr.busy && now < approval.expiresAt,onClick = { qr.decide(true) }) { Text("允许此设备登录我的账户") } },
            dismissButton = { TextButton(enabled = !qr.busy && now < approval.expiresAt,onClick = { qr.decide(false) }) { Text("拒绝") } })
    }
    model.pendingAccount?.let { account ->
        AlertDialog(onDismissRequest = { model.dismissAccountSwitch() },properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("切换账户") },text = { Text("从 ${model.displayName} 切换到 ${account.optString("displayName")}？") },
            confirmButton = { TextButton(enabled = !model.busy,onClick = { model.confirmAccountSwitch() }) { Text("切换") } },
            dismissButton = { TextButton(enabled = !model.busy,onClick = { model.dismissAccountSwitch() }) { Text("取消") } })
    }
}
