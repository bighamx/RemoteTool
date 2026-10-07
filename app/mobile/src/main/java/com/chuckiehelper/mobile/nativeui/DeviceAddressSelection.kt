package com.chuckiehelper.mobile.nativeui

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun deviceAddressCandidates(addresses: List<String>, preferred: String?): List<String> =
    (listOfNotNull(preferred?.takeIf { it in addresses }) + addresses).distinct()

internal class DeviceAddressFailure(val attempts: List<Pair<String, Exception>>) : IOException(
    "所有地址均无法连接（${attempts.size} 个）：" + attempts.joinToString("；") { (url, error) -> "$url：${error.message ?: error.javaClass.simpleName}" },
    attempts.lastOrNull()?.second,
)

internal suspend fun <T> selectVerifiedDeviceAddress(
    addresses: List<String>, expectedIdentity: String,
    probe: suspend (String) -> Pair<String, T>,
): Pair<String, T> {
    val failures = mutableListOf<Pair<String, Exception>>()
    for (url in addresses.distinct()) {
        currentCoroutineContext().ensureActive()
        try {
            val result = probe(url)
            currentCoroutineContext().ensureActive()
            if (result.first != expectedIdentity) throw IOException("设备标识不匹配，连接到了另一台电脑")
            return result
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); failures += url to error }
    }
    if (failures.size == 1) throw failures.single().second
    throw DeviceAddressFailure(failures)
}
