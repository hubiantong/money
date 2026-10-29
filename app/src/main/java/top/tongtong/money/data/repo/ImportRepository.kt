package top.tongtong.money.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.tongtong.money.data.local.AppDatabase
import top.tongtong.money.data.local.CandidateStatus
import top.tongtong.money.data.local.ImportCandidateEntity
import top.tongtong.money.data.local.ImportLogEntity
import top.tongtong.money.data.prefs.SecureStore
import top.tongtong.money.data.prefs.SettingsRepository
import top.tongtong.money.domain.imports.BillImportService
import top.tongtong.money.domain.imports.EmailBillInfo
import top.tongtong.money.domain.imports.ExpiredBillLinkException
import top.tongtong.money.domain.imports.ImportOutcome
import top.tongtong.money.domain.imports.ImapConfig
import top.tongtong.money.domain.imports.ImapMailClient
import top.tongtong.money.domain.imports.PasswordRequiredException
import top.tongtong.money.domain.imports.WrongPasswordException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class PickedBillFile(
    val name: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PickedBillFile && name == other.name && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

data class ImportSyncSummary(
    val fetched: Int = 0,
    val imported: Int = 0,
    val needPassword: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
)

enum class CandidateAttempt { IMPORTED, NEED_PASSWORD, FAILED }

class ImportRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val secureStore: SecureStore,
    private val importService: BillImportService,
    private val mailClient: ImapMailClient = ImapMailClient(),
) {

    val candidates: Flow<List<ImportCandidateEntity>> = db.importCandidateDao().observeAll()
    val logs: Flow<List<ImportLogEntity>> = db.importLogDao().observeAll()

    fun hasAuthCode(): Boolean = !secureStore.imapAuthCode().isNullOrBlank()

    fun imapAuthCode(): String? = secureStore.imapAuthCode()

    suspend fun saveEmailConfig(
        host: String,
        port: Int,
        ssl: Boolean,
        user: String,
        folder: String,
        authCode: String? = null,
    ) {
        settingsRepository.setEmailConfig(host, port, ssl, user, folder)
        if (!authCode.isNullOrBlank()) secureStore.setImapAuthCode(authCode.trim())
    }

    suspend fun testConnection(
        host: String,
        port: Int,
        ssl: Boolean,
        user: String,
        folder: String,
        authCode: String,
    ): Int {
        val config = ImapConfig(host = host, port = port, ssl = ssl, user = user, folder = folder)
        return mailClient.testConnection(config, authCode)
    }

    suspend fun syncNow(): ImportSyncSummary = withContext(Dispatchers.IO) {
        purgeStaleAttachmentCache()
        val settings = settingsRepository.flow.first()
        val authCode = secureStore.imapAuthCode()
            ?: throw IOException("尚未配置邮箱授权码")
        val config = ImapConfig(
            host = settings.emailHost,
            port = settings.emailPort,
            ssl = settings.emailSsl,
            user = settings.emailUser,
            folder = settings.emailFolder,
        )
        val mails = mailClient.fetchRecentBills(
            config,
            authCode,
            days = settings.emailFetchDays.coerceAtLeast(1),
            limit = settings.emailFetchLimit.coerceAtLeast(1),
        )
        val storedPassword = if (settings.rememberZipPassword) secureStore.zipPassword() else null

        var imported = 0
        var needPassword = 0
        var failed = 0
        var skipped = 0

        for (info in mails) {
            val existing = db.importCandidateDao().findByMessageId(info.messageId)
            if (existing != null && existing.status == CandidateStatus.IMPORTED) {
                skipped++
                continue
            }
            val candidateId = existing?.id ?: insertCandidate(info)
            if (candidateId <= 0L) {
                skipped++
                continue
            }
            val candidate = db.importCandidateDao().getById(candidateId) ?: continue
            val attachment = info.attachments.firstOrNull {
                it.fileName.endsWith(".zip", true) ||
                    it.fileName.endsWith(".xlsx", true) ||
                    it.fileName.endsWith(".csv", true)
            }
            val result = if (info.downloadUrl == null && attachment != null) {
                importAttachment(candidate, attachment.bytes, attachment.fileName, storedPassword)
            } else {
                attempt(candidate, storedPassword)
            }
            when (result) {
                CandidateAttempt.IMPORTED -> imported++
                CandidateAttempt.NEED_PASSWORD -> needPassword++
                CandidateAttempt.FAILED -> failed++
            }
        }

        settingsRepository.setEmailLastSync(System.currentTimeMillis())
        ImportSyncSummary(
            fetched = mails.size,
            imported = imported,
            needPassword = needPassword,
            failed = failed,
            skipped = skipped,
        )
    }

    /** 用户在候选列表输入解压码后重试。 */
    suspend fun submitPassword(
        candidateId: Long,
        password: String,
        remember: Boolean,
    ): CandidateAttempt = withContext(Dispatchers.IO) {
        val candidate = db.importCandidateDao().getById(candidateId)
            ?: return@withContext CandidateAttempt.FAILED
        val result = attempt(candidate, password)
        if (result == CandidateAttempt.IMPORTED && remember) {
            secureStore.setZipPassword(password)
            settingsRepository.setRememberZipPassword(true)
        }
        result
    }

    suspend fun readPickedFile(uri: Uri): PickedBillFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var declaredSize = -1L
        val name = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                cursor.getString(0)
            } else {
                null
            }
        } ?: "手动导入账单"
        if (declaredSize > MAX_PICK_BYTES) {
            throw IOException("所选文件过大，请选择 20MB 以内的账单文件")
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("无法读取所选文件")
        PickedBillFile(name = name, bytes = bytes)
    }

    suspend fun importPickedFile(
        file: PickedBillFile,
        password: String?,
        rememberPassword: Boolean,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val outcome = importService.importBytes(file.bytes, file.name, password)
        if (rememberPassword && !password.isNullOrBlank()) {
            secureStore.setZipPassword(password)
            settingsRepository.setRememberZipPassword(true)
        }
        outcome
    }

    private suspend fun insertCandidate(info: EmailBillInfo): Long {
        return db.importCandidateDao().insertIgnore(
            ImportCandidateEntity(
                messageId = info.messageId,
                subject = info.subject.ifBlank { "微信支付账单" },
                fromAddress = info.fromAddress,
                receivedAt = info.receivedAt,
                periodStart = info.periodStart,
                periodEnd = info.periodEnd,
                downloadUrl = info.downloadUrl,
                fileName = "微信支付账单.zip",
                status = CandidateStatus.PENDING_PASSWORD,
            ),
        )
    }

    private suspend fun attempt(
        candidate: ImportCandidateEntity,
        password: String?,
    ): CandidateAttempt {
        val url = candidate.downloadUrl
        if (url.isNullOrBlank()) {
            // 附件路径：加密附件在首次拉取时已缓存到私有目录，输入解压码后从缓存重试
            val cached = attachmentCacheFile(candidate.messageId)
            if (cached.isFile) {
                return try {
                    val bytes = cached.readBytes()
                    persistCandidate(
                        candidate.id,
                        bytes,
                        candidate.fileName ?: "微信支付账单.zip",
                        password,
                    )
                    cached.delete()
                    CandidateAttempt.IMPORTED
                } catch (exception: PasswordRequiredException) {
                    db.importCandidateDao().updateStatus(
                        candidate.id,
                        CandidateStatus.PENDING_PASSWORD,
                        "账单已加密，请输入解压码",
                    )
                    CandidateAttempt.NEED_PASSWORD
                } catch (exception: WrongPasswordException) {
                    db.importCandidateDao().updateStatus(
                        candidate.id,
                        CandidateStatus.PENDING_PASSWORD,
                        "解压码不正确，请重新输入",
                    )
                    CandidateAttempt.NEED_PASSWORD
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    db.importCandidateDao().updateStatus(
                        candidate.id,
                        CandidateStatus.FAILED,
                        exception.message ?: "导入失败",
                    )
                    CandidateAttempt.FAILED
                }
            }
            db.importCandidateDao().updateStatus(
                candidate.id,
                CandidateStatus.FAILED,
                "邮件中没有账单下载链接",
            )
            return CandidateAttempt.FAILED
        }
        return try {
            val bytes = importService.download(url)
            persistCandidate(candidate.id, bytes, candidate.fileName ?: "微信支付账单.zip", password)
            CandidateAttempt.IMPORTED
        } catch (exception: PasswordRequiredException) {
            db.importCandidateDao().updateStatus(
                candidate.id,
                CandidateStatus.PENDING_PASSWORD,
                "账单已加密，请输入解压码",
            )
            CandidateAttempt.NEED_PASSWORD
        } catch (exception: WrongPasswordException) {
            db.importCandidateDao().updateStatus(
                candidate.id,
                CandidateStatus.PENDING_PASSWORD,
                "解压码不正确，请重新输入",
            )
            CandidateAttempt.NEED_PASSWORD
        } catch (exception: ExpiredBillLinkException) {
            db.importCandidateDao().updateStatus(
                candidate.id,
                CandidateStatus.EXPIRED,
                exception.message ?: "下载链接已过期",
            )
            CandidateAttempt.FAILED
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            db.importCandidateDao().updateStatus(
                candidate.id,
                CandidateStatus.FAILED,
                exception.message ?: "导入失败",
            )
            CandidateAttempt.FAILED
        }
    }

    private suspend fun importAttachment(
        candidate: ImportCandidateEntity,
        bytes: ByteArray,
        fileName: String,
        password: String?,
    ): CandidateAttempt {
        return try {
            persistCandidate(candidate.id, bytes, fileName, password)
            CandidateAttempt.IMPORTED
        } catch (exception: PasswordRequiredException) {
            // 附件内容缓存到私有目录，用户输入解压码后可从缓存重试
            cacheAttachment(candidate.messageId, bytes)
            db.importCandidateDao().updateStatus(
                candidate.id,
                CandidateStatus.PENDING_PASSWORD,
                "账单附件已加密，请输入解压码",
            )
            CandidateAttempt.NEED_PASSWORD
        } catch (exception: WrongPasswordException) {
            cacheAttachment(candidate.messageId, bytes)
            db.importCandidateDao().updateStatus(
                candidate.id,
                CandidateStatus.PENDING_PASSWORD,
                "解压码不正确，请重新输入",
            )
            CandidateAttempt.NEED_PASSWORD
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            db.importCandidateDao().updateStatus(
                candidate.id,
                CandidateStatus.FAILED,
                exception.message ?: "导入失败",
            )
            CandidateAttempt.FAILED
        }
    }

    /**
     * 加密附件缓存：内容本身是 ZipCrypto 加密的，放在应用私有 cacheDir；
     * messageId 可能包含文件系统非法字符，落盘前先做 SHA-256。
     */
    private fun attachmentCacheFile(messageId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(messageId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "bill_attachments"), "$name.bin")
    }

    private fun cacheAttachment(messageId: String, bytes: ByteArray) {
        runCatching {
            val file = attachmentCacheFile(messageId)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    /** 清理超过 7 天仍未处理的附件缓存，避免导入一直悬着时占住空间。 */
    private fun purgeStaleAttachmentCache() {
        runCatching {
            val dir = File(context.cacheDir, "bill_attachments")
            val cutoff = System.currentTimeMillis() - STALE_ATTACHMENT_MILLIS
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    private companion object {
        const val MAX_PICK_BYTES = 20L * 1024 * 1024
        const val STALE_ATTACHMENT_MILLIS = 7L * 24 * 60 * 60 * 1000
    }

    private suspend fun persistCandidate(
        candidateId: Long,
        bytes: ByteArray,
        fileName: String,
        password: String?,
    ) {
        importService.importBytes(bytes, fileName, password, candidateId)
        db.importCandidateDao().markImported(
            candidateId,
            CandidateStatus.IMPORTED,
            null,
            System.currentTimeMillis(),
        )
    }
}
