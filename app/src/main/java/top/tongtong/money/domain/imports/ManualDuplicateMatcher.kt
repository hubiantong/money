package top.tongtong.money.domain.imports

import top.tongtong.money.data.local.TransactionEntity
import kotlin.math.abs

/**
 * 手动记账与微信账单导入的查重规则：方向、金额必须一致；
 * 商家/商品归一化后相似（相等或一方包含另一方）时放宽到 ±24 小时，否则只认 ±2 小时。
 * 纯 JVM，便于单测。
 */
object ManualDuplicateMatcher {

    const val BASE_WINDOW_MILLIS = 2L * 60 * 60 * 1000
    const val SIMILAR_WINDOW_MILLIS = 24L * 60 * 60 * 1000

    fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    fun isSimilar(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= 2 && longer.contains(shorter)
    }

    fun windowMillis(
        importedMerchant: String,
        importedProduct: String,
        manualMerchant: String,
        manualProduct: String,
    ): Long =
        if (isSimilar(importedMerchant, manualMerchant) || isSimilar(importedProduct, manualProduct)) {
            SIMILAR_WINDOW_MILLIS
        } else {
            BASE_WINDOW_MILLIS
        }

    fun isDuplicate(row: ParsedBillRow, manual: TransactionEntity): Boolean {
        if (manual.direction != row.direction || manual.amountCents != row.amountCents) return false
        val window = windowMillis(row.merchant, row.product, manual.merchant, manual.product)
        return abs(manual.occurredAt - row.occurredAt) <= window
    }
}
