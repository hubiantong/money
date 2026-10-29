package top.tongtong.money.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.ledger.PeriodCalculator
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

private const val MONTH_ROLLOVER_TICK_MS = 60_000L

data class CategorySlice(
    val categoryId: Long?,
    val name: String,
    val emoji: String,
    val colorArgb: Long,
    val cents: Long,
)

data class MonthTotal(
    val monthKey: String,
    val label: String,
    val cents: Long,
)

data class StatsUiState(
    val monthKey: String = "",
    val netExpenseCents: Long = 0,
    val refundCents: Long = 0,
    val slices: List<CategorySlice> = emptyList(),
    val dailyCents: List<Long> = emptyList(),
    val months: List<MonthTotal> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(container: AppContainer) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val month = MutableStateFlow(PeriodCalculator.currentMonthKey())
    private var lastCurrentMonth = month.value

    init {
        // 停留在"当前月"时，跨月自动跟随；用户翻到历史月份则保持不变
        viewModelScope.launch {
            while (isActive) {
                delay(MONTH_ROLLOVER_TICK_MS)
                val nowMonth = PeriodCalculator.currentMonthKey()
                if (nowMonth != lastCurrentMonth) {
                    if (month.value == lastCurrentMonth) month.value = nowMonth
                    lastCurrentMonth = nowMonth
                }
            }
        }
    }

    val state: StateFlow<StatsUiState> = month.flatMapLatest { monthKey ->
        // 月份与交易查询一起重建，避免出现"新月份 + 上个月交易"的中间状态
        val sixMonthsAgo = YearMonth.parse(monthKey).minusMonths(5).toString()
        combine(
            container.database.transactionDao().observeRange(
                PeriodCalculator.monthStart(sixMonthsAgo),
                PeriodCalculator.monthEndExclusive(monthKey),
            ),
            container.database.categoryDao().observeAll(),
        ) { transactions, categories ->
            build(monthKey, transactions, categories)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState(monthKey = month.value),
    )

    fun shiftMonth(delta: Long) {
        month.value = PeriodCalculator.shiftMonth(month.value, delta)
    }

    private fun build(
        monthKey: String,
        transactions: List<TransactionEntity>,
        categories: List<CategoryEntity>,
    ): StatsUiState {
        val categoryById = categories.associateBy { it.id }
        val selected = transactions.filter {
            PeriodCalculator.monthKeyOf(it.occurredAt, zone) == monthKey
        }
        val netByCategory = mutableMapOf<Long?, Long>()
        val daily = mutableMapOf<Int, Long>()
        var refund = 0L
        for (transaction in selected) {
            val key = transaction.categoryId
            val day = Instant.ofEpochMilli(transaction.occurredAt).atZone(zone).dayOfMonth
            when (transaction.direction) {
                TxDirection.EXPENSE -> {
                    netByCategory[key] = (netByCategory[key] ?: 0L) + transaction.amountCents
                    daily[day] = (daily[day] ?: 0L) + transaction.amountCents
                }
                TxDirection.REFUND -> {
                    refund += transaction.amountCents
                    netByCategory[key] = (netByCategory[key] ?: 0L) - transaction.amountCents
                    daily[day] = (daily[day] ?: 0L) - transaction.amountCents
                }
                TxDirection.INCOME, TxDirection.NEUTRAL -> Unit
            }
        }

        val slices = netByCategory.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .map { (categoryId, cents) ->
                val category = categoryId?.let { categoryById[it] }
                CategorySlice(
                    categoryId = categoryId,
                    name = category?.name ?: "未分类",
                    emoji = category?.emoji ?: "❓",
                    colorArgb = category?.colorArgb ?: 0xFFB0A8A4,
                    cents = cents,
                )
            }

        val yearMonth = YearMonth.parse(monthKey)
        val daysInMonth = yearMonth.lengthOfMonth()
        val dailyCents = (1..daysInMonth).map { day -> (daily[day] ?: 0L).coerceAtLeast(0) }
        // 一次遍历按月汇总，避免对每条记录重复匹配 6 个月份
        val netByMonth = HashMap<String, Long>()
        for (transaction in transactions) {
            val key = PeriodCalculator.monthKeyOf(transaction.occurredAt, zone)
            val net = when (transaction.direction) {
                TxDirection.EXPENSE -> transaction.amountCents
                TxDirection.REFUND -> -transaction.amountCents
                TxDirection.INCOME, TxDirection.NEUTRAL -> 0L
            }
            netByMonth[key] = (netByMonth[key] ?: 0L) + net
        }
        val months = (5 downTo 0).map { offset ->
            val key = yearMonth.minusMonths(offset.toLong())
            MonthTotal(
                monthKey = key.toString(),
                label = "${key.monthValue}月",
                cents = (netByMonth[key.toString()] ?: 0L).coerceAtLeast(0),
            )
        }

        return StatsUiState(
            monthKey = monthKey,
            netExpenseCents = slices.sumOf { it.cents },
            refundCents = refund,
            slices = slices,
            dailyCents = dailyCents,
            months = months,
        )
    }
}
