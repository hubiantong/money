package top.tongtong.money.data.repo

import top.tongtong.money.data.local.AppDatabase
import top.tongtong.money.data.local.LedgerStateEntity
import top.tongtong.money.data.local.SurplusGrantEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.data.local.TxSource
import top.tongtong.money.domain.ledger.ExpenseAllocation
import top.tongtong.money.domain.ledger.LedgerEngine
import top.tongtong.money.domain.ledger.PeriodCalculator
import top.tongtong.money.domain.ledger.SurplusSourcePrefs
import top.tongtong.money.domain.ledger.SurplusSourceRef
import java.time.YearMonth

/** 记账界面的可抵扣来源选项。 */
data class SurplusSourceOption(
    val categoryId: Long,
    val sourcePeriodStart: Long,
    val sourcePeriodEnd: Long,
    val remainingCents: Long,
    val categoryName: String,
    val categoryEmoji: String,
    val sameCategory: Boolean,
)

private const val PREVIEW_ID = -1L

class LedgerRepository(private val db: AppDatabase) {

    suspend fun addTransaction(transaction: TransactionEntity): Long {
        val id = db.transactionDao().insert(transaction.copy(id = 0))
        rebuildFrom(PeriodCalculator.monthKeyOf(transaction.occurredAt))
        return id
    }

    suspend fun updateTransaction(transaction: TransactionEntity) {
        val old = db.transactionDao().getById(transaction.id) ?: return
        db.transactionDao().update(transaction.copy(updatedAt = System.currentTimeMillis()))
        val monthA = PeriodCalculator.monthKeyOf(old.occurredAt)
        val monthB = PeriodCalculator.monthKeyOf(transaction.occurredAt)
        rebuildFrom(minOf(monthA, monthB))
    }

    suspend fun deleteTransaction(transaction: TransactionEntity) {
        // 以库内实际行为准确定重建起点，避免调用方持有过期对象导致漏算月份
        val existing = db.transactionDao().getById(transaction.id) ?: return
        db.transactionDao().deleteById(existing.id)
        rebuildFrom(PeriodCalculator.monthKeyOf(existing.occurredAt))
    }

    suspend fun previewExpense(
        categoryId: Long?,
        amountCents: Long,
        occurredAt: Long,
        merchant: String = "",
        selectedSources: List<SurplusSourceRef> = emptyList(),
        excludeTransactionId: Long? = null,
    ): ExpenseAllocation {
        val monthKey = PeriodCalculator.monthKeyOf(occurredAt)
        val start = PeriodCalculator.monthStart(monthKey)
        val end = PeriodCalculator.monthEndExclusive(monthKey)
        val categories = db.categoryDao().getAll()
        // 编辑中的记录已在库里，试算时必须排除，否则会把自己的旧抵扣重复算一遍
        val transactions = db.transactionDao().getRangeAsc(start, end)
            .filter { it.id != excludeTransactionId }
        val fake = TransactionEntity(
            id = PREVIEW_ID,
            source = TxSource.MANUAL,
            direction = TxDirection.EXPENSE,
            amountCents = amountCents,
            categoryId = categoryId,
            merchant = merchant,
            occurredAt = occurredAt,
            surplusSourcePrefs = SurplusSourcePrefs.encode(selectedSources),
        )
        val engine = LedgerEngine(
            categories = categories,
            startMonth = YearMonth.parse(monthKey),
            // 按交易发生时刻回放，避免把之后发生的支出算进这笔的试算
            now = occurredAt,
        )
        return engine.build(transactions + fake).allocations[fake.id] ?: ExpenseAllocation()
    }

