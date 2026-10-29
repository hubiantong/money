package top.tongtong.money.ui.screens.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.MerchantRuleEntity
import top.tongtong.money.di.AppContainer

data class MerchantRuleItem(
    val rule: MerchantRuleEntity,
    val category: CategoryEntity?,
)

data class MerchantRulesUiState(
    val rules: List<MerchantRuleItem> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val error: String? = null,
)

class MerchantRulesViewModel(container: AppContainer) : ViewModel() {

    private val repository = container.categoryRepository
    private val errorState = MutableStateFlow<String?>(null)

    val state: StateFlow<MerchantRulesUiState> = combine(
        repository.merchantRules,
        repository.categories,
        errorState,
    ) { rules, categories, error ->
        val byId = categories.associateBy { it.id }
        MerchantRulesUiState(
            rules = rules.map { MerchantRuleItem(it, byId[it.categoryId]) },
            categories = categories.filter { !it.archived },
            error = error,
        )
    }.catch {
        // 上游读取失败时退回空状态，避免整条状态流被永久取消
        emit(MerchantRulesUiState())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MerchantRulesUiState(),
    )

    fun addRule(pattern: String, categoryId: Long) {
        val trimmed = pattern.trim()
        if (trimmed.isBlank()) return
        if (trimmed.length < 2) {
            errorState.value = "商家关键词至少需要 2 个字符"
            return
        }
        viewModelScope.launch {
            runCatching { repository.addRule(trimmed, categoryId) }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    errorState.value = "保存规则失败，请重试"
                }
        }
    }

    fun updateRule(id: Long, pattern: String, categoryId: Long) {
        val trimmed = pattern.trim()
        if (trimmed.isBlank()) return
        if (trimmed.length < 2) {
            errorState.value = "商家关键词至少需要 2 个字符"
            return
        }
        viewModelScope.launch {
            runCatching { repository.updateRule(id, trimmed, categoryId) }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    errorState.value = "保存规则失败，请重试"
                }
        }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteRule(id) }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    errorState.value = "删除规则失败，请重试"
                }
        }
    }

    fun consumeError() {
        errorState.value = null
    }
}
