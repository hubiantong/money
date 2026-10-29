package top.tongtong.money.ui.screens.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.data.repo.CategoryRepository
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.components.centsToPlainYuan
import top.tongtong.money.ui.components.parseYuanToCents
import java.math.BigDecimal
import java.math.RoundingMode

private const val DEFAULT_EMOJI = "💸"
private const val DEFAULT_COLOR_ARGB = 0xFFF06B6BL

/** 分类色板；与 seedDefaults 及详情页默认色保持同一体系。 */
val CATEGORY_COLOR_OPTIONS = listOf(
    0xFFF06B6BL, // 珊瑚红
    0xFFE8953DL, // 暖橙
    0xFFD9A514L, // 芒果黄
    0xFF39B98AL, // 薄荷绿
    0xFF4A7DDBL, // 晴空蓝
    0xFF8E6FDEL, // 薰衣草紫
    0xFFE86FA4L, // 樱花粉
    0xFF7B7370L, // 暖灰
)

data class CategoryEditUiState(
    val name: String = "",
    val emoji: String = DEFAULT_EMOJI,
    val colorArgb: Long = DEFAULT_COLOR_ARGB,
    val periodType: PeriodType = PeriodType.MONTHLY,
    val quotaText: String = "",
    val feePercentText: String = "10",
    val existing: CategoryEntity? = null,
    val loaded: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
)

class CategoryEditViewModel(
    container: AppContainer,
    private val categoryId: Long,
) : ViewModel() {

    private val db = container.database
    private val repository: CategoryRepository = container.categoryRepository

    private val _state = MutableStateFlow(CategoryEditUiState())
    val state: StateFlow<CategoryEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val defaultFeeBp = container.settingsRepository.flow.first().defaultFeeBp
            if (categoryId > 0) {
                val category = db.categoryDao().getById(categoryId)
                if (category == null) {
                    // 分类已被删除：不要退回"新建"，否则保存会凭空多出一条记录
                    _state.update { it.copy(loaded = true, error = "分类不存在") }
                    return@launch
                }
                _state.update {
                    it.copy(
                        name = category.name,
                        emoji = category.emoji,
                        colorArgb = category.colorArgb,
                        periodType = category.periodType,
                        quotaText = centsToPlainYuan(category.quotaCents),
                        feePercentText = percentText(category.feeBp),
                        existing = category,
                        loaded = true,
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(feePercentText = percentText(defaultFeeBp), loaded = true)
            }
        }
    }

    fun setName(value: String) {
        _state.update { it.copy(name = value, error = null) }
    }

    fun setEmoji(value: String) {
        _state.update { it.copy(emoji = value) }
    }

    fun setColorArgb(value: Long) {
        _state.update { it.copy(colorArgb = value) }
    }

    fun setPeriodType(value: PeriodType) {
        _state.update { it.copy(periodType = value) }
    }

    fun setQuotaText(value: String) {
        _state.update { it.copy(quotaText = value, error = null) }
    }

    fun setFeePercentText(value: String) {
        _state.update { it.copy(feePercentText = value, error = null) }
    }

    fun save() {
        val snapshot = _state.value
        // 加载完成前保存会把(target)分类当成新建，必须拦住；同时防止重复点击
        if (!snapshot.loaded || snapshot.saving) return
        if (categoryId > 0 && snapshot.existing == null) {
            _state.update { it.copy(error = "分类不存在") }
            return
        }
        if (snapshot.name.isBlank()) {
            _state.update { it.copy(error = "请填写分类名称") }
            return
        }
        val quota = if (snapshot.quotaText.isBlank()) null else parseYuanToCents(snapshot.quotaText)
        if (snapshot.quotaText.isNotBlank() && quota == null) {
            _state.update { it.copy(error = "配额金额无效") }
            return
        }
        val feeBp = feeBpFromPercent(snapshot.feePercentText)
        if (feeBp == null) {
            _state.update { it.copy(error = "手续费率应在 0% - 100% 之间") }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val existing = snapshot.existing
                val nextSortOrder = existing?.sortOrder
                    ?: ((db.categoryDao().getAll().maxOfOrNull { it.sortOrder } ?: 0) + 1)
                val entity = CategoryEntity(
                    id = existing?.id ?: 0,
                    name = snapshot.name.trim(),
                    emoji = snapshot.emoji.ifBlank { DEFAULT_EMOJI },
                    colorArgb = snapshot.colorArgb,
                    quotaCents = quota,
                    periodType = snapshot.periodType,
                    feeBp = feeBp,
                    isSystem = existing?.isSystem ?: false,
                    archived = existing?.archived ?: false,
                    sortOrder = nextSortOrder,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                )
                repository.save(entity)
                _state.update { it.copy(saved = true, saving = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update {
                    it.copy(error = exception.message ?: "保存失败", saving = false)
                }
            }
        }
    }

    fun archive() {
        val existing = _state.value.existing ?: return
        viewModelScope.launch {
            try {
                repository.archive(existing)
                _state.update { it.copy(saved = true) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update { it.copy(error = exception.message ?: "归档失败") }
            }
        }
    }

    fun restore() {
        val existing = _state.value.existing ?: return
        viewModelScope.launch {
            try {
                repository.restore(existing)
                _state.update { it.copy(saved = true) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _state.update { it.copy(error = exception.message ?: "恢复失败") }
            }
        }
    }

    private fun percentText(bp: Int): String =
        BigDecimal(bp).divide(BigDecimal(100)).stripTrailingZeros().toPlainString()

    private fun feeBpFromPercent(text: String): Int? {
        val percent = text.trim().removeSuffix("%").trim().toBigDecimalOrNull() ?: return null
        if (percent < BigDecimal.ZERO || percent > BigDecimal(100)) return null
        return percent.multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
            .coerceIn(0, 10_000)
    }
}
