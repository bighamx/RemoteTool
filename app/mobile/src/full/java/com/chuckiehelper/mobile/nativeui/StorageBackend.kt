package com.chuckiehelper.mobile.nativeui

import android.app.DownloadManager
import android.content.Context
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal interface StorageBackend {
    suspend fun scan(): JSONObject
    suspend fun items(snapshot: String, filter: Map<String,String>, offset: Int): JSONObject
    suspend fun prepare(snapshot: String, ids: Set<String>, action: String): JSONObject
    suspend fun execute(plan: String): JSONObject
}

internal class ServerStorageBackend(private val api: NativeApi) : StorageBackend {
    override suspend fun scan() = api.json("/api/storage/scan", obj())
    override suspend fun items(snapshot: String, filter: Map<String,String>, offset: Int) = api.json(
        "/api/storage/items?snapshot=${q(snapshot)}&offset=$offset&limit=100&" + filter.entries.joinToString("&") { "${q(it.key)}=${q(it.value)}" })
    override suspend fun prepare(snapshot: String, ids: Set<String>, action: String) = api.json("/api/storage/prepare", obj("snapshot" to snapshot, "ids" to JSONArray(ids.toList()), "action" to action))
    override suspend fun execute(plan: String) = api.json("/api/storage/execute", obj("plan" to plan))
}

internal fun storageFileKind(name: String) = when (name.substringAfterLast('.').lowercase(java.util.Locale.ROOT)) {
    "png","jpg","jpeg","gif","webp","bmp","heic","avif" -> "image"
    "mp4","mkv","mov","webm","avi","m4v" -> "video"
    "mp3","m4a","wav","flac","opus","ogg" -> "audio"
    "pdf","md","txt","doc","docx","xlsx","pptx","json","csv" -> "document"
    "apk" -> "package"
    "log" -> "log"
    else -> "other"
}

internal fun storageSafeFile(root: File, file: File): Boolean = runCatching {
    val base=root.absoluteFile.toPath().normalize()
    val target=file.absoluteFile.toPath().normalize()
    if (!target.startsWith(base) || target==base) return false
    var current:java.nio.file.Path?=target
    while (current!=null && current.startsWith(base)) {
        if (java.nio.file.Files.isSymbolicLink(current)) return false
        current=current.parent
    }
    file.canonicalPath.startsWith(root.canonicalPath+File.separator)
}.getOrDefault(false)

