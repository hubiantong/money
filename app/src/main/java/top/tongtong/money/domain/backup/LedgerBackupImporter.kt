package top.tongtong.money.domain.backup

import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.data.local.TxSource

data class ParsedBackupCategory(
    val id: Long,
    val name: String,
    val emoji: String,
    val colorArgb: Long,
    val quotaCents: Long?,
    val periodType: PeriodType,
    val feeBp: Int,
    val isSystem: Boolean,
    val archived: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
)

data class ParsedBackupTransaction(
    val externalId: String?,
    val dedupKey: String?,
    val source: TxSource,
    val direction: TxDirection,
    val amountCents: Long,
    val categoryId: Long?,
    val merchant: String,
    val product: String,
    val note: String,
    val occurredAt: Long,
    val rawType: String,
    val payMethod: String,
    val status: String,
    val createdAt: Long,
)

data class ParsedBackupRule(
    val pattern: String,
    val categoryId: Long,
    val priority: Int,
    val createdAt: Long,
)

data class ParsedBackup(
    val version: Int,
    val monthlyBudgetCents: Long?,
    val defaultFeeBp: Int,
    val categories: List<ParsedBackupCategory>,
    val transactions: List<ParsedBackupTransaction>,
    val merchantRules: List<ParsedBackupRule>,
)

/**
 * 解析 [LedgerBackupExporter] 生成的备份 JSON 为数据对象；与 Room 无关，可 JVM 测试。
 * 整体结构非法抛 IllegalArgumentException；数组内单条数据缺失关键金额/时间字段的跳过（返回 null），
 * 其余字段缺失回退到默认值，保证部分损坏的备份也能尽量恢复。
 */
object LedgerBackupImporter {

    fun parse(text: String): ParsedBackup {
        val root = MiniJson.parse(text) as? Map<*, *>
            ?: throw IllegalArgumentException("备份文件不是有效的 JSON 对象")
        val version = asLong(root["version"])?.toInt()
            ?: throw IllegalArgumentException("备份缺少版本号")
        if (version != BACKUP_VERSION) {
            throw IllegalArgumentException("不支持的备份版本：$version，当前支持 $BACKUP_VERSION")
        }
        val app = root["app"] as? String
        if (app != null && app != "tongtong-money") {
            throw IllegalArgumentException("这不是潼潼的账本备份文件")
        }
        val settings = root["settings"] as? Map<*, *>
        return ParsedBackup(
            version = version,
            monthlyBudgetCents = settings?.let { asLong(it["monthlyBudgetCents"]) },
            defaultFeeBp = settings?.let { asLong(it["defaultFeeBp"])?.toInt()?.coerceIn(0, 10_000) }
                ?: 1000,
            categories = (root["categories"] as? List<*>).orEmpty().mapNotNull(::parseCategory),
            transactions = (root["transactions"] as? List<*>).orEmpty().mapNotNull(::parseTransaction),
            merchantRules = (root["merchantRules"] as? List<*>).orEmpty().mapNotNull(::parseRule),
        )
    }

    private fun parseCategory(any: Any?): ParsedBackupCategory? {
        val map = any as? Map<*, *> ?: return null
        val id = asLong(map["id"]) ?: return null
        val name = (map["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return null
        return ParsedBackupCategory(
            id = id,
            name = name,
            emoji = (map["emoji"] as? String)?.takeIf { it.isNotBlank() } ?: "💸",
            colorArgb = asLong(map["colorArgb"]) ?: 0xFFF06B6BL,
            quotaCents = asLong(map["quotaCents"]),
            periodType = parseEnum(map["periodType"], PeriodType.values()),
            feeBp = asLong(map["feeBp"])?.toInt()?.coerceIn(0, 10_000) ?: 1000,
            isSystem = map["isSystem"] == true,
            archived = map["archived"] == true,
            sortOrder = asLong(map["sortOrder"])?.toInt() ?: 0,
            createdAt = asLong(map["createdAt"]) ?: 0L,
        )
    }

    private fun parseTransaction(any: Any?): ParsedBackupTransaction? {
        val map = any as? Map<*, *> ?: return null
        val amountCents = asLong(map["amountCents"]) ?: return null
        val occurredAt = asLong(map["occurredAt"]) ?: return null
        return ParsedBackupTransaction(
            externalId = map["externalId"] as? String,
            dedupKey = map["dedupKey"] as? String,
            source = parseEnum(map["source"], TxSource.values()),
            direction = parseEnum(map["direction"], TxDirection.values()),
            amountCents = amountCents,
            categoryId = asLong(map["categoryId"]),
            merchant = map["merchant"] as? String ?: "",
            product = map["product"] as? String ?: "",
            note = map["note"] as? String ?: "",
            occurredAt = occurredAt,
            rawType = map["rawType"] as? String ?: "",
            payMethod = map["payMethod"] as? String ?: "",
            status = map["status"] as? String ?: "",
            createdAt = asLong(map["createdAt"]) ?: occurredAt,
        )
    }

    private fun parseRule(any: Any?): ParsedBackupRule? {
        val map = any as? Map<*, *> ?: return null
        val pattern = (map["pattern"] as? String)?.trim().orEmpty()
        val categoryId = asLong(map["categoryId"]) ?: return null
        if (pattern.isEmpty()) return null
        return ParsedBackupRule(
            pattern = pattern,
            categoryId = categoryId,
            priority = asLong(map["priority"])?.toInt() ?: 0,
            createdAt = asLong(map["createdAt"]) ?: 0L,
        )
    }

    private fun <T : Enum<T>> parseEnum(value: Any?, values: Array<T>): T {
        val name = value as? String ?: return values.first()
        return values.firstOrNull { it.name == name } ?: values.first()
    }

    private fun asLong(any: Any?): Long? = when (any) {
        is Long -> any
        is Int -> any.toLong()
        is Double -> if (any % 1.0 == 0.0 && !any.isInfinite() && !any.isNaN()) any.toLong() else null
        else -> null
    }
}
