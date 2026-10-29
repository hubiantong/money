package top.tongtong.money.ui.screens.transaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.data.local.TxSource
import top.tongtong.money.data.repo.CategoryRepository
import top.tongtong.money.data.repo.LedgerRepository
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.ledger.ExpenseAllocation
import top.tongtong.money.ui.components.parseYuanToCents
import java.math.BigDecimal
import java.math.RoundingMode

data class AddTransactionUiState(
    val amountText: String = "",
    val categoryId: Long? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val direction: TxDirection = TxDirection.EXPENSE,
    val occurredAt: Long = System.currentTimeMillis(),
    val merchant: String = "",
    val product: String = "",
    val payMethod: String = "",
    val note: String = "",
    val rememberMerchant: Boolean = false,
    val preview: ExpenseAllocation? = null,
    val editing: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

class AddTransactionViewModel(
    container: AppContainer,
    private val transactionId: Long,
) : ViewModel() {

    private val db = container.database
    private val ledger = container.ledgerRepository
    private val categoryRepository: CategoryRepository = container.categoryRepository

    private val _state = MutableStateFlow(AddTransactionUiState(editing = transactionId > 0))
    val state: StateFlow<AddTransactionUiState> = _state.asStateFlow()

    private var original: TransactionEntity? = null
    private var previewJob: Job? = null
    // 返回确认的基线：新增时取首次分类加载后的状态，编辑时取原记录，避免"未做任何修改也弹确认"
    private var baseline: AddTransactionUiState? = null

    init {
        viewModelScope.launch {
            categoryRepository.categories.collect { categories ->
                _state.update { current ->
                    // 编辑时保留原分类（可能是已归档分类），避免静默改写历史记录
                    val editingCategoryId = original?.categoryId
                    val visible = categories.filter { !it.archived || it.id == editingCategoryId }
                    val fallback = visible.firstOrNull { !it.isSystem }?.id
                    current.copy(
                        categories = visible,
                        // 已归档/不存在的分类不再保留，避免保存时写入不可见分类
                        categoryId = current.categoryId?.takeIf { id -> visible.any { it.id == id } }
                            ?: fallback,
                    )
                }
                if (transactionId > 0 && original == null) load(transactionId)
                if (transactionId == 0L && baseline == null) baseline = _state.value
                schedulePreview()
            }
        }
    }

    private suspend fun load(id: Long) {
        val transaction = db.transactionDao().getById(id)
        if (transaction == null) {
            // 记录已被删除时不能静默退化成"新增"
            _state.update { it.copy(error = "交易不存在或已被删除") }
            return
        }
        original = transaction
        // 原分类可能已归档、不在可见列表里，补进列表以便界面正确显示
        val editingCategory = transaction.categoryId?.let { categoryId ->
            _state.value.categories.firstOrNull { it.id == categoryId }
                ?: db.categoryDao().getById(categoryId)
        }
        _state.update {
            it.copy(
                amountText = centsToYuan(transaction.amountCents),
                categoryId = transaction.categoryId,
                direction = transaction.direction,
                occurredAt = transaction.occurredAt,
                merchant = transaction.merchant,
                payMethod = transaction.payMethod,
                note = transaction.note,
                editing = true,
                categories = if (editingCategory != null &&
                    it.categories.none { category -> category.id == editingCategory.id }
                ) {
                    it.categories + editingCategory
                } else {
                    it.categories
                },
            )
        }
        baseline = _state.value
    }

    fun setAmount(text: String) {
        _state.update { it.copy(amountText = text, error = null) }
        schedulePreview()
    }

    fun setDirection(direction: TxDirection) {
        _state.update { it.copy(direction = direction) }
        schedulePreview()
    }

    fun setCategory(categoryId: Long) {
        _state.update { it.copy(categoryId = categoryId) }
        schedulePreview()
    }

    fun setOccurredAt(millis: Long) {
        _state.update { it.copy(occurredAt = millis) }
        schedulePreview()
    }

    fun setMerchant(text: String) {
        _state.update { it.copy(merchant = text) }
    }

    fun setPayMethod(text: String) {
        _state.update { it.copy(payMethod = text) }
    }

    fun setNote(text: String) {
        _state.update { it.copy(note = text) }
    }

    fun setRememberMerchant(remember: Boolean) {
        _state.update { it.copy(rememberMerchant = remember) }
    }

    /** 是否有相对基线的未保存修改；基线尚未就绪（记录加载中）视为未修改 */
    fun isDirty(snapshot: AddTransactionUiState): Boolean {
        val baseline = this.baseline ?: return false
        return snapshot.amountText != baseline.amountText ||
            snapshot.direction != baseline.direction ||
            snapshot.categoryId != baseline.categoryId ||
            snapshot.occurredAt != baseline.occurredAt ||
            snapshot.merchant != baseline.merchant ||
            snapshot.payMethod != baseline.payMethod ||
            snapshot.note != baseline.note ||
            snapshot.rememberMerchant != baseline.rememberMerchant
    }

    fun save() {
        val snapshot = _state.value
        // 保存进行中或已完成时直接返回，避免快速双击重复写入
        if (snapshot.saving || snapshot.saved) return
        if (snapshot.editing && original == null) {
            _state.update { it.copy(error = "记录不存在或正在加载，暂时无法保存") }
            return
        }
        val amount = parseYuanToCents(snapshot.amountText)
        if (amount == null) {
            _state.update { it.copy(error = "请输入有效金额") }
            return
        }
        if (snapshot.direction != TxDirection.NEUTRAL && snapshot.categoryId == null) {
            _state.update { it.copy(error = "请选择分类") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val base = original
                val entity = TransactionEntity(
                    id = base?.id ?: 0,
                    externalId = base?.externalId,
                    dedupKey = base?.dedupKey,
                    source = base?.source ?: TxSource.MANUAL,
                    direction = snapshot.direction,
                    amountCents = amount,
                    categoryId = if (snapshot.direction == TxDirection.NEUTRAL) null else snapshot.categoryId,
                    merchant = snapshot.merchant.trim(),
                    product = base?.product ?: "",
                    note = snapshot.note.trim(),
                    occurredAt = snapshot.occurredAt,
                    rawType = base?.rawType ?: "",
                    payMethod = snapshot.payMethod.trim(),
                    status = base?.status ?: "",
                )
                if (base == null) {
                    ledger.addTransaction(entity)
                } else {
                    ledger.updateTransaction(entity)
                }
                val merchant = snapshot.merchant.trim()
                if (snapshot.rememberMerchant && merchant.isNotEmpty()) {
                    snapshot.categoryId?.let { categoryId ->
                        // 记忆商家分类属于附带动作，失败不应让已落库的保存显示为失败
                        runCatching { categoryRepository.rememberMerchant(merchant, categoryId) }
                    }
                }
                _state.update { it.copy(saving = false, saved = true) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update {
                    it.copy(saving = false, error = exception.message ?: "保存失败")
                }
            }
        }
    }

    private fun schedulePreview() {
        previewJob?.cancel()
        val snapshot = _state.value
        val amount = parseYuanToCents(snapshot.amountText)
        if (snapshot.direction != TxDirection.EXPENSE || amount == null || snapshot.categoryId == null) {
            _state.update { it.copy(preview = null) }
            return
        }
        previewJob = viewModelScope.launch {
            delay(220)
            val allocation = runCatching {
                ledger.previewExpense(
                    categoryId = snapshot.categoryId,
                    amountCents = amount,
                    occurredAt = snapshot.occurredAt,
                    merchant = snapshot.merchant,
                )
            }.getOrNull()
            _state.update { it.copy(preview = allocation) }
        }
    }

    private fun centsToYuan(cents: Long): String =
        BigDecimal(cents).divide(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
}
