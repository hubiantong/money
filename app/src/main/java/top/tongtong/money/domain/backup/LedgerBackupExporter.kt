package top.tongtong.money.domain.backup

import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.MerchantRuleEntity
import top.tongtong.money.data.local.TransactionEntity

/** 备份文件格式版本；恢复逻辑按 version 分支处理。 */
const val BACKUP_VERSION = 1

/**
 * 生成全量备份 JSON。只含源数据（分类、交易、商家规则、预算/费率设置）；
 * 派生状态（周期桶、结余、储蓄流水、抵扣字段）一律不导出，恢复时由账本引擎重放生成。
 * 不依赖任何序列化框架，字符串手工转义，保持纯 JVM 可测。
 */
object LedgerBackupExporter {

    fun build(
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        merchantRules: List<MerchantRuleEntity>,
        monthlyBudgetCents: Long?,
        defaultFeeBp: Int,
        exportedAt: Long,
    ): String = StringBuilder().apply {
        append("{")
        append("\"version\":").append(BACKUP_VERSION).append(",")
        append("\"app\":\"tongtong-money\",")
        append("\"exportedAt\":").append(exportedAt).append(",")
        append("\"settings\":{\"monthlyBudgetCents\":").append(monthlyBudgetCents ?: "null")
        append(",\"defaultFeeBp\":").append(defaultFeeBp).append("},")
        append("\"categories\":[")
        categories.forEachIndexed { index, category ->
            if (index > 0) append(",")
            append("{")
            append("\"id\":").append(category.id).append(",")
            append("\"name\":").append(json(category.name)).append(",")
            append("\"emoji\":").append(json(category.emoji)).append(",")
            append("\"colorArgb\":").append(category.colorArgb).append(",")
            append("\"quotaCents\":").append(category.quotaCents ?: "null").append(",")
            append("\"periodType\":").append(json(category.periodType.name)).append(",")
            append("\"feeBp\":").append(category.feeBp).append(",")
            append("\"sameFeeBp\":").append(category.sameFeeBp).append(",")
            append("\"isSystem\":").append(category.isSystem).append(",")
            append("\"archived\":").append(category.archived).append(",")
            append("\"sortOrder\":").append(category.sortOrder).append(",")
            append("\"createdAt\":").append(category.createdAt)
            append("}")
        }
        append("],")
        append("\"transactions\":[")
        transactions.forEachIndexed { index, tx ->
            if (index > 0) append(",")
            append("{")
            append("\"externalId\":").append(tx.externalId?.let(::json) ?: "null").append(",")
            append("\"dedupKey\":").append(tx.dedupKey?.let(::json) ?: "null").append(",")
            append("\"source\":").append(json(tx.source.name)).append(",")
            append("\"direction\":").append(json(tx.direction.name)).append(",")
            append("\"amountCents\":").append(tx.amountCents).append(",")
            append("\"categoryId\":").append(tx.categoryId ?: "null").append(",")
            append("\"merchant\":").append(json(tx.merchant)).append(",")
            append("\"product\":").append(json(tx.product)).append(",")
            append("\"note\":").append(json(tx.note)).append(",")
            append("\"occurredAt\":").append(tx.occurredAt).append(",")
            append("\"rawType\":").append(json(tx.rawType)).append(",")
            append("\"payMethod\":").append(json(tx.payMethod)).append(",")
            append("\"status\":").append(json(tx.status)).append(",")
            append("\"surplusSourcePrefs\":").append(json(tx.surplusSourcePrefs)).append(",")
            append("\"createdAt\":").append(tx.createdAt)
            append("}")
        }
        append("],")
        append("\"merchantRules\":[")
        merchantRules.forEachIndexed { index, rule ->
            if (index > 0) append(",")
            append("{")
            append("\"pattern\":").append(json(rule.pattern)).append(",")
            append("\"categoryId\":").append(rule.categoryId).append(",")
            append("\"priority\":").append(rule.priority).append(",")
            append("\"createdAt\":").append(rule.createdAt)
            append("}")
        }
        append("]")
        append("}")
    }.toString()

    private fun json(value: String): String {
        val builder = StringBuilder(value.length + 8)
        builder.append('"')
        for (ch in value) {
            when {
                ch == '"' -> builder.append("\\\"")
                ch == '\\' -> builder.append("\\\\")
                ch == '\n' -> builder.append("\\n")
                ch == '\r' -> builder.append("\\r")
                ch == '\t' -> builder.append("\\t")
                ch < ' ' -> builder.append("\\u").append(String.format("%04x", ch.code))
                else -> builder.append(ch)
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
