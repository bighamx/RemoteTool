package com.chuckiehelper.mobile.startup

import android.content.Context
import com.chuckiehelper.mobile.nativeui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI

internal class StartupLoginRequired : IOException("请先登录目标电脑并保存登录信息")

/** Same identity-before-credentials boundary as interactive device connections. */
internal suspend fun startupConnection(context: Context, target: StartupSettings.Target): NativeApi {
    val addresses = (listOfNotNull(target.preferred) + target.addresses).distinct()
    var last: Exception? = null
    for (address in addresses) {
        try {
            val uri = URI(address)
            require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty())
            val identity = NativeApi(address, probing = true).json("/api/device/identity").optString("id")
            if (identity != target.id) { last = IOException("电脑身份不匹配"); continue }
            val authentication = DeviceAuthSession()
            val api = NativeApi(address, authentication = authentication)
            val credentials = DeviceCredentialStore(context)
            val recovered = DeviceAuthentication.recover(target.id, identity,
                read = { credentials.read(target.id) }, legacyTokens = { emptyList() },
                validate = { token ->
                    authentication.token = token
                    try { api.json("/api/auth/me"); true } catch (_: LoginRequired) { false }
                },
                signIn = { saved -> try { api.login(saved.username, saved.password) } catch (_: LoginRequired) { null } },
                save = { credentials.save(target.id, it) },
            ) ?: throw StartupLoginRequired()
            api.installToken(recovered.token)
            val refresh = Mutex()
            api.onUnauthorized = { failed -> refresh.withLock {
                DeviceAuthentication.refresh(failed, authentication.token,
                    read = { DeviceCredentialStore(context).read(target.id) },
                    signIn = { saved -> try { api.login(saved.username, saved.password) } catch (_: LoginRequired) { null } },
                    save = { DeviceCredentialStore(context).save(target.id, it) })
            } }
            return api
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (required: StartupLoginRequired) { throw required }
        catch (failure: Exception) { last = failure }
    }
    throw IOException("等待电脑连接", last)
}
