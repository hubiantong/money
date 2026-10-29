package top.tongtong.money.ui.screens.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.SurplusUsageEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.repo.CategoryRepository
import top.tongtong.money.data.repo.LedgerRepository
import top.tongtong.money.di.AppContainer

data class TransactionDetailUiState(
    val transaction: TransactionEntity? = null,
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val usages: List<SurplusUsageEntity> = emptyList(),
    val rememberMerchant: Boolean = true,
    val deleted: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModel(
    container: AppContainer,
    private val transactionId: Long,
) : ViewModel() {

    private val db = container.database
    private val ledger: LedgerRepository = container.ledgerRepository
    private val categoryRepository: CategoryRepository = container.categoryRepository

    private val flags = MutableStateFlow(Flags())

    private data class Flags(
        val rememberMerchant: Boolean = true,
        val deleted: Boolean = false,
        val error: String? = null,
    )

    // 复用同一个 Room 观察流，避免 combine 里重复订阅
    private val transactionFlow = db.transactionDao().observeById(transactionId)

    private val usages = transactionFlow.flatMapLatest { transaction ->
        flow {
            emit(
                if (transaction == null) {
                    emptyList()
                } else {
                    db.usageDao().forTransaction(transactionId)
                },
            )
        }
    }

    val state: StateFlow<TransactionDetailUiState> = combine(
        transactionFlow,
        db.categoryDao().observeAll(),
        usages,
        flags,
    ) { transaction, categories, usages, flags ->
        TransactionDetailUiState(
            transaction = transaction,
            categories = categories.associateBy { it.id },
            usages = usages,
            rememberMerchant = flags.rememberMerchant,
            deleted = flags.deleted,
            error = flags.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionDetailUiState(),
    )

    fun setRememberMerchant(value: Boolean) {
        flags.update { it.copy(rememberMerchant = value) }
    }

    fun clearError() {
        flags.update { it.copy(error = null) }
    }

    fun changeCategory(categoryId: Long) {
        val transaction = state.value.transaction ?: return
        viewModelScope.launch {
            runCatching { ledger.updateTransaction(transaction.copy(categoryId = categoryId)) }
                .onSuccess {
                    if (flags.value.rememberMerchant && transaction.merchant.isNotBlank()) {
                        // 记忆商家分类失败不影响已完成的分类修改
                        runCatching {
                            categoryRepository.rememberMerchant(transaction.merchant, categoryId)
                        }
                    }
                    flags.update { it.copy(error = null) }
                }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    flags.update { it.copy(error = exception.message ?: "修改分类失败") }
                }
        }
    }

    fun delete() {
        val transaction = state.value.transaction ?: return
        viewModelScope.launch {
            runCatching { ledger.deleteTransaction(transaction) }
                .onSuccess { flags.update { it.copy(deleted = true, error = null) } }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    flags.update { it.copy(error = exception.message ?: "删除失败") }
                }
        }
    }
}
