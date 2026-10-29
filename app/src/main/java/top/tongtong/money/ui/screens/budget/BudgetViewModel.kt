package top.tongtong.money.ui.screens.budget

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
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.PeriodBucketEntity
import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.ledger.PeriodCalculator
import top.tongtong.money.data.repo.CategoryRepository
import java.time.Instant
import java.time.ZoneId

private const val MINUTE_MS = 60_000L

/** 用于感知跨天 / 跨月的时间键 */
private data class TimeKey(val monthKey: String, val dayStart: Long)

data class BudgetCategoryItem(
    val category: CategoryEntity,
    val spentCents: Long,
    val quotaCents: Long?,
    val overLimit: Boolean,
    val surplusCents: Long,
)

data class BudgetUiState(
    val budgetCents: Long? = null,
    val categories: List<BudgetCategoryItem> = emptyList(),
    val totalSurplusCents: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(container: AppContainer) : ViewModel() {

    private val repository: CategoryRepository = container.categoryRepository

    // 每分钟检查一次，跨天 / 跨月后重建查询，避免 ViewModel 存活期间一直使用旧周期的数据
    private val timeKey = flow {
        while (true) {
            emit(currentTimeKey())
            delay(MINUTE_MS)
        }
    }.distinctUntilChanged()

    val state: StateFlow<BudgetUiState> = timeKey.flatMapLatest { key ->
        combine(
            container.settingsRepository.flow,
            repository.categories,
            container.database.bucketDao().observeActive(key.dayStart),
            container.database.grantDao().observeActive(key.monthKey),
        ) { settings, categories, buckets, grants ->
            val surplusByCategory = grants.groupBy { it.categoryId }
            val bucketByKey = buckets.associateBy { it.categoryId to it.periodStart }
            val periodStartByType = PeriodType.values().associateWith {
                PeriodCalculator.periodOf(key.dayStart, it).start
            }
            val items = categories.sortedBy { it.sortOrder }.map { category ->
                val bucket: PeriodBucketEntity? = if (category.quotaCents != null) {
                    bucketByKey[category.id to periodStartByType.getValue(category.periodType)]
                } else {
                    null
                }
                BudgetCategoryItem(
                    category = category,
                    spentCents = bucket?.spentCents ?: 0,
                    quotaCents = category.quotaCents,
                    overLimit = bucket?.overLimit == true,
                    surplusCents = surplusByCategory[category.id]?.sumOf { it.remainingCents } ?: 0,
                )
            }
            BudgetUiState(
                budgetCents = settings.monthlyBudgetCents,
                categories = items,
                totalSurplusCents = grants.sumOf { it.remainingCents },
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BudgetUiState(),
    )

    private fun currentTimeKey(): TimeKey {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val dayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        return TimeKey(PeriodCalculator.currentMonthKey(now), dayStart)
    }

    fun setBudget(cents: Long?) {
        viewModelScope.launch { repository.setMonthlyBudget(cents) }
    }

    fun archive(category: CategoryEntity) {
        viewModelScope.launch { repository.archive(category) }
    }

    fun restore(category: CategoryEntity) {
        viewModelScope.launch { repository.restore(category) }
    }

    fun moveCategory(categoryId: Long, delta: Int) {
        viewModelScope.launch { repository.moveCategory(categoryId, delta) }
    }
}
