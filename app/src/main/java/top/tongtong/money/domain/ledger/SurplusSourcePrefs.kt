package top.tongtong.money.domain.ledger

/** 一条可抵扣的超额资金来源：分类 + 来源周期起点。 */
data class SurplusSourceRef(
    val categoryId: Long,
    val sourcePeriodStart: Long,
)

/**
 * 交易上手选资金来源的编码：`categoryId:sourcePeriodStart` 以逗号连接。
 * 空串表示未手动指定，由引擎按默认顺序自动抵扣；解析时忽略非法项并保持顺序去重。
 */
object SurplusSourcePrefs {

    fun encode(refs: List<SurplusSourceRef>): String =
        refs.distinct().joinToString(",") { "${it.categoryId}:${it.sourcePeriodStart}" }

    fun decode(value: String?): List<SurplusSourceRef> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',')
            .mapNotNull { item ->
                val parts = item.split(':')
                if (parts.size != 2) return@mapNotNull null
                val categoryId = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                val periodStart = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
                SurplusSourceRef(categoryId, periodStart)
            }
            .distinct()
    }
}