    /** 当前这笔支出在本月可用的结余来源（按手选顺序展示）。 */
    suspend fun availableSurplusSources(
        categoryId: Long?,
        occurredAt: Long,
        excludeTransactionId: Long? = null,
    ): List<SurplusSourceOption> {
        val monthKey = PeriodCalculator.monthKeyOf(occurredAt)
        val start = PeriodCalculator.monthStart(monthKey)
        val end = PeriodCalculator.monthEndExclusive(monthKey)
        val categories = db.categoryDao().getAll()
        val transactions = db.transactionDao().getRangeAsc(start, end)
            .filter { it.id != excludeTransactionId }
        val result = LedgerEngine(
            categories = categories,
            startMonth = YearMonth.parse(monthKey),
            // 只回放到该笔交易发生时刻，展示当时真正可用的结余来源
            now = occurredAt,
        ).build(transactions)
        val categoryById = categories.associateBy { it.id }
        return result.grants
            .asSequence()
            .filter { it.monthKey == monthKey && it.remainingCents > 0 && it.expiresAt > occurredAt }
            .sortedWith(
                compareByDescending<SurplusGrantEntity> {
                    it.categoryId == categoryId
                }.thenBy { it.expiresAt }.thenBy { it.sourcePeriodStart },
            )
            .map { grant ->
                val category = categoryById[grant.categoryId]
                SurplusSourceOption(
                    categoryId = grant.categoryId,
                    sourcePeriodStart = grant.sourcePeriodStart,
                    sourcePeriodEnd = grant.sourcePeriodEnd,
                    remainingCents = grant.remainingCents,
                    categoryName = category?.name ?: "分类",
                    categoryEmoji = category?.emoji ?: "💸",
                    sameCategory = categoryId != null && grant.categoryId == categoryId,
                )
            }
            .toList()
    }

    /** 应用启动或定时任务调用：从上次重建所在月份补算到当前。 */
    suspend fun catchUp(now: Long = System.currentTimeMillis()) {
        val state = db.ledgerStateDao().get()
        val startMonth = if (state == null || state.lastRebuildAt <= 0L) {
            earliestMonth(now)
        } else {
            PeriodCalculator.monthKeyOf(state.lastRebuildAt)
        }
        val current = PeriodCalculator.monthKeyOf(now)
        rebuildFrom(if (YearMonth.parse(startMonth).isAfter(YearMonth.parse(current))) current else startMonth, now)
    }

    /** 导入历史账单后，从受影响的最早月份重放。 */
    suspend fun rebuildFrom(monthKey: String, now: Long = System.currentTimeMillis()) {
        val start = PeriodCalculator.monthStart(monthKey)
        // 重放计算放在事务外，避免导入历史账单时长时间持有写锁；事务内只做短写。
        val categories = db.categoryDao().getAll()
        val transactions = db.transactionDao().getAllFrom(start)
        val result = LedgerEngine(
            categories = categories,
            startMonth = YearMonth.parse(monthKey),
            now = now,
        ).build(transactions)

        db.inTransaction {
            db.bucketDao().deleteFromMonth(monthKey)
            db.grantDao().deleteFromMonth(monthKey)
            db.usageDao().deleteFromMonth(monthKey)
            db.savingsDao().deleteFromMonth(monthKey)
            db.transactionDao().clearAllocationsFrom(start)

            if (result.buckets.isNotEmpty()) db.bucketDao().insertAll(result.buckets)
            if (result.grants.isNotEmpty()) db.grantDao().insertAll(result.grants)
            if (result.usages.isNotEmpty()) db.usageDao().insertAll(result.usages)
            if (result.savingsEntries.isNotEmpty()) db.savingsDao().insertAll(result.savingsEntries)
            for ((id, allocation) in result.allocations) {
                if (id <= 0) continue
                db.transactionDao().updateAllocation(
                    id = id,
                    overLimit = allocation.overLimit,
                    quotaCovered = allocation.quotaCoveredCents,
                    sameSurplus = allocation.sameSurplusCents,
                    crossSurplus = allocation.crossSurplusCents,
                    fee = allocation.feeCents,
                    uncovered = allocation.uncoveredCents,
                )
            }
            db.ledgerStateDao().put(
                LedgerStateEntity(
                    id = 1,
                    schemaVersion = 1,
                    lastRebuildAt = now,
                ),
            )
        }
    }

    suspend fun rebuildAll(now: Long = System.currentTimeMillis()) {
        rebuildFrom(earliestMonth(now), now)
    }

    private suspend fun earliestMonth(now: Long): String {
        val earliestTransaction = db.transactionDao().earliestOccurredAt()
        val categories = db.categoryDao().getAll()
        val earliestCategory = categories.minOfOrNull { it.createdAt }
        val candidates = listOfNotNull(earliestTransaction, earliestCategory)
        val current = PeriodCalculator.monthKeyOf(now)
        val earliest = candidates.minOrNull() ?: return current
        val key = PeriodCalculator.monthKeyOf(earliest)
        return if (YearMonth.parse(key).isAfter(YearMonth.parse(current))) current else key
    }
}
