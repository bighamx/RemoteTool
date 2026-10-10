package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal const val UPDATE_REPOSITORY = "bighamx/RemoteTool"
internal const val UPDATE_REPOSITORY_ID = 368781353L
internal const val UPDATE_RELEASES_URL = "https://api.github.com/repositories/368781353/releases?per_page=30"
private val updateRepositoryAliases = setOf(UPDATE_REPOSITORY, "bighamx/chuckieTool")
internal const val UPDATE_MANIFEST = "chuckiehelper-update.json"
internal const val UPDATE_LATEST_MANIFEST_URL = "https://github.com/$UPDATE_REPOSITORY/releases/latest/download/$UPDATE_MANIFEST"
internal const val UPDATE_PACKAGE = "com.chuckiehelper.mobile"
internal const val MAX_UPDATE_SIZE = 256L * 1024 * 1024

internal data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
) {
    fun json() = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
        .put("notes", notes).put("apkUrl", apkUrl).put("size", size).put("sha256", sha256)
}

internal fun isUpdateAssetUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == "github.com" && uri.port in setOf(-1, 443) &&
        uri.userInfo == null && updateRepositoryAliases.any {
            uri.rawPath.startsWith("/$it/releases/download/", ignoreCase = true) ||
                uri.rawPath.startsWith("/$it/releases/latest/download/", ignoreCase = true)
        }
}.getOrDefault(false)

internal fun isUpdateTransportUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.userInfo == null &&
        (isUpdateAssetUrl(url) ||
            (uri.host == "api.github.com" && (uri.path == "/repositories/$UPDATE_REPOSITORY_ID/releases" ||
                updateRepositoryAliases.any { uri.path.equals("/repos/$it/releases", ignoreCase = true) })) ||
            uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
}.getOrDefault(false)

/** Web-only releases, drafts and previews are not Android update candidates. */
internal fun appReleaseCandidates(releases: JSONArray): List<JSONObject> =
    (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
        .filter { release ->
            !release.optBoolean("draft") && !release.optBoolean("prerelease") &&
                release.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length()).any { assets.optJSONObject(it)?.optString("name") == UPDATE_MANIFEST }
                } == true
        }.take(5)

internal fun parseAppRelease(manifest: JSONObject, release: JSONObject): AppRelease {
    require(manifest.optInt("schemaVersion") == 1) { "更新清单格式不支持" }
    require(manifest.optString("packageId") == UPDATE_PACKAGE) { "更新清单应用不匹配" }
    val code = manifest.getLong("versionCode")
    require(code in 1..2_100_000_000L) { "更新版本号无效" }
    val name = manifest.getString("versionName").trim()
    require(name.isNotEmpty() && name.length <= 80) { "更新版本名称无效" }
    val sha = manifest.getString("sha256").lowercase()
    require(sha.matches(Regex("[0-9a-f]{64}"))) { "更新包缺少有效 SHA-256" }
    val size = manifest.getLong("size")
    require(size in 1..MAX_UPDATE_SIZE) { "更新包大小无效" }
    val assets = release.getJSONArray("assets")
    val apkName = manifest.getString("apkAsset")
    require(apkName.endsWith(".apk", true) && !apkName.contains('/') && !apkName.contains('\\')) { "更新包名称无效" }
    val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        .singleOrNull { it.optString("name") == apkName } ?: error("发布中缺少对应的 APK")
    require(asset.getLong("size") == size) { "发布文件大小与清单不一致" }
    val url = asset.getString("browser_download_url")
    require(isUpdateAssetUrl(url)) { "更新包地址不属于本项目" }
    return AppRelease(code, name, manifest.optString("notes", release.optString("body")).take(6000), url, size, sha)
}

/** The public latest-release asset endpoint does not consume GitHub API quota.
 * Hash, byte count, installed package identity and signer still pin the downloaded APK. */
internal fun parseLatestAppRelease(manifest: JSONObject): AppRelease {
    val name = manifest.getString("apkAsset")
    require(name.matches(Regex("[a-zA-Z0-9._+-]+\\.apk"))) { "更新包名称无效" }
    val asset = JSONObject().put("name", name).put("size", manifest.getLong("size"))
        .put("browser_download_url", "https://github.com/$UPDATE_REPOSITORY/releases/latest/download/$name")
    return parseAppRelease(manifest, JSONObject().put("assets", JSONArray().put(asset)))
}

internal fun updateHttpFailureMessage(code: Int, remaining: String?, retryAfter: String?): String = when {
    code == 429 || code == 403 && remaining == "0" ->
        "更新服务请求限额已用尽" + (retryAfter?.toLongOrNull()?.takeIf { it > 0 }?.let { "，请在 ${it} 秒后重试" } ?: "，请稍后重试或切换网络")
    code == 403 -> "更新服务拒绝访问（HTTP 403），请检查网络或稍后重试"
    else -> "更新服务 HTTP $code"
}

internal fun restoreAppRelease(value: JSONObject): AppRelease {
    val release = AppRelease(value.getLong("versionCode"), value.getString("versionName"),
        value.optString("notes").take(6000), value.getString("apkUrl"), value.getLong("size"), value.getString("sha256"))
    require(release.versionCode in 1..2_100_000_000L && release.size in 1..MAX_UPDATE_SIZE &&
        release.sha256.matches(Regex("[0-9a-f]{64}")) && isUpdateAssetUrl(release.apkUrl))
    return release
}

internal fun validateUpdateIdentity(packageName: String, versionCode: Long, signers: Set<String>,
    currentCode: Long, currentSigners: Set<String>, release: AppRelease) {
    require(packageName == UPDATE_PACKAGE) { "安装包应用 ID 不匹配" }
    require(versionCode == release.versionCode && versionCode > currentCode) { "安装包版本不匹配或没有提升" }
    require(signers.isNotEmpty() && currentSigners.isNotEmpty() && signers == currentSigners) { "安装包签名不匹配，不能覆盖现有应用" }
}
