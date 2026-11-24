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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.PeriodBucketEntity
import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.data.local.SurplusUsageEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.ledger.PeriodCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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
    val error: String? = null,
)

/** 当日的单笔交易及其配额、结余来源信息。 */
data class DayItemUi(
    val transaction: TransactionEntity,
    val category: CategoryEntity?,
    /** 仅支出且分类配置了配额时非空。 */
    val quota: QuotaComparisonUi?,
    val sources: List<SourceUsageUi>,
)

/** 交易所属分类周期的配额与累计支出对比。 */
data class QuotaComparisonUi(
    val periodLabel: String,
    val periodText: String,
    val quotaCents: Long,
    val spentCents: Long,
    val overLimit: Boolean,
)

/** 一笔超额支出消耗的结余来源。 */
data class SourceUsageUi(
    val sourceCategoryId: Long,
    val sourceCategoryName: String,
    val sourceCategoryEmoji: String,
    val sourcePeriodLabel: String,
    val amountCents: Long,
    val feeCents: Long,
    val crossCategory: Boolean,
)

data class CalendarDayUi(
    val date: LocalDate,
    val dayOfMonth: Int,
    val expenseCents: Long,
    val incomeCents: Long,
    val refundCents: Long,
    val items: List<DayItemUi>,
    val overLimitItems: List<DayItemUi>,
)