@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal class LocalStorageBackend(private val context: Context) : StorageBackend {
    private data class Candidate(val row:JSONObject,val root:File?,val file:File?,val ticks:Long,val download:Long?=null,val thumbnail:Boolean=false)
    private data class Plan(val files:List<Candidate>,var result:JSONObject?=null)
    private val files=linkedMapOf<String,Candidate>()
    private val plans=mutableMapOf<String,Plan>()
    private var token=""
    private val gate=Any()
    private suspend fun <T> io(action:()->T):T=withContext(Dispatchers.IO){synchronized(gate){action()}}
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun walk(root:File):Sequence<File> = sequence {
        if (!root.isDirectory || java.nio.file.Files.isSymbolicLink(root.toPath())) return@sequence
        val dirs=java.util.ArrayDeque<File>();dirs.add(root)
        var count=0
        while (dirs.isNotEmpty() && count<100_000) {
            for (f in dirs.removeFirst().listFiles().orEmpty()) {
                if (!storageSafeFile(root,f)) continue
                if (f.isDirectory) dirs.add(f) else if (f.isFile) {yield(f);count++}
            }
        }
    }
    private fun add(category:String,name:String,group:String,file:File,root:File,protected:Boolean=false,reason:String="",kind:String=storageFileKind(file.name)) {
        val id=UUID.randomUUID().toString()
        val row=obj("id" to id,"category" to category,"kind" to kind,"name" to name,"group" to group,"source" to "本机副本",
            "bytes" to file.length(),"modifiedAt" to file.lastModified(),"protected" to protected,"reason" to reason)
        files[id]=Candidate(row,root,file,file.lastModified())
    }
    private fun historyInUse(file:File):Boolean {
        val scope=file.parentFile?.name?:return true
        val pref=File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().firstOrNull{hash(it.nameWithoutExtension)==scope}?:return false
        val prefs=context.getSharedPreferences(pref.nameWithoutExtension,0)
        val pattern=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|[0-9]{8}_[0-9]{6}_[a-zA-Z0-9]+")
        return listOf("pendingSubmissions","trackedRuns","finalSyncStates","message_queue").any{key->pattern.findAll(prefs.getString(key,"").orEmpty()).any{hash(it.value)+".json"==file.name}}
    }
    override suspend fun scan():JSONObject=io {
        files.clear();plans.clear();token=UUID.randomUUID().toString()
        val devicePrefs=context.getSharedPreferences("MainActivity",0)
        val devices=runCatching { JSONArray(devicePrefs.getString("devices","[]")).objects() }.getOrDefault(emptyList())
        val cacheLabels=mutableMapOf<String,String>();val protectedCache=mutableSetOf<String>();val previewLabels=mutableMapOf<String,String>()
        val prefFiles=File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().filter { it.name.startsWith("codex-")||it.name.startsWith("hermes-") }
        for (pref in prefFiles) {
            val identity=pref.nameWithoutExtension;val scope=hash(identity);val prefs=context.getSharedPreferences(identity,0)
            val device=devices.firstOrNull{identity.contains(it.optString("id"))};val deviceName=device?.optString("name").orEmpty()
            val agent=if(identity.startsWith("codex-"))"Codex" else "Hermes"
            val profile=identity.substringAfter("-profile-","default")
            for (s in runCatching{JSONArray(prefs.getString("offlineSessions","[]")).objects()}.getOrDefault(emptyList())) {
                val id=s.optString("id");if(id.isBlank())continue
                cacheLabels[scope+"/"+hash(id)+".json"]="$deviceName · $agent · $profile · ${s.optString("title",id.take(12))}"
            }
            for (key in listOf("pendingSubmissions","trackedRuns","finalSyncStates","message_queue")) {
                val raw=prefs.getString(key,null)?:continue
                val pattern=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|[0-9]{8}_[0-9]{6}_[a-zA-Z0-9]+")
                for (match in pattern.findAll(raw)) protectedCache+=scope+"/"+hash(match.value)+".json"
            }
        }
        val history=File(context.noBackupFilesDir,"conversation-history")
        var enrichedBytes=0L
        for (f in walk(history)) {
            if (f.extension!="json")continue
            val rel=f.relativeTo(history).invariantSeparatorsPath
            val label=cacheLabels[rel]?:"离线会话 · ${f.name.take(12)}"
            add("history",label,label,f,history,rel in protectedCache,if(rel in protectedCache)"有待发送或待同步记录" else "",kind="session")
            // Attachment names are optional enrichment; preserve unknown caches as inspectable entries.
            if (f.length()<=8*1024*1024 && enrichedBytes<24*1024*1024) runCatching {
                enrichedBytes+=f.length()
                for(row in JSONArray(f.readText()).objects())for(a in row.array("attachments").objects()) {
                    val url=a.optString("url");if(url.isBlank())continue
                    for(d in devices)for(i in 0 until d.array("endpoints").length()) {
                        val endpoint=d.array("endpoints").getString(i)
                        previewLabels[hermesPreviewKey(endpoint+url)]=a.optString("name")+" · "+label
                    }
                }
            }
        }
        val preview=File(context.cacheDir,"hermes-open")
        for(f in walk(preview)) {
            val temporary=f.name.endsWith(".tmp")||f.name.contains(".part")
            add("previews",previewLabels[f.name.substringBefore('.')]?:"附件预览 · ${f.name.take(16)}","图片 / 视频 / 音频 / 文档预览",f,preview,
                temporary,"临时文件正在使用",kind=storageFileKind(f.name))
        }
        val updates=File(context.filesDir,"updates")
        for(f in walk(updates))if(f.extension=="apk")add("updates",f.name,"更新安装包",f,updates,
            System.currentTimeMillis()-f.lastModified()<120_000,"最近下载，稍后可清理",kind="package")
        val reports=File(context.filesDir,"crash-reports")
        for(f in walk(reports))if(f.extension=="txt")add("logs",f.name,"诊断日志",f,reports,kind="log")
        val disk=context.imageLoader.diskCache
        disk?.directory?.toFile()?.let { root ->
            val bytes=walk(root).sumOf{it.length()}
            if(bytes>0){val id=UUID.randomUUID().toString();val row=obj("id" to id,"category" to "previews","kind" to "image","name" to "图片缩略图缓存", "group" to "由图片缓存库统一清理", "bytes" to bytes,"modifiedAt" to root.lastModified(),"protected" to false)
                files[id]=Candidate(row,root,null,0,thumbnail=true)}
        }
        val manager=context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val ids=context.getSharedPreferences("hermes-local-files",0).all.values.filterIsInstance<Set<*>>().flatMap{it.filterIsInstance<String>()}.mapNotNull{it.toLongOrNull()}.distinct()
        for(batch in ids.chunked(100))runCatching {
            manager.query(DownloadManager.Query().setFilterById(*batch.toLongArray())).use { c ->
                while(c.moveToNext()) {
                    val download=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val size=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
                    val status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val title=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
                    val id=UUID.randomUUID().toString();val row=obj("id" to id,"category" to "downloads","kind" to storageFileKind(title),"name" to title,
                        "group" to "App 下载到系统下载目录的副本","bytes" to size,"modifiedAt" to c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)),
                        "protected" to (status!=DownloadManager.STATUS_SUCCESSFUL),"reason" to "下载尚未完成")
                    files[id]=Candidate(row,null,null,0,download=download)
                }
            }
        }
        val names=mapOf("history" to "离线会话","previews" to "附件预览与缩略图","downloads" to "App 下载副本","updates" to "更新安装包","logs" to "诊断日志")
        val categories=names.map{(id,name)->val rows=files.values.filter{it.row.optString("category")==id};obj("id" to id,"name" to name,
            "bytes" to rows.sumOf{it.row.optLong("bytes")},"count" to rows.size,"protectedCount" to rows.count{it.row.optBoolean("protected")})}
        val privateBytes=walk(context.dataDir).sumOf{it.length()}
        val managedPrivate=files.values.filter{it.download==null}.sumOf{it.row.optLong("bytes")}
        obj("snapshot" to token,"totalBytes" to files.values.sumOf{it.row.optLong("bytes")},"categories" to JSONArray(categories),
            "appBytes" to File(context.applicationInfo.sourceDir).length(),"otherPrivateBytes" to (privateBytes-managedPrivate).coerceAtLeast(0),
            "note" to "本机管理覆盖所有已保存设备的离线会话、附件预览、App 下载副本、安装包和诊断日志。设置、登录凭据、草稿、分享收件箱及发送回执保留。离线会话删除后断网无法查看；若服务端历史已丢失，请先保留本机副本。")
    }
    override suspend fun items(snapshot:String,filter:Map<String,String>,offset:Int):JSONObject=io{
        require(snapshot==token){"列表已过期，请重新扫描"}
        var rows=files.values.map{it.row}.filter{r->filter["category"].isNullOrEmpty()||r.optString("category")==filter["category"]}
        if(!filter["kind"].isNullOrEmpty())rows=rows.filter{it.optString("kind")==filter["kind"]}
        val search=filter["search"].orEmpty();if(search.isNotBlank())rows=rows.filter{(it.optString("name")+it.optString("group")).contains(search,true)}
        val age=filter["olderDays"]?.toIntOrNull()?:0;if(age>0)rows=rows.filter{it.optLong("modifiedAt")<System.currentTimeMillis()-age*86_400_000L}
        rows=when(filter["sort"]){"oldest"->rows.sortedBy{it.optLong("modifiedAt")};"newest"->rows.sortedByDescending{it.optLong("modifiedAt")};"name"->rows.sortedBy{it.optString("name")};else->rows.sortedByDescending{it.optLong("bytes")}}
        obj("data" to JSONArray(rows.drop(offset).take(100)),"total" to rows.size,"bytes" to rows.sumOf{it.optLong("bytes")},"hasMore" to (offset+100<rows.size))
    }
    override suspend fun prepare(snapshot:String,ids:Set<String>,action:String):JSONObject=io{
        require(snapshot==token&&action=="delete"&&ids.size in 1..500){"列表已过期或清理方式无效"}
        val selected=ids.map{files[it]?:error("所选数据已改变")};require(selected.none{it.row.optBoolean("protected")}){"所选数据正在使用"}
        require(selected.none{it.row.optString("category")=="history"&&it.file?.let(::historyInUse)==true}){"所选会话有待发送或待同步记录，请刷新后重试"}
        val plan=UUID.randomUUID().toString();plans[plan]=Plan(selected)
        obj("plan" to plan,"count" to selected.size,"bytes" to selected.sumOf{it.row.optLong("bytes")},"action" to action,
            "warning" to "只清理所选本机数据。离线会话可能是仅存副本，请先确认服务端可读取；删除后断网无法查看。App 下载副本会从系统下载目录删除。服务端附件、原生会话、相册原图、设置和发送回执保留。")
    }
    override suspend fun execute(plan:String):JSONObject=io{
        val operation=plans[plan]?:error("操作已过期，请重新扫描");operation.result?.let{return@io it}
        var count=0;var bytes=0L;val failed=mutableListOf<JSONObject>()
        val clearedHistory=mutableSetOf<String>()
        for(item in operation.files)try {
            if(item.thumbnail){context.imageLoader.memoryCache?.clear();context.imageLoader.diskCache?.clear()}
            else if(item.download!=null){val m=context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager;require(m.remove(item.download)>0){"下载记录不存在"}}
            else {
                val file=item.file?:error("文件不存在");val root=item.root?:error("路径不可用")
                require(item.row.optString("category")!="history"||!historyInUse(file)){"会话正在使用或有未同步记录"}
                require(storageSafeFile(root,file)&&file.isFile&&file.length()==item.row.optLong("bytes")&&file.lastModified()==item.ticks){"文件已变化，请重新扫描"}
                require(file.delete()){"无法删除该文件"}
                if(item.row.optString("category")=="history")clearedHistory+=file.parentFile!!.name+"/"+file.name
            }
            count++;bytes+=item.row.optLong("bytes")
        } catch(e:Exception){failed+=obj("id" to item.row.optString("id"),"name" to item.row.optString("name"),"message" to e.message)}
        if(clearedHistory.isNotEmpty())StorageCleanupSignals.history.tryEmit(clearedHistory)
        obj("processed" to count,"bytes" to bytes,"freedBytes" to bytes,"failed" to failed.size,"failures" to JSONArray(failed)).also{operation.result=it}
    }
}
