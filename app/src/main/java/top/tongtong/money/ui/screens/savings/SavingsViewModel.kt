package top.tongtong.money.ui.screens.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import top.tongtong.money.data.local.SavingsEntryEntity
import top.tongtong.money.di.AppContainer

data class SavingsGroup(
    val monthKey: String,
    val totalCents: Long,
    val entries: List<SavingsEntryEntity>,
)

data class SavingsUiState(
    val balanceCents: Long = 0,
    val groups: List<SavingsGroup> = emptyList(),
)

class SavingsViewModel(container: AppContainer) : ViewModel() {

    // 余额由明细汇总得出，只订阅一个查询，避免两个 Room 流各自刷新导致数字短暂不一致
    val state: StateFlow<SavingsUiState> = container.database.savingsDao().observeAll()
        .map { entries ->
            val groups = entries
                .groupBy { it.monthKey }
                .toList()
                .sortedByDescending { it.first }
                .map { (monthKey, monthEntries) ->
                    SavingsGroup(
                        monthKey = monthKey,
                        totalCents = monthEntries.sumOf { it.amountCents },
                        entries = monthEntries.sortedByDescending { it.createdAt },
                    )
                }
            SavingsUiState(
                balanceCents = entries.sumOf { it.amountCents },
                groups = groups,
            )
        }
        .flowOn(Dispatchers.Default)
        .catch { emit(SavingsUiState()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SavingsUiState(),
        )
}