data class CalendarUiState(
    val monthKey: String = "",
    val leadingBlanks: Int = 0,
    val days: List<CalendarDayUi> = emptyList(),
    val overLimitDays: List<CalendarDayUi> = emptyList(),
    val selectedDate: LocalDate? = null,
    val selectedDay: CalendarDayUi? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** 列表与日历共用的当月数据快照，避免重复订阅同一批 Room 查询。 */
private data class MonthSnapshot(
    val monthKey: String,
    val transactions: List<TransactionEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val buckets: List<PeriodBucketEntity> = emptyList(),
    val usages: List<SurplusUsageEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
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
    private val selectedDate = MutableStateFlow<LocalDate?>(null)
    private val reloadToken = MutableStateFlow(0)

    /** 最近一次成功加载的快照；同月重新订阅时先复用，避免闪一下加载态。 */
    private var cachedSnapshot: MonthSnapshot? = null

    // 重试时即使月份不变也会重新订阅数据库 Flow
    private val monthKeyFlow = combine(month, reloadToken) { monthKey, _ -> monthKey }

    private val snapshot: StateFlow<MonthSnapshot> = monthKeyFlow.flatMapLatest { monthKey ->
        val start = PeriodCalculator.monthStart(monthKey)
        val end = PeriodCalculator.monthEndExclusive(monthKey)
        combine(
            db.transactionDao().observeRange(start, end)
                .map { MonthSnapshot(monthKey, it, isLoading = false) }
                .catch {
                    // 数据库读取失败时降级为错误态，避免异常击穿 viewModelScope
                    emit(
                        MonthSnapshot(
                            monthKey = monthKey,
                            isLoading = false,
                            error = "读取交易记录失败，请稍后重试",
                        ),
                    )
                },
            db.categoryDao().observeAll().catch { emit(emptyList()) },
            db.bucketDao().observeMonth(monthKey).catch { emit(emptyList()) },
            db.usageDao().observeMonth(monthKey).catch { emit(emptyList()) },
        ) { transactions, categories, buckets, usages ->
            transactions.copy(
                categories = categories,
                // 防御月份切换瞬间的旧月数据混入
                buckets = buckets.filter { it.monthKey == monthKey },
                usages = usages.filter { it.monthKey == monthKey },
            ).also { snapshot ->
                if (snapshot.error == null) {
                    cachedSnapshot = snapshot
                }
            }
        }.onStart {
            // 同月重新订阅（如从详情页返回）或切回刚看过的月份时先复用缓存，
            // 只有首次加载或没有缓存时才发加载态。
            val cached = cachedSnapshot?.takeIf { it.monthKey == monthKey }
            emit(cached ?: MonthSnapshot(monthKey = monthKey))
        }
    }.catch {
        emit(
            MonthSnapshot(
                monthKey = month.value,
                isLoading = false,
                error = "读取账本失败，请稍后重试",
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MonthSnapshot(monthKey = month.value),
    )

    init {
        // 停留在真实当月时，跨月后自动跟随到新月份
        viewModelScope.launch {
            var lastCurrentMonth = month.value
            while (true) {
                delay(60_000L)
                val nowMonth = PeriodCalculator.currentMonthKey()
                if (nowMonth != lastCurrentMonth) {
                    if (month.value == lastCurrentMonth) {
                        month.value = nowMonth
                        selectedDate.value = null
                    }
                    lastCurrentMonth = nowMonth
                }
            }
        }
    }

    val state: StateFlow<TransactionsUiState> = combine(
        snapshot,
        direction,
        category,
        query,
    ) { data, dir, categoryId, queryText ->
        buildListState(data, dir, categoryId, queryText)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionsUiState(monthKey = month.value),
    )

    val calendarState: StateFlow<CalendarUiState> = combine(
        snapshot,
        selectedDate,
    ) { data, date ->
        buildCalendarState(data, date)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CalendarUiState(monthKey = month.value),
    )

    fun shiftMonth(delta: Long) {
        month.value = PeriodCalculator.shiftMonth(month.value, delta)
        selectedDate.value = null
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

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun clearSelectedDate() {
        selectedDate.value = null
    }

    fun retry() {
        reloadToken.value++
    }

    private fun buildListState(
        data: MonthSnapshot,
        dir: TxDirection?,
        categoryId: Long?,
        queryText: String,
    ): TransactionsUiState {
        val keyword = queryText.trim()
        val filtered = data.transactions.filter { transaction ->
            (dir == null || transaction.direction == dir) &&
                (categoryId == null || transaction.categoryId == categoryId) &&
                (keyword.isEmpty() || matchesKeyword(transaction, keyword))
        }
        val groups = filtered
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
        return TransactionsUiState(
            monthKey = data.monthKey,
            groups = groups,
            categories = data.categories.associateBy { it.id },
            // 汇总直接复用分组结果，避免再遍历一次列表
            expenseCents = groups.sumOf { it.expenseCents },
            incomeCents = groups.sumOf { it.incomeCents },
            refundCents = groups.sumOf { it.refundCents },
            direction = dir,
            categoryId = categoryId,
            query = keyword,
            isLoading = data.isLoading,
            error = data.error,
        )
    }

    private fun buildCalendarState(data: MonthSnapshot, selected: LocalDate?): CalendarUiState {
        if (data.isLoading) {
            return CalendarUiState(monthKey = data.monthKey, isLoading = true)
        }
        if (data.error != null) {
            return CalendarUiState(
                monthKey = data.monthKey,
                isLoading = false,
                error = data.error,
            )
        }
        val month = runCatching { YearMonth.parse(data.monthKey) }.getOrNull()
            ?: return CalendarUiState(
                monthKey = data.monthKey,
                isLoading = false,
                error = "月份数据异常，请切换月份后重试",
            )
        val categoryById = data.categories.associateBy { it.id }
        val bucketByKey = data.buckets.associateBy { it.categoryId to it.periodStart }
        val usagesByTransaction = data.usages.groupBy { it.transactionId }
        val transactionsByDate = data.transactions.groupBy {
            Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate()
        }
        val days = (1..month.lengthOfMonth()).map { dayOfMonth ->
            val date = month.atDay(dayOfMonth)
            val transactions = transactionsByDate[date].orEmpty().sortedByDescending { it.occurredAt }
            val items = transactions.map { transaction ->
                buildDayItem(transaction, categoryById, bucketByKey, usagesByTransaction)
            }
            CalendarDayUi(
                date = date,
                dayOfMonth = dayOfMonth,
                expenseCents = transactions
                    .filter { it.direction == TxDirection.EXPENSE }
                    .sumOf { it.amountCents },
                incomeCents = transactions
                    .filter { it.direction == TxDirection.INCOME }
                    .sumOf { it.amountCents },
                refundCents = transactions
                    .filter { it.direction == TxDirection.REFUND }
                    .sumOf { it.amountCents },
                items = items,
                // 只有支出会参与配额超额；退款/收入即使有脏数据也不标记
                overLimitItems = items.filter {
                    it.transaction.overLimit && it.transaction.direction == TxDirection.EXPENSE
                },
            )
        }
        val selectedDay = selected?.let { date -> days.firstOrNull { it.date == date } }
        return CalendarUiState(
            monthKey = data.monthKey,
            leadingBlanks = month.atDay(1).dayOfWeek.value - 1,
            days = days,
            overLimitDays = days.filter { it.overLimitItems.isNotEmpty() },
            selectedDate = selectedDay?.date,
            selectedDay = selectedDay,
            isLoading = false,
            error = null,
        )
    }

    private fun buildDayItem(
        transaction: TransactionEntity,
        categoryById: Map<Long, CategoryEntity>,
        bucketByKey: Map<Pair<Long, Long>, PeriodBucketEntity>,
        usagesByTransaction: Map<Long, List<SurplusUsageEntity>>,
    ): DayItemUi {
        val category = transaction.categoryId?.let { categoryById[it] }
        val quota = if (
            transaction.direction == TxDirection.EXPENSE &&
            category != null &&
            category.quotaCents != null
        ) {
            val period = PeriodCalculator.periodOf(transaction.occurredAt, category.periodType, zone)
            bucketByKey[category.id to period.start]?.let { bucket ->
                QuotaComparisonUi(
                    periodLabel = PeriodCalculator.periodLabel(category.periodType),
                    periodText = periodText(period, category.periodType),
                    quotaCents = bucket.quotaCents,
                    spentCents = bucket.spentCents,
                    overLimit = bucket.overLimit || bucket.spentCents > bucket.quotaCents,
                )
            }
        } else {
            null
        }
        val sources = usagesByTransaction[transaction.id].orEmpty().map { usage ->
            val sourceCategory = categoryById[usage.grantCategoryId]
            SourceUsageUi(
                sourceCategoryId = usage.grantCategoryId,
                sourceCategoryName = sourceCategory?.name ?: "已删除分类",
                sourceCategoryEmoji = sourceCategory?.emoji ?: "💸",
                sourcePeriodLabel = sourcePeriodLabel(usage, sourceCategory),
                amountCents = usage.amountCents,
                feeCents = usage.feeCents,
                crossCategory = usage.crossCategory,
            )
        }
        return DayItemUi(
            transaction = transaction,
            category = category,
            quota = quota,
            sources = sources,
        )
    }

    private fun sourcePeriodLabel(usage: SurplusUsageEntity, sourceCategory: CategoryEntity?): String {
        if (sourceCategory == null) {
            return runCatching { "${YearMonth.parse(usage.monthKey).monthValue}月" }
                .getOrDefault(usage.monthKey)
        }
        val period = PeriodCalculator.periodOf(
            usage.grantSourcePeriodStart,
            sourceCategory.periodType,
            zone,
        )
        return periodText(period, sourceCategory.periodType)
    }

    private fun periodText(period: PeriodCalculator.Period, type: PeriodType): String {
        val start = Instant.ofEpochMilli(period.start).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(period.end - 1).atZone(zone).toLocalDate()
        return when (type) {
            PeriodType.DAILY -> "${start.monthValue}月${start.dayOfMonth}日"
            PeriodType.WEEKLY ->
                "${start.monthValue}月${start.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日"
            PeriodType.MONTHLY -> "${start.monthValue}月"
        }
    }

    private fun matchesKeyword(transaction: TransactionEntity, keyword: String): Boolean =
        transaction.merchant.contains(keyword, ignoreCase = true) ||
            transaction.product.contains(keyword, ignoreCase = true) ||
            transaction.note.contains(keyword, ignoreCase = true)
}
