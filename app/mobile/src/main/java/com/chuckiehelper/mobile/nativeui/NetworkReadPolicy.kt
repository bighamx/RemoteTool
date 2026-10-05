package com.chuckiehelper.mobile.nativeui

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ApiRequestFailure(message: String, val status: Int) : IOException(message)
class ReadConnectionFailure(cause: IOException) : IOException("读取时连接暂时中断，请重新连接", cause)

fun mayRetryRead(method: String, probing: Boolean, attempt: Int, error: IOException, noRetry: Boolean = false): Boolean =
    method in setOf("GET", "HEAD") && !probing && !noRetry && attempt == 0 &&
        (error !is ApiRequestFailure || error.status in setOf(502, 503, 504)) && error !is LoginRequired

fun connectionFailureMessage(error: Exception): String = when (error) {
    is ReadConnectionFailure -> error.message!!
    is UnknownHostException -> "无法解析设备地址，请检查网络后重新连接"
    is SocketTimeoutException -> "连接超时，请检查当前设备通道"
    is SocketException -> "网络连接已中断，请重新连接"
    else -> error.message ?: "请求失败"
}
