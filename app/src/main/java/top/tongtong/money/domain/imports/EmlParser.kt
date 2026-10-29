package top.tongtong.money.domain.imports

import com.sun.mail.imap.IMAPFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties
import javax.mail.Address
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility

data class EmailAttachment(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EmailAttachment &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        (fileName.hashCode() * 31 + contentType.hashCode()) * 31 + bytes.contentHashCode()
}

data class EmailBillInfo(
    val messageId: String,
    val subject: String,
    val fromAddress: String,
    val receivedAt: Long,
    val downloadUrl: String?,
    val attachments: List<EmailAttachment>,
    val periodStart: Long?,
    val periodEnd: Long?,
)

object EmlParser {

    private val zone = BILL_TIMEZONE
    private val subjectPeriod = Regex("""\((\d{8})-(\d{8})\)""")
    private val linkRegex = Regex("""https://(?:[A-Za-z0-9-]+\.)*wechatpay\.cn/[^\s"'<>]+""")

    fun isWechatBill(info: EmailBillInfo): Boolean {
        val fromMatch = info.fromAddress.contains("wechatpay@tencent.com", ignoreCase = true)
        val subjectMatch = info.subject.contains("微信支付") && info.subject.contains("账单")
        return fromMatch || subjectMatch
    }

    fun parse(raw: ByteArray): EmailBillInfo {
        val session = Session.getInstance(Properties())
        val message = MimeMessage(session, ByteArrayInputStream(raw))
        return parse(message)
    }

    fun parse(message: Message): EmailBillInfo {
        val subject = runCatching { MimeUtility.decodeText(message.subject ?: "") }.getOrDefault("")
        val from = runCatching { formatAddresses(message.from) }.getOrDefault("")
        val received = runCatching {
            (message.receivedDate ?: message.sentDate)?.time
        }.getOrNull() ?: System.currentTimeMillis()

        val html = StringBuilder()
        val text = StringBuilder()
        val attachments = mutableListOf<EmailAttachment>()
        runCatching { collect(message, html, text, attachments) }

        val body = html.toString().ifBlank { text.toString() }
        val url = extractDownloadUrl(body)
        val period = parseSubjectPeriod(subject)
        val messageId = runCatching {
            message.getHeader("Message-ID")?.firstOrNull()?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "generated-${subject.hashCode()}-${received}"
        return EmailBillInfo(
            messageId = messageId,
            subject = subject,
            fromAddress = from,
            receivedAt = received,
            downloadUrl = url,
            attachments = attachments,
            periodStart = period?.first,
            periodEnd = period?.second,
        )
    }

    fun extractDownloadUrl(text: String): String? {
        val unescaped = text.replace("&amp;", "&")
        val match = linkRegex.find(unescaped) ?: return null
        return match.value.trimEnd('.', ',', ';', ')')
    }

    private fun parseSubjectPeriod(subject: String): Pair<Long, Long>? {
        val match = subjectPeriod.find(subject) ?: return null
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        return runCatching {
            val start = LocalDate.parse(match.groupValues[1], formatter)
            val end = LocalDate.parse(match.groupValues[2], formatter)
            val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            startMillis to endMillis
        }.getOrNull()
    }

    private fun formatAddresses(addresses: Array<Address>?): String {
        if (addresses == null) return ""
        return addresses.joinToString(", ") { address ->
            runCatching { address.toString() }.getOrDefault("")
        }
    }

    private fun collect(
        part: Part,
        html: StringBuilder,
        text: StringBuilder,
        attachments: MutableList<EmailAttachment>,
        depth: Int = 0,
    ) {
        if (depth > MAX_MIME_DEPTH) return
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as? Multipart ?: return
            for (index in 0 until multipart.count) {
                collect(multipart.getBodyPart(index), html, text, attachments, depth + 1)
            }
            return
        }
        val fileName = runCatching { part.fileName }.getOrNull()
        val disposition = runCatching { part.disposition }.getOrNull()
        if (fileName != null && (Part.ATTACHMENT.equals(disposition, ignoreCase = true) || disposition == null)) {
            val declaredSize = runCatching { part.size }.getOrNull() ?: -1
            val bytes = when {
                declaredSize in 0..MAX_ATTACHMENT_BYTES ->
                    runCatching { part.inputStream.use { it.readBytes() } }.getOrNull()
                declaredSize < 0 -> runCatching { readAtMost(part, MAX_ATTACHMENT_BYTES) }.getOrNull()
                else -> null
            }
            if (bytes != null) {
                attachments += EmailAttachment(
                    fileName = runCatching { MimeUtility.decodeText(fileName) }.getOrDefault(fileName),
                    contentType = part.contentType ?: "",
                    bytes = bytes,
                )
            }
            return
        }
        when {
            part.isMimeType("text/html") -> {
                val content = runCatching { part.content?.toString() }.getOrNull()
                if (content != null) html.append(content)
            }
            part.isMimeType("text/plain") -> {
                val content = runCatching { part.content?.toString() }.getOrNull()
                if (content != null) text.append(content)
            }
        }
    }

    /** 尺寸未知时按上限读取，超出即放弃，避免整段附件进入内存。 */
    private fun readAtMost(part: Part, limit: Int): ByteArray? {
        part.inputStream.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
    private const val MAX_MIME_DEPTH = 20

    /**
     * 从 IMAP 文件夹拉取最近 7 天的微信支付账单邮件，不做已读标记。
     */
    fun fetchRecent(folder: IMAPFolder, days: Int = 7, limit: Int = 20): List<EmailBillInfo> {
        folder.open( javax.mail.Folder.READ_ONLY)
        return try {
            val total = folder.messageCount
            if (total <= 0) return emptyList()
            val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
            val start = maxOf(1, total - limit * 3)
            val result = mutableListOf<EmailBillInfo>()
            for (index in total downTo start) {
                val message = runCatching { folder.getMessage(index) }.getOrNull() ?: continue
                val received = (message.receivedDate ?: message.sentDate)?.time ?: 0
                // 无收件时间的邮件不能视为过期，避免中断整段扫描。
                if (received in 1L until cutoff) break
                val info = runCatching { parse(message) }.getOrNull() ?: continue
                if (isWechatBill(info)) {
                    result += info
                    if (result.size >= limit) break
                }
            }
            result
        } finally {
            runCatching { folder.close(false) }
        }
    }
}
