package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun friendlyAgentFailure(value:String):String=when{
    value.contains("workspace routing discovery",true)->"服务端暂时无法连接 Codex 工作空间路由。请检查服务端网络及代理，然后重试。"
    value.contains("usage limit",true)->"Codex 账号用量已达上限。请切换工作空间、等待额度恢复，或查看可用的额度重置。"
    value.contains("codex_tools_missing",true)||value.contains("找不到 Codex")||value.contains("Codex 工具程序")->"服务端 Codex 尚未就绪，请查看安装与配置指引。"
    value.contains("API_SERVER_KEY",true)||value.contains("尚未安装")||value.contains("Hermes 尚未接入")->"Hermes 尚未完成接入，请按安装与配置指引启用本地 API。"
    else->value
}

/** 安装/配置引导里，这类报错和上方的引导卡片重复，界面只保留一份。 */
internal fun setupRelatedFailure(value:String):Boolean=
    value.contains("尚未安装")||value.contains("Hermes 尚未接入")||value.contains("codex_tools_missing")||
    value.contains("找不到 Codex")||value.contains("Codex 工具程序")

internal fun agentSetupBusy(state:JSONObject?):Boolean =
    state?.optJSONObject("install")?.optBoolean("busy") == true || state?.optString("state") == "installing"

private fun setupBadge(state:String):String=when(state){
    "not_installed"->"未安装"
    "configure"->"需要启用"
    "not_running"->"未启动"
    "credentials_invalid"->"凭据不匹配"
    "unsupported_version"->"版本不匹配"
    "installing"->"安装中"
    "sign_in"->"需要登录"
    "profile_missing"->"缺少 Profile"
    else->"需要处理"
}

private fun setupTint(state:String,colors:ColorScheme):Color=when(state){
    "not_installed"->colors.error
    "installing"->colors.primary
    "configure","sign_in","not_running"->colors.tertiary
    else->colors.secondary
}

@Composable
internal fun AgentSetupCard(api:NativeApi,agent:String,profile:String="default",refreshKey:Int=0,onConfigured:()->Unit={},onVisibility:(Boolean)->Unit={}){
    var result by remember(api,agent,profile){mutableStateOf<JSONObject?>(null)}
    var retry by remember{mutableIntStateOf(0)}
    var details by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    var polling by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    var configurationPending by remember(api,agent,profile){mutableStateOf(false)}
    val configuredCallback by rememberUpdatedState(onConfigured)
    LaunchedEffect(api,agent,profile,retry,refreshKey){
        try {
            var current=api.json("/api/agent-setup/$agent?profile=${q(profile)}")
            result=current
            val wasInstalling=agentSetupBusy(current)||configurationPending
            while(agentSetupBusy(current)) {
                polling=true
                kotlinx.coroutines.delay(3000)
                try {
                    current=api.json("/api/agent-setup/$agent?profile=${q(profile)}")
                    result=current
                    current.optJSONObject("install")?.optString("message")?.takeIf{it.isNotBlank()}?.let{message=it}
                } catch(e:CancellationException){throw e}
                catch(_:Exception){message="正在重新连接，配置任务继续在服务端执行。"}
            }
            configurationPending=false
            if(wasInstalling && (current.optString("state")=="ready" || current.optJSONObject("install")?.optString("phase")=="ready"))configuredCallback()
        } catch(e:CancellationException){throw e}
        catch(_:Exception){result=null}
        finally{polling=false}
    }
    val state=result?:return
    val phase=state.optString("state")
    SideEffect{onVisibility(phase!="ready")}
    if(phase=="ready")return
    val install=state.optJSONObject("install")
    val colors=MaterialTheme.colorScheme
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp)){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Surface(color=setupTint(phase,colors).copy(alpha=.14f),shape=CircleShape){
                    Text(setupBadge(phase),color=setupTint(phase,colors),style=MaterialTheme.typography.labelMedium,
                        fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=10.dp,vertical=4.dp))
                }
                Text(state.optString("title"),style=MaterialTheme.typography.titleSmall)
            }
            Text(state.optString("message"),style=MaterialTheme.typography.bodySmall)
            val stepMessage=install?.optString("message").orEmpty()
            stepMessage.takeIf{it.isNotBlank() && it!=state.optString("message")}?.let{
                Text(it,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            }
            message?.takeIf{it!=state.optString("message") && it!=stepMessage}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
            Row(verticalAlignment=Alignment.CenterVertically){
                TextButton(enabled=!busy,onClick={details=true}){Text("安装与配置步骤")}
                TextButton(enabled=!busy&&!polling,onClick={retry++}){Text(if(polling)"安装中…" else "重新检测")}
            }
            if(state.optBoolean("canInstall") && install?.optString("phase")!="ready")Button(enabled=!busy&&!agentSetupBusy(state),onClick={busy=true;message=null;scope.launch{
                try{
                    val started=api.json("/api/agent-setup/$agent/install",obj());message=started.optString("message")
                    retry++
                }
                catch(e:CancellationException){throw e}catch(e:Exception){message=e.message}finally{busy=false}
            }}){Text(if(busy)"安装中…" else if(agent=="codex")"一键安装 Codex" else "一键安装 Hermes")}
            if(state.optBoolean("canConfigureClaude"))Button(enabled=!busy&&!agentSetupBusy(state),onClick={
                busy=true;configurationPending=true;message="正在提交 Claude 配置任务…"
                scope.launch {
                    try {
                        val started=api.json("/api/agent-setup/claude/configure",obj())
                        message=started.optString("message")
                    } catch(e:CancellationException){throw e}
                    catch(e:Exception){message=e.message}
                    finally{busy=false;retry++}
                }
            }){Text(if(busy||agentSetupBusy(state))"配置中…" else "一键配置 Claude")}
            if(state.optBoolean("canConfigureApi"))Button(enabled=!busy,onClick={busy=true;scope.launch{
                try{val r=api.json("/api/agent-setup/hermes/configure",obj());message=r.optString("message");retry++}
                catch(e:CancellationException){throw e}catch(e:Exception){message=e.message}finally{busy=false}
            }}){Text(if(busy)"配置中…" else "一键配置并安全重启 API")}
        }
    }
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("安装与配置")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(state.optString("installationNote"),style=MaterialTheme.typography.bodySmall)
            val steps=state.array("steps");for(i in 0 until steps.length())Text("${i+1}. ${steps.getString(i)}")
        }
    },confirmButton={TextButton(onClick={details=false}){Text("知道了")}})
}
