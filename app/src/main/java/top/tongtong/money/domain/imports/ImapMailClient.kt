package top.tongtong.money.domain.imports

import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Store

data class ImapConfig(
    val host: String,
    val port: Int,
    val ssl: Boolean,
    val user: String,
    val folder: String = "INBOX",
)

class ImapConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ImapMailClient {

    /** 仅建立连接并读取文件夹邮件数，用于配置页“测试连接”。 */
    suspend fun testConnection(config: ImapConfig, authCode: String): Int =
        withContext(Dispatchers.IO) {
            withFolder(config, authCode) { folder -> folder.messageCount }
        }

    /** 拉取最近若干天的微信支付账单邮件。 */
    suspend fun fetchRecentBills(
        config: ImapConfig,
        authCode: String,
        days: Int = 7,
        limit: Int = 20,
    ): List<EmailBillInfo> = withContext(Dispatchers.IO) {
        withFolder(config, authCode) { folder ->
            EmlParser.fetchRecent(folder, days = days, limit = limit)
        }
    }

    private fun <T> withFolder(
        config: ImapConfig,
        authCode: String,
        block: (IMAPFolder) -> T,
    ): T {
        if (config.host.isBlank() || config.user.isBlank()) {
            throw ImapConnectionException("请先填写邮箱服务器与账号")
        }
        if (authCode.isBlank()) {
            throw ImapConnectionException("请先填写邮箱授权码")
        }
        val protocol = if (config.ssl) "imaps" else "imap"
        val properties = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", config.host.trim())
            put("mail.$protocol.port", config.port.toString())
            put("mail.$protocol.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
            put("mail.$protocol.timeout", READ_TIMEOUT_MS.toString())
            put("mail.$protocol.writetimeout", WRITE_TIMEOUT_MS.toString())
            if (config.ssl) {
                put("mail.$protocol.ssl.enable", "true")
                // 显式校验证书主机名，避免任意受信任证书冒充邮箱服务器。
                put("mail.$protocol.ssl.checkserveridentity", "true")
            }
        }
        val session = Session.getInstance(properties)
        var store: Store? = null
        try {
            store = session.getStore(protocol)
            store.connect(config.host.trim(), config.port, config.user.trim(), authCode.trim())
            val folderName = config.folder.trim().ifBlank { "INBOX" }
            val folder = store.getFolder(folderName) as? IMAPFolder
                ?: throw ImapConnectionException("邮箱协议与文件夹类型不匹配：$folderName")
            if (!folder.exists()) {
                throw ImapConnectionException("邮箱文件夹不存在：$folderName")
            }
            return block(folder)
        } catch (exception: AuthenticationFailedException) {
            throw ImapConnectionException("授权码错误或邮箱未开启 IMAP 服务", exception)
        } catch (exception: MessagingException) {
            throw ImapConnectionException(mapMessage(exception), exception)
        } finally {
            runCatching { store?.close() }
        }
    }

    private fun mapMessage(exception: MessagingException): String {
        val text = (exception.message ?: "").lowercase()
        return when {
            text.contains("auth") || text.contains("login") ->
                "授权码错误或邮箱未开启 IMAP 服务"
            text.contains("timeout") || text.contains("connect") ->
                "连接邮箱服务器超时，请检查网络或服务器地址"
            text.contains("certificate") || text.contains("ssl") ->
                "邮箱服务器 SSL 证书校验失败"
            else -> "连接邮箱失败：${exception.message ?: "未知错误"}"
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20000
        const val READ_TIMEOUT_MS = 30000
        const val WRITE_TIMEOUT_MS = 30000
    }
}
