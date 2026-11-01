package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun friendlyAgentFailure(value:String):String=when{
    value.contains("workspace routing discovery",true)->"服务端暂时无法连接 Codex 工作空间路由。请检查服务端网络及代理，然后重试。"
    value.contains("usage limit",true)->"Codex 账号用量已达上限。请切换工作空间、等待额度恢复，或查看可用的额度重置。"
    value.contains("codex_tools_missing",true)||value.contains("找不到 Codex")||value.contains("Codex 工具程序")->"服务端 Codex 尚未就绪，请查看安装与配置指引。"
    value.contains("API_SERVER_KEY",true)||value.contains("尚未安装 Hermes")||value.contains("Hermes 尚未接入")->"Hermes 尚未完成接入，请按安装与配置指引启用本地 API。"
    else->value
}

@Composable
internal fun AgentSetupCard(api:NativeApi,agent:String,profile:String="default",refreshKey:Int=0){
    var result by remember(api,agent,profile){mutableStateOf<JSONObject?>(null)}
    var retry by remember{mutableIntStateOf(0)}
    var details by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(api,agent,profile,retry,refreshKey){
        try{result=api.json("/api/agent-setup/$agent?profile=${q(profile)}")}
        catch(e:CancellationException){throw e}
        catch(_:Exception){result=null}
    }
    val state=result?:return
    if(state.optString("state")=="ready")return
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp)){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(state.optString("title"),style=MaterialTheme.typography.titleSmall)
            Text(state.optString("message"),style=MaterialTheme.typography.bodySmall)
            message?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
            Row{
                TextButton(enabled=!busy,onClick={details=true}){Text("安装与配置步骤")}
                TextButton(enabled=!busy,onClick={retry++}){Text("重新检测")}
            }
            if(state.optBoolean("canInstall"))Button(enabled=!busy,onClick={busy=true;scope.launch{
                try{
                    val started=api.json("/api/agent-setup/codex/install",obj());message=started.optString("message")
                    var rounds=0
                    while(rounds<100){
                        kotlinx.coroutines.delay(3000);rounds++
                        val now=api.json("/api/agent-setup/codex")
                        now.optJSONObject("install")?.let{install->install.optString("message").takeIf{it.isNotBlank()}?.let{message=it}}
                        if(now.optString("state")!="installing")break
                    }
                    retry++
                }
                catch(e:CancellationException){throw e}catch(e:Exception){message=e.message}finally{busy=false}
            }}){Text(if(busy)"安装中…" else "一键安装 Codex")}
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
