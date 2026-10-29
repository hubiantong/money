package top.tongtong.money.ui.screens.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.ledger.PeriodCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TransactionGroup(
    val date: LocalDate,
    val label: String,
    val expenseCents: Long,
    val incomeCents: Long,
    val refundCents: Long,
    val transactions: List<TransactionEntity>,
)

data class TransactionsUiState(
    val monthKey: String = "",
    val groups: List<TransactionGroup> = emptyList(),
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    val refundCents: Long = 0,
    val direction: TxDirection? = null,
    val categoryId: Long? = null,
    val query: String = "",
    val isLoading: Boolean = true,
)

private data class Filtered(
    val monthKey: String,
    val direction: TxDirection?,
    val categoryId: Long?,
    val query: String,
    val transactions: List<TransactionEntity>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(
    container: AppContainer,
    initialCategoryId: Long? = null,
    initialMonthKey: String? = null,
) : ViewModel() {

    private val db = container.database
    private val zone = ZoneId.systemDefault()
    private val dayFormatter = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)

    private val month = MutableStateFlow(
        initialMonthKey?.takeIf { it.isNotBlank() } ?: PeriodCalculator.currentMonthKey(),
    )
    private val direction = MutableStateFlow<TxDirection?>(null)
    private val category = MutableStateFlow(initialCategoryId)
    private val query = MutableStateFlow("")

    // 只有 month 影响 SQL 查询范围，方向/分类/关键词仅做内存过滤，避免切换筛选时重建查询
    private val transactions = month.flatMapLatest { monthKey ->
        db.transactionDao().observeRange(
            PeriodCalculator.monthStart(monthKey),
            PeriodCalculator.monthEndExclusive(monthKey),
        ).map { monthKey to it }
    }.catch {
        // 数据库读取失败时降级为空列表，避免异常击穿 viewModelScope
        emit(month.value to emptyList())
    }

    private val filtered = combine(
        transactions,
        direction,
        category,
        query,
    ) { data, dir, categoryId, queryText ->
        val keyword = queryText.trim()
        Filtered(
            monthKey = data.first,
            direction = dir,
            categoryId = categoryId,
            query = keyword,
            transactions = data.second.filter { transaction ->
                (dir == null || transaction.direction == dir) &&
                    (categoryId == null || transaction.categoryId == categoryId) &&
                    (keyword.isEmpty() || matchesKeyword(transaction, keyword))
            },
        )
    }

    init {
        // 停留在真实当月时，跨月后自动跟随到新月份
        viewModelScope.launch {
            var lastCurrentMonth = month.value
            while (true) {
                delay(60_000L)
                val nowMonth = PeriodCalculator.currentMonthKey()
                if (nowMonth != lastCurrentMonth) {
                    if (month.value == lastCurrentMonth) month.value = nowMonth
                    lastCurrentMonth = nowMonth
                }
            }
        }
    }

    val state: StateFlow<TransactionsUiState> = combine(
        filtered,
        db.categoryDao().observeAll().catch { emit(emptyList()) },
    ) { data, categories ->
        val categoriesById = categories.associateBy { it.id }
        val groups = data.transactions
            .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
            .toList()
            .sortedByDescending { it.first }
            .map { (date, transactions) ->
                TransactionGroup(
                    date = date,
                    label = date.format(dayFormatter),
                    expenseCents = transactions
                        .filter { it.direction == TxDirection.EXPENSE }
                        .sumOf { it.amountCents },
                    incomeCents = transactions
                        .filter { it.direction == TxDirection.INCOME }
                        .sumOf { it.amountCents },
                    refundCents = transactions
                        .filter { it.direction == TxDirection.REFUND }
                        .sumOf { it.amountCents },
                    transactions = transactions.sortedByDescending { it.occurredAt },
                )
            }
        TransactionsUiState(
            monthKey = data.monthKey,
            groups = groups,
            categories = categoriesById,
            // 汇总直接复用分组结果，避免再遍历一次列表
            expenseCents = groups.sumOf { it.expenseCents },
            incomeCents = groups.sumOf { it.incomeCents },
            refundCents = groups.sumOf { it.refundCents },
            direction = data.direction,
            categoryId = data.categoryId,
            query = data.query,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionsUiState(monthKey = month.value),
    )

    fun shiftMonth(delta: Long) {
        month.value = PeriodCalculator.shiftMonth(month.value, delta)
    }

    fun setDirection(value: TxDirection?) {
        direction.value = value
    }

    fun setCategory(value: Long?) {
        category.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    private fun matchesKeyword(transaction: TransactionEntity, keyword: String): Boolean =
        transaction.merchant.contains(keyword, ignoreCase = true) ||
            transaction.product.contains(keyword, ignoreCase = true) ||
            transaction.note.contains(keyword, ignoreCase = true)
}
