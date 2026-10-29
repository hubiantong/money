package top.tongtong.money.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.PeriodBucketEntity
import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.data.local.SurplusGrantEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.data.prefs.UserSettings
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.ledger.PeriodCalculator
import java.time.Instant
import java.time.ZoneId

private const val MINUTE_MS = 60_000L

/** 用于感知跨天 / 跨月的时间键 */
private data class TimeKey(val monthKey: String, val dayStart: Long)

data class QuotaItem(
    val category: CategoryEntity,
    val spentCents: Long,
    val quotaCents: Long,
    val overLimit: Boolean,
    val surplusCents: Long,
    val periodLabel: String,
    val periodText: String,
)

data class HomeUiState(
    val monthKey: String = "",
    val budgetCents: Long? = null,
    val spentThisMonthCents: Long = 0,
    val incomeThisMonthCents: Long = 0,
    val remainingBudgetCents: Long? = null,
    val overBudget: Boolean = false,
    val savingsBalanceCents: Long = 0,
    val monthSavingsCents: Long = 0,
    val totalSurplusCents: Long = 0,
    val quotaItems: List<QuotaItem> = emptyList(),
    val recent: List<TransactionEntity> = emptyList(),
    val categories: Map<Long, CategoryEntity> = emptyMap(),
)

private class CoreData(
    val monthKey: String,
    val periods: Map<PeriodType, PeriodCalculator.Period>,
    val settings: UserSettings,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val buckets: List<PeriodBucketEntity>,
    val grants: List<SurplusGrantEntity>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(container: AppContainer) : ViewModel() {

    private val zone = ZoneId.systemDefault()

    // 每分钟检查一次，跨天 / 跨月后重建查询，避免 ViewModel 存活期间一直显示旧周期的数据
    private val timeKey = flow {
        while (true) {
            emit(currentTimeKey())
            delay(MINUTE_MS)
        }
    }.distinctUntilChanged()

    val state: StateFlow<HomeUiState> = timeKey.flatMapLatest { key ->
        val core = combine(
            container.settingsRepository.flow,
            container.categoryRepository.categories,
            container.database.transactionDao().observeRange(
                PeriodCalculator.monthStart(key.monthKey),
                PeriodCalculator.monthEndExclusive(key.monthKey),
            ),
            container.database.bucketDao().observeActive(key.dayStart),
            container.database.grantDao().observeActive(key.monthKey),
        ) { settings, categories, transactions, buckets, grants ->
            CoreData(
                monthKey = key.monthKey,
                periods = PeriodType.values().associateWith {
                    PeriodCalculator.periodOf(key.dayStart, it, zone)
                },
                settings = settings,
                categories = categories,
                transactions = transactions,
                buckets = buckets,
                grants = grants,
            )
        }
        val savings = combine(
            container.database.savingsDao().observeBalance(),
            container.database.savingsDao().observeMonthTotal(key.monthKey),
        ) { balance, monthTotal -> balance to monthTotal }
        combine(
            core,
            savings,
            container.database.transactionDao().observeRecent(8),
        ) { data, savingsPair, recent ->
            buildState(data, savingsPair.first, savingsPair.second, recent)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(monthKey = PeriodCalculator.currentMonthKey()),
    )

    private fun currentTimeKey(): TimeKey {
        val now = System.currentTimeMillis()
        val dayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        return TimeKey(PeriodCalculator.currentMonthKey(now), dayStart)
    }

    private fun buildState(
        data: CoreData,
        savingsBalance: Long,
        monthSavings: Long,
        recent: List<TransactionEntity>,
    ): HomeUiState {
        val netExpense = data.transactions.sumOf { transaction ->
            when (transaction.direction) {
                TxDirection.EXPENSE -> transaction.amountCents
                TxDirection.REFUND -> -transaction.amountCents
                TxDirection.INCOME, TxDirection.NEUTRAL -> 0L
            }
        }
        // 收入小计含退款：都是进账方向，但退款已在净支出里冲减，这里只做展示汇总
        val incomeTotal = data.transactions
            .filter { it.direction == TxDirection.INCOME || it.direction == TxDirection.REFUND }
            .sumOf { it.amountCents }
        val budget = data.settings.monthlyBudgetCents
        val surplusByCategory = data.grants.groupBy { it.categoryId }
        val bucketByKey = data.buckets.associateBy { it.categoryId to it.periodStart }
        val quotaItems = data.categories
            .filter { !it.archived && it.quotaCents != null }
            .sortedBy { it.sortOrder }
            .map { category ->
                val period = data.periods.getValue(category.periodType)
                val bucket = bucketByKey[category.id to period.start]
                val spent = bucket?.spentCents ?: 0L
                val quota = category.quotaCents ?: 0L
                QuotaItem(
                    category = category,
                    spentCents = spent,
                    quotaCents = quota,
                    overLimit = bucket?.overLimit == true || spent > quota,
                    surplusCents = surplusByCategory[category.id]?.sumOf { it.remainingCents } ?: 0L,
                    periodLabel = PeriodCalculator.periodLabel(category.periodType),
                    periodText = periodText(period, category.periodType),
                )
            }

        return HomeUiState(
            monthKey = data.monthKey,
            budgetCents = budget,
            spentThisMonthCents = netExpense,
            incomeThisMonthCents = incomeTotal,
            remainingBudgetCents = budget?.let { it - netExpense },
            overBudget = budget != null && netExpense > budget,
            savingsBalanceCents = savingsBalance,
            monthSavingsCents = monthSavings,
            totalSurplusCents = data.grants.sumOf { it.remainingCents },
            quotaItems = quotaItems,
            recent = recent,
            categories = data.categories.associateBy { it.id },
        )
    }

    private fun periodText(period: PeriodCalculator.Period, type: PeriodType): String {
        val start = Instant.ofEpochMilli(period.start).atZone(zone).toLocalDate()
        return when (type) {
            PeriodType.DAILY -> "${start.monthValue}月${start.dayOfMonth}日"
            PeriodType.WEEKLY -> {
                val end = Instant.ofEpochMilli(period.end - 1).atZone(zone).toLocalDate()
                "${start.monthValue}月${start.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日"
            }
            PeriodType.MONTHLY -> "${start.monthValue}月"
        }
    }
}
