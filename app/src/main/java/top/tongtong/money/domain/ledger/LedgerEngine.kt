package top.tongtong.money.domain.ledger

import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.PeriodBucketEntity
import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.data.local.SavingsEntryEntity
import top.tongtong.money.data.local.SavingsType
import top.tongtong.money.data.local.SurplusGrantEntity
import top.tongtong.money.data.local.SurplusUsageEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class ExpenseAllocation(
    val overLimit: Boolean = false,
    val quotaCoveredCents: Long = 0,
    val sameSurplusCents: Long = 0,
    val crossSurplusCents: Long = 0,
    val feeCents: Long = 0,
    val uncoveredCents: Long = 0,
)

data class LedgerResult(
    val buckets: List<PeriodBucketEntity>,
    val grants: List<SurplusGrantEntity>,
    val usages: List<SurplusUsageEntity>,
    val savingsEntries: List<SavingsEntryEntity>,
    val allocations: Map<Long, ExpenseAllocation>,
)

/**
 * 账本重放引擎：纯内存计算，不依赖 Android/Room。
 * 周期结束事件与交易按时间顺序交织处理，保证结余、手续费、超支标记可复现。
 */
class LedgerEngine(
    private val categories: List<CategoryEntity>,
    private val startMonth: YearMonth,
    private val now: Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private data class BucketKey(val categoryId: Long, val periodStart: Long)

    private class MutableBucket(
        val categoryId: Long,
        val periodType: PeriodType,
        val periodStart: Long,
        val periodEnd: Long,
        val quotaCents: Long,
        val monthKey: String,
        var spentCents: Long = 0,
        var overLimit: Boolean = false,
        var settled: Boolean = false,
    )

    private class MutableGrant(
        val categoryId: Long,
        val sourcePeriodStart: Long,
        val sourcePeriodEnd: Long,
        var remainingCents: Long,
        val expiresAt: Long,
        val monthKey: String,
        val createdAt: Long,
    )

    private val categoryById = categories.associateBy { it.id }
    private val buckets = LinkedHashMap<BucketKey, MutableBucket>()
    private val grants = mutableListOf<MutableGrant>()
    private val usages = mutableListOf<SurplusUsageEntity>()
    private val savings = mutableListOf<SavingsEntryEntity>()
    private val allocations = HashMap<Long, ExpenseAllocation>()
    private var processingMonthKey: String = ""

    fun build(transactions: List<TransactionEntity>): LedgerResult {
        // 同实例可重复调用：先清空上一轮累积状态，避免重放结果叠加
        buckets.clear()
        grants.clear()
        usages.clear()
        savings.clear()
        allocations.clear()
        processingMonthKey = ""
        val currentMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        val usable = transactions
            .filter { it.occurredAt <= now }
            .sortedWith(compareBy<TransactionEntity> { it.occurredAt }.thenBy { it.id })
        // 按月分组一次，避免在月份循环中对每笔交易重复做时区转换
        val transactionsByMonth = usable.groupBy { PeriodCalculator.monthKeyOf(it.occurredAt, zone) }

        var month = startMonth
        while (!month.isAfter(currentMonth)) {
            val monthKey = month.toString()
            processingMonthKey = monthKey
            beginMonth(monthKey)

            val monthTransactions = transactionsByMonth[monthKey].orEmpty()
            for (tx in monthTransactions) {
                settleUntil(tx.occurredAt)
                applyTransaction(tx)
            }

            if (month.isBefore(currentMonth)) {
                finishMonth(monthKey)
            } else {
                settleUntil(now)
            }
            month = month.plusMonths(1)
        }

        return LedgerResult(
            buckets = buckets.values.map {
                PeriodBucketEntity(
                    categoryId = it.categoryId,
                    periodType = it.periodType,
                    periodStart = it.periodStart,
                    periodEnd = it.periodEnd,
                    quotaCents = it.quotaCents,
                    spentCents = it.spentCents,
                    overLimit = it.overLimit,
                    settled = it.settled,
                    monthKey = it.monthKey,
                )
            },
            grants = grants.filter { it.remainingCents > 0 }.map {
                SurplusGrantEntity(
                    categoryId = it.categoryId,
                    sourcePeriodStart = it.sourcePeriodStart,
                    sourcePeriodEnd = it.sourcePeriodEnd,
                    amountCents = it.remainingCents,
                    remainingCents = it.remainingCents,
                    expiresAt = it.expiresAt,
                    monthKey = it.monthKey,
                    createdAt = it.createdAt,
                )
            },
            usages = usages.toList(),
            savingsEntries = savings.toList(),
            allocations = allocations.toMap(),
        )
    }

    private fun beginMonth(monthKey: String) {
        val activeCategories = categories.filter { !it.archived && it.quotaCents != null }
        for (category in activeCategories) {
            val quota = category.quotaCents ?: continue
            val periods = PeriodCalculator.periodsOfMonth(monthKey, category.periodType, zone)
            for (period in periods) {
                if (period.end <= category.createdAt) continue
                val key = BucketKey(category.id, period.start)
                if (buckets.containsKey(key)) continue
                buckets[key] = MutableBucket(
                    categoryId = category.id,
                    periodType = category.periodType,
                    periodStart = period.start,
                    periodEnd = period.end,
                    quotaCents = quota,
                    monthKey = monthKey,
                )
            }
        }
    }

    private fun settleUntil(time: Long) {
        val pending = buckets.values
            .filter {
                it.monthKey == processingMonthKey &&
                    !it.settled &&
                    it.periodType != PeriodType.MONTHLY &&
                    it.periodEnd <= time
            }
            .sortedBy { it.periodEnd }
        for (bucket in pending) {
            settleBucket(bucket)
        }
    }

    private fun finishMonth(monthKey: String) {
        val monthBuckets = buckets.values
            .filter { it.monthKey == monthKey && !it.settled }
            .sortedBy { it.periodEnd }
        for (bucket in monthBuckets) {
            settleBucket(bucket)
        }
        val monthEnd = PeriodCalculator.monthEndInclusive(monthKey, zone)
        val expired = grants.filter { it.monthKey == monthKey }
        for (grant in expired) {
            if (grant.remainingCents > 0) {
                savings += SavingsEntryEntity(
                    type = SavingsType.SURPLUS_ROLLOVER,
                    amountCents = grant.remainingCents,
                    monthKey = monthKey,
                    note = "月末结余结转：${categoryName(grant.categoryId)}",
                    createdAt = monthEnd,
                )
            }
        }
        grants.removeAll { it.monthKey == monthKey }
    }

    private fun settleBucket(bucket: MutableBucket) {
        if (bucket.settled) return
        val remaining = bucket.quotaCents - bucket.spentCents
        val monthEnd = PeriodCalculator.monthEndInclusive(bucket.monthKey, zone)
        if (remaining > 0) {
            if (bucket.periodType == PeriodType.MONTHLY) {
                savings += SavingsEntryEntity(
                    type = SavingsType.MONTHLY_QUOTA_ROLLOVER,
                    amountCents = remaining,
                    monthKey = bucket.monthKey,
                    note = "分类月度配额结余：${categoryName(bucket.categoryId)}",
                    createdAt = monthEnd,
                )
            } else {
                grants += MutableGrant(
                    categoryId = bucket.categoryId,
                    sourcePeriodStart = bucket.periodStart,
                    sourcePeriodEnd = bucket.periodEnd,
                    remainingCents = remaining,
                    expiresAt = monthEnd,
                    monthKey = bucket.monthKey,
                    createdAt = bucket.periodEnd,
                )
            }
        }
        bucket.settled = true
    }

    private fun applyTransaction(tx: TransactionEntity) {
        when (tx.direction) {
            TxDirection.EXPENSE -> applyExpense(tx)
            TxDirection.REFUND -> applyRefund(tx)
            TxDirection.INCOME, TxDirection.NEUTRAL -> {
                allocations[tx.id] = ExpenseAllocation()
            }
        }
    }

    private fun applyRefund(tx: TransactionEntity) {
        val bucket = bucketFor(tx)
        if (bucket != null) {
            val before = bucket.spentCents
            val after = maxOf(0L, before - tx.amountCents)
            if (bucket.settled) {
                // 已结算周期的结余已经发出，不能再改支出，只能回收对应的结余，避免虚增储蓄
                revokeGrant(bucket, before - after)
            } else {
                bucket.spentCents = after
                bucket.overLimit = bucket.spentCents > bucket.quotaCents
            }
        }
        allocations[tx.id] = ExpenseAllocation()
    }

    /** 退款落在已结算周期时，按同分类、优先同来源周期的顺序回收结余。 */
    private fun revokeGrant(bucket: MutableBucket, amount: Long) {
        var left = amount
        if (left <= 0L) return
        val candidates = grants
            .filter { it.categoryId == bucket.categoryId && it.remainingCents > 0 }
            .sortedWith(
                compareByDescending<MutableGrant> { it.sourcePeriodStart == bucket.periodStart }
                    .thenBy { it.createdAt },
            )
        for (grant in candidates) {
            if (left <= 0L) break
            val take = minOf(left, grant.remainingCents)
            grant.remainingCents -= take
            left -= take
        }
    }

    private fun applyExpense(tx: TransactionEntity) {
        val category = tx.categoryId?.let { categoryById[it] }
        val bucket = bucketFor(tx)
        if (category == null || bucket == null) {
            allocations[tx.id] = ExpenseAllocation()
            return
        }

        val available = bucket.quotaCents - bucket.spentCents
        val covered = minOf(tx.amountCents, maxOf(0L, available))
        var remainder = tx.amountCents - covered
        var same = 0L
        var cross = 0L
        var fee = 0L

        val candidates = grants
            .filter {
                it.monthKey == bucket.monthKey &&
                    it.remainingCents > 0 &&
                    it.expiresAt > tx.occurredAt
            }
            .sortedWith(compareBy<MutableGrant> { it.expiresAt }.thenBy { it.sourcePeriodStart }.thenBy { it.categoryId })

        for (grant in candidates) {
            if (remainder <= 0) break
            if (grant.categoryId != category.id) continue
            val take = minOf(remainder, grant.remainingCents)
            if (take <= 0) continue
            grant.remainingCents -= take
            remainder -= take
            same += take
            usages += SurplusUsageEntity(
                transactionId = tx.id,
                grantCategoryId = grant.categoryId,
                grantSourcePeriodStart = grant.sourcePeriodStart,
                amountCents = take,
                feeCents = 0,
                crossCategory = false,
                monthKey = bucket.monthKey,
                createdAt = tx.occurredAt,
            )
        }

        for (grant in candidates) {
            if (remainder <= 0) break
            if (grant.categoryId == category.id) continue
            val take = minOf(remainder, grant.remainingCents)
            if (take <= 0) continue
            val grantFee = feeFor(take, category.feeBp)
            grant.remainingCents -= take
            remainder -= take
            cross += take
            fee += grantFee
            usages += SurplusUsageEntity(
                transactionId = tx.id,
                grantCategoryId = grant.categoryId,
                grantSourcePeriodStart = grant.sourcePeriodStart,
                amountCents = take,
                feeCents = grantFee,
                crossCategory = true,
                monthKey = bucket.monthKey,
                createdAt = tx.occurredAt,
            )
            if (grantFee > 0) {
                savings += SavingsEntryEntity(
                    type = SavingsType.CROSS_FEE,
                    amountCents = grantFee,
                    monthKey = bucket.monthKey,
                    note = "跨类抵扣手续费：${categoryName(grant.categoryId)} → ${category.name}",
                    createdAt = tx.occurredAt,
                )
            }
        }

        bucket.spentCents += tx.amountCents
        bucket.overLimit = bucket.spentCents > bucket.quotaCents
        allocations[tx.id] = ExpenseAllocation(
            overLimit = bucket.overLimit,
            quotaCoveredCents = covered,
            sameSurplusCents = same,
            crossSurplusCents = cross,
            feeCents = fee,
            uncoveredCents = remainder,
        )
    }

    private fun bucketFor(tx: TransactionEntity): MutableBucket? {
        val categoryId = tx.categoryId ?: return null
        val category = categoryById[categoryId] ?: return null
        val monthKey = PeriodCalculator.monthKeyOf(tx.occurredAt, zone)
        if (monthKey != processingMonthKey) return null
        val period = PeriodCalculator.periodOf(tx.occurredAt, category.periodType, zone)
        return buckets[BucketKey(categoryId, period.start)]
    }

    private fun categoryName(categoryId: Long): String =
        categoryById[categoryId]?.name ?: "分类"

    private fun feeFor(amount: Long, feeBp: Int): Long {
        if (feeBp <= 0 || amount <= 0) return 0
        return (amount * feeBp + 5000) / 10000
    }
}
