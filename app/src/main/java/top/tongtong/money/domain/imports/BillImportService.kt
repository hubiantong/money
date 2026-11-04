package top.tongtong.money.domain.imports

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.tongtong.money.data.local.AppDatabase
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.ImportLogEntity
import top.tongtong.money.data.local.ImportStatus
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.data.local.TxSource
import top.tongtong.money.data.repo.LedgerRepository
import top.tongtong.money.domain.ledger.PeriodCalculator
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

data class ImportOutcome(
    val fileName: String,
    val totalRows: Int,
    val importedRows: Int,
    val duplicateRows: Int,
    val skippedRows: Int,
    val earliestAt: Long?,
    val latestAt: Long?,
    val skippedNeutral: Int,
)

class BillImportService(
    private val context: Context,
    private val db: AppDatabase,
    private val ledgerRepository: LedgerRepository,
    private val downloader: BillDownloader,
    private val zipExtractor: ZipExtractor = ZipExtractor(),
) {

    suspend fun importBytes(
        bytes: ByteArray,
        fileName: String,
        password: String?,
        candidateId: Long? = null,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "bill_import_${UUID.randomUUID()}")
        try {
            val parsed = parseSource(bytes, fileName, password, tempDir)
            persist(parsed, candidateId)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            if (exception !is PasswordRequiredException && exception !is WrongPasswordException) {
                runCatching {
                    db.importLogDao().insert(
                        ImportLogEntity(
                            candidateId = candidateId,
                            fileName = fileName,
                            status = ImportStatus.FAILED,
                            message = exception.message ?: "导入失败",
                        ),
                    )
                }
            }
            throw exception
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun download(url: String): ByteArray = downloader.download(url)

    private suspend fun parseSource(
        bytes: ByteArray,
        fileName: String,
        password: String?,
        tempDir: File,
    ): ParsedBill {
        tempDir.mkdirs()
        var name = fileName
        var data = bytes

        if (name.endsWith(".eml", true) || looksLikeEml(data)) {
            val info = EmlParser.parse(data)
            val attachment = info.attachments.firstOrNull {
                it.fileName.endsWith(".zip", true) ||
                    it.fileName.endsWith(".xlsx", true) ||
                    it.fileName.endsWith(".csv", true)
            }
            data = when {
                info.downloadUrl != null -> downloader.download(info.downloadUrl)
                attachment != null -> attachment.bytes
                else -> throw BillFormatException("邮件中没有账单下载链接或附件")
            }
            name = attachment?.fileName ?: info.subject.ifBlank { "微信支付账单" }
        }

        val source = File(tempDir, "bill_source")
        source.writeBytes(data)

        if (name.endsWith(".csv", true)) {
            return WechatBillParser.parse(CsvReader.read(source), name)
        }
        if (name.endsWith(".xlsx", true) || hasWorkbook(source)) {
            return WechatBillParser.parse(XlsxReader.read(source), name)
        }
        if (isZip(source)) {
            val inner = zipExtractor.extract(source, password, File(tempDir, "unzipped"))
            return readTable(inner)
        }
        // 兜底：当作 CSV 文本解析（部分邮件直接给文本表格）
        val asCsv = CsvReader.read(source)
        if (asCsv.any { row -> row.any { WechatBillParser.normalizeHeader(it) == "交易时间" } }) {
            return WechatBillParser.parse(asCsv, name)
        }
        throw BillFormatException("无法识别的账单文件格式")
    }

    private fun readTable(file: File): ParsedBill {
        val name = file.name
        val rows = when {
            name.endsWith(".xlsx", true) -> XlsxReader.read(file)
            name.endsWith(".csv", true) -> CsvReader.read(file)
            hasWorkbook(file) -> XlsxReader.read(file)
            else -> throw BillFormatException("不支持的账单文件：$name")
        }
        return WechatBillParser.parse(rows, name)
    }

    private suspend fun persist(parsed: ParsedBill, candidateId: Long?): ImportOutcome {
        if (parsed.rows.isEmpty()) {
            throw BillFormatException("未解析到交易记录")
        }
        val categories = db.categoryDao().getAll()
        val fallback = categories.firstOrNull { it.name == "未分类" }
            ?: CategoryEntity(
                name = "未分类",
                emoji = "❓",
                isSystem = true,
                sortOrder = 999,
            ).let { entity ->
                val id = db.categoryDao().insert(entity)
                entity.copy(id = id)
            }
        val categorizer = MerchantCategorizer(db.merchantRuleDao().getAll())

        var imported = 0
        var duplicates = 0
        var neutral = 0
        val now = System.currentTimeMillis()
        val digest = MessageDigest.getInstance("SHA-256")

        // 整批写入放在同一事务里，避免逐行提交，也保证中途失败时不会留下半批数据。
        db.inTransaction {
            for (row in parsed.rows) {
                if (row.direction == TxDirection.NEUTRAL) neutral++
                val categoryId = if (row.direction == TxDirection.NEUTRAL) {
                    null
                } else {
                    categorizer.categorize(row.merchant, row.product, fallback.id)
                }
                val dedupKey = if (row.externalId == null) contentKey(row, digest) else null
                // 手动记账可能早于账单导入：命中时把账单单号挂到手动记录上，保留用户填写的分类/备注
                val manual = findManualDuplicate(row)
                if (manual != null) {
                    linkManual(manual, row.externalId, dedupKey, now)
                    duplicates++
                    continue
                }
                val entity = TransactionEntity(
                    externalId = row.externalId,
                    dedupKey = dedupKey,
                    source = TxSource.WECHAT,
                    direction = row.direction,
                    amountCents = row.amountCents,
                    categoryId = categoryId,
                    merchant = row.merchant,
                    product = row.product,
                    note = row.note,
                    occurredAt = row.occurredAt,
                    rawType = row.rawType,
                    payMethod = row.payMethod,
                    status = row.status,
                    createdAt = now,
                    updatedAt = now,
                )
                val id = db.transactionDao().insertIgnore(entity)
                if (id == -1L) duplicates++ else imported++
            }
        }

        val earliest = parsed.rows.minOfOrNull { it.occurredAt }
        val latest = parsed.rows.maxOfOrNull { it.occurredAt }
        if (earliest != null) {
            ledgerRepository.rebuildFrom(PeriodCalculator.monthKeyOf(earliest))
        }

        db.importLogDao().insert(
            ImportLogEntity(
                candidateId = candidateId,
                fileName = parsed.fileName,
                totalRows = parsed.rows.size + parsed.skippedRows,
                importedRows = imported,
                duplicateRows = duplicates,
                skippedRows = parsed.skippedRows,
                earliestAt = earliest,
                latestAt = latest,
                status = ImportStatus.SUCCESS,
                message = "中性交易 $neutral 条",
            ),
        )

        return ImportOutcome(
            fileName = parsed.fileName,
            totalRows = parsed.rows.size + parsed.skippedRows,
            importedRows = imported,
            duplicateRows = duplicates,
            skippedRows = parsed.skippedRows,
            earliestAt = earliest,
            latestAt = latest,
            skippedNeutral = neutral,
        )
    }

    /** 查找与导入行匹配的手动记账（同方向同金额，时间窗口见 [ManualDuplicateMatcher]）。 */
    private suspend fun findManualDuplicate(row: ParsedBillRow): TransactionEntity? {
        val window = ManualDuplicateMatcher.SIMILAR_WINDOW_MILLIS
        val candidates = db.transactionDao().findManualCandidates(
            direction = row.direction.name,
            amountCents = row.amountCents,
            from = row.occurredAt - window,
            to = row.occurredAt + window,
        )
        // 已挂过单号/内容指纹的手动记录不再是"纯手动"来源，避免同一笔被反复认领
        return candidates.firstOrNull {
            it.externalId == null && it.dedupKey == null && ManualDuplicateMatcher.isDuplicate(row, it)
        }
    }

    /** 把账单单号/内容指纹写到手动记录上，后续重复导入由唯一索引自动拦截。 */
    private suspend fun linkManual(
        manual: TransactionEntity,
        importedExternalId: String?,
        dedupKey: String?,
        now: Long,
    ) {
        val externalId = importedExternalId?.takeIf { db.transactionDao().findByExternalId(it) == null }
        val fingerprint = dedupKey?.takeIf { db.transactionDao().findByDedupKey(it) == null }
        if (externalId == null && fingerprint == null) return
        db.transactionDao().update(
            manual.copy(
                externalId = externalId ?: manual.externalId,
                dedupKey = fingerprint ?: manual.dedupKey,
                updatedAt = now,
            ),
        )
    }

    private fun hasWorkbook(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { it.name == "xl/workbook.xml" }
        }
    }.getOrDefault(false)

    private fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            if (stream.read(header) != 4) return@use false
            val signature = header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            signature && (
                (header[2] == 3.toByte() && header[3] == 4.toByte()) ||
                    (header[2] == 5.toByte() && header[3] == 6.toByte())
                )
        }
    }.getOrDefault(false)

    private fun looksLikeEml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
        return head.contains("MIME-Version:") || head.contains("Content-Type: multipart/")
    }

    private fun contentKey(row: ParsedBillRow, digest: MessageDigest): String {
        val payload = "${row.occurredAt}|${row.amountCents}|${row.direction.name}|${row.merchant}|${row.product}"
        digest.reset()
        val hash = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
