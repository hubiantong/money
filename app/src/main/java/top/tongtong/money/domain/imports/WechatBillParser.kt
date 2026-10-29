package top.tongtong.money.domain.imports

import top.tongtong.money.data.local.TxDirection
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedBillRow(
    val occurredAt: Long,
    val amountCents: Long,
    val direction: TxDirection,
    val rawType: String,
    val merchant: String,
    val product: String,
    val payMethod: String,
    val status: String,
    val externalId: String?,
    val merchantOrderId: String,
    val note: String,
)

data class ParsedBill(
    val rows: List<ParsedBillRow>,
    val skippedRows: Int,
    val fileName: String,
)

object WechatBillParser {

    private val zone = BILL_TIMEZONE
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dateTimeNoSeconds = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    private val amountClean = Regex("""[¥￥,，\u00A0元\s]""")

    data class ColumnMap(
        val time: Int,
        val type: Int,
        val merchant: Int,
        val product: Int,
        val direction: Int,
        val amount: Int,
        val payMethod: Int,
        val status: Int,
        val externalId: Int,
        val merchantOrderId: Int,
        val note: Int,
    )

    fun parse(rows: List<List<String>>, fileName: String): ParsedBill {
        val headerIndex = rows.indexOfFirst { row ->
            val normalized = row.map { normalizeHeader(it) }
            normalized.contains("交易时间") && normalized.any { it.startsWith("金额") }
        }
        if (headerIndex < 0) {
            throw BillFormatException("未找到微信账单表头（交易时间 / 金额(元)）")
        }
        val header = rows[headerIndex].map { normalizeHeader(it) }
        val columns = ColumnMap(
            time = header.indexOf("交易时间"),
            type = header.indexOf("交易类型"),
            merchant = header.indexOf("交易对方"),
            product = header.indexOf("商品"),
            direction = header.indexOf("收/支"),
            amount = header.indexOfFirst { it.startsWith("金额") },
            payMethod = header.indexOf("支付方式"),
            status = header.indexOf("当前状态"),
            externalId = header.indexOf("交易单号"),
            merchantOrderId = header.indexOf("商户单号"),
            note = header.indexOf("备注"),
        )
        if (columns.time < 0 || columns.amount < 0 || columns.direction < 0) {
            throw BillFormatException("账单表头缺少必要字段")
        }

        val parsed = mutableListOf<ParsedBillRow>()
        var skipped = 0
        for (index in headerIndex + 1 until rows.size) {
            val row = rows[index]
            if (row.all { it.isBlank() }) continue
            val timeText = row.valueAt(columns.time).trim()
            val amountText = row.valueAt(columns.amount).trim()
            if (timeText.isBlank() || amountText.isBlank()) {
                skipped++
                continue
            }
            val occurredAt = parseTime(timeText)
            if (occurredAt == null) {
                skipped++
                continue
            }
            val amountCents = parseAmount(amountText)
            if (amountCents == null) {
                skipped++
                continue
            }
            val directionText = row.valueAt(columns.direction).trim()
            if (directionText.isBlank()) {
                skipped++
                continue
            }
            val status = row.valueAt(columns.status).trim()
            if (directionText == "支出" && status.contains("退款")) {
                // 退款会以独立收入行出现，避免重复冲减
                skipped++
                continue
            }
            val rawType = row.valueAt(columns.type).trim()
            val direction = when {
                directionText == "支出" -> TxDirection.EXPENSE
                // 真实账单里退款行的交易类型常为“商户消费”，退款标记落在当前状态上。
                directionText == "收入" && (rawType.contains("退款") || status.contains("退款")) ->
                    TxDirection.REFUND
                directionText == "收入" -> TxDirection.INCOME
                else -> TxDirection.NEUTRAL
            }
            val externalId = row.valueAt(columns.externalId).trim().ifBlank { null }
            parsed += ParsedBillRow(
                occurredAt = occurredAt,
                amountCents = amountCents,
                direction = direction,
                rawType = rawType,
                merchant = row.valueAt(columns.merchant).trim(),
                product = row.valueAt(columns.product).trim(),
                payMethod = row.valueAt(columns.payMethod).trim(),
                status = status,
                externalId = externalId,
                merchantOrderId = row.valueAt(columns.merchantOrderId).trim(),
                note = row.valueAt(columns.note).trim(),
            )
        }
        return ParsedBill(rows = parsed, skippedRows = skipped, fileName = fileName)
    }

    fun normalizeHeader(value: String): String =
        value.removePrefix("\uFEFF")
            .replace('\u3000', ' ')
            .replace('（', '(')
            .replace('）', ')')
            .replace(" ", "")
            .trim()

    private fun List<String>.valueAt(index: Int): String = if (index in indices) this[index] else ""

    private fun parseTime(value: String): Long? {
        val normalized = value.replace('/', '-').trim()
        return runCatching {
            LocalDateTime.parse(normalized, dateTime).atZone(zone).toInstant().toEpochMilli()
        }.recoverCatching {
            LocalDateTime.parse(normalized, dateTimeNoSeconds).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun parseAmount(value: String): Long? {
        val cleaned = value.replace(amountClean, "")
        if (cleaned.isBlank()) return null
        return runCatching {
            BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()
    }
}

class BillFormatException(message: String) : Exception(message)
