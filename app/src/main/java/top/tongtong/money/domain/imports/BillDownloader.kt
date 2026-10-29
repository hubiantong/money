package top.tongtong.money.domain.imports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExpiredBillLinkException(message: String) : Exception(message)

class BillDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .followRedirects(true)
        .build(),
) {
    suspend fun download(url: String): ByteArray {
        require(url.startsWith("https://", ignoreCase = true)) { "账单下载链接必须为 HTTPS" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).awaitResponse().use { response ->
                if (response.code == 403 || response.code == 404 || response.code == 410) {
                    throw ExpiredBillLinkException("账单下载链接已过期或无效，请在微信中重新申请账单")
                }
                if (!response.isSuccessful) {
                    throw IOException("下载账单失败：HTTP ${response.code}")
                }
                val declared = response.body.contentLength()
                if (declared > MAX_BYTES) {
                    throw IOException("账单文件过大")
                }
                response.body.byteStream().use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("账单文件过大")
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            }
        }
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isCancelled) {
                        response.close()
                        return
                    }
                    continuation.resume(response)
                }
            },
        )
        continuation.invokeOnCancellation { runCatching { cancel() } }
    }

    private companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
    }
}
