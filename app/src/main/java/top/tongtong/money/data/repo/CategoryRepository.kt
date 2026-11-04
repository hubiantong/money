package top.tongtong.money.data.repo

import top.tongtong.money.data.local.AppDatabase
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.MerchantRuleEntity
import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.data.prefs.SettingsRepository
import top.tongtong.money.domain.ledger.PeriodCalculator

class CategoryRepository(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val ledgerRepository: LedgerRepository,
) {

    val categories = db.categoryDao().observeAll()
    val merchantRules = db.merchantRuleDao().observeAll()

    /** 首次启动写入默认分类；创建时间对齐当前月，避免回放时生成无意义的历史周期。 */
    suspend fun seedDefaults() {
        db.inTransaction<Unit> {
            // 事务内重新检查，避免并发调用时重复写入。
            if (db.categoryDao().count() > 0) return@inTransaction
            val monthStart = PeriodCalculator.monthStart(PeriodCalculator.currentMonthKey())
            val defaults = listOf(
                Triple("餐饮", "🍚", PeriodType.DAILY),
                Triple("交通", "🚌", PeriodType.WEEKLY),
                Triple("购物", "🛍️", PeriodType.MONTHLY),
                Triple("娱乐", "🎮", PeriodType.MONTHLY),
                Triple("学习", "📚", PeriodType.MONTHLY),
                Triple("住房", "🏠", PeriodType.MONTHLY),
                Triple("医疗", "💊", PeriodType.MONTHLY),
                Triple("其他", "🧾", PeriodType.MONTHLY),
            )
            defaults.forEachIndexed { index, (name, emoji, period) ->
                db.categoryDao().insert(
                    CategoryEntity(
                        name = name,
                        emoji = emoji,
                        quotaCents = null,
                        periodType = period,
                        isSystem = false,
                        sortOrder = index,
                        createdAt = monthStart,
                    ),
                )
            }
            db.categoryDao().insert(
                CategoryEntity(
                    name = "未分类",
                    emoji = "❓",
                    quotaCents = null,
                    periodType = PeriodType.MONTHLY,
                    isSystem = true,
                    sortOrder = 999,
                    createdAt = monthStart,
                ),
            )
        }
    }

    /** 新增或更新分类；配额、周期、费率变化后从当前月重算。 */
    suspend fun save(category: CategoryEntity): Long = db.inTransaction {
        var rebuild = true
        val id = if (category.id == 0L) {
            db.categoryDao().insert(category)
        } else {
            val previous = db.categoryDao().getById(category.id)
            rebuild = previous == null ||
                previous.quotaCents != category.quotaCents ||
                previous.periodType != category.periodType ||
                previous.feeBp != category.feeBp ||
                previous.sameFeeBp != category.sameFeeBp ||
                previous.createdAt != category.createdAt ||
                previous.archived != category.archived
            db.categoryDao().update(category)
            category.id
        }
        if (rebuild) ledgerRepository.rebuildFrom(PeriodCalculator.currentMonthKey())
        id
    }

    suspend fun archive(category: CategoryEntity) = setArchived(category, true)

    suspend fun restore(category: CategoryEntity) = setArchived(category, false)

    /**
     * 在未归档、非系统分类中按 delta（-1 上移 / +1 下移）交换 sortOrder。
     * 排序不影响账本回放，不需要 rebuildFrom。
     */
    suspend fun moveCategory(categoryId: Long, delta: Int) {
        if (delta == 0) return
        db.inTransaction {
            val ordered = db.categoryDao().getAll()
                .filter { !it.archived && !it.isSystem }
                .sortedBy { it.sortOrder }
            val index = ordered.indexOfFirst { it.id == categoryId }
            if (index < 0) return@inTransaction
            val target = index + delta
            if (target < 0 || target >= ordered.size) return@inTransaction
            val current = ordered[index]
            val neighbor = ordered[target]
            db.categoryDao().update(current.copy(sortOrder = neighbor.sortOrder))
            db.categoryDao().update(neighbor.copy(sortOrder = current.sortOrder))
        }
    }

    private suspend fun setArchived(category: CategoryEntity, archived: Boolean) {
        if (category.isSystem) return
        db.inTransaction {
            val existing = db.categoryDao().getById(category.id) ?: return@inTransaction
            if (existing.isSystem || existing.archived == archived) return@inTransaction
            db.categoryDao().setArchived(category.id, archived)
            ledgerRepository.rebuildFrom(PeriodCalculator.currentMonthKey())
        }
    }

    suspend fun setMonthlyBudget(cents: Long?) {
        settingsRepository.setMonthlyBudget(cents)
    }

    suspend fun rememberMerchant(merchant: String, categoryId: Long) {
        upsertRule(merchant, categoryId)
    }

    suspend fun addRule(pattern: String, categoryId: Long) {
        upsertRule(pattern, categoryId)
    }

    /** 编辑规则关键词/分类；关键词归一化与新增一致，改关键词时保持关键词唯一。 */
    suspend fun updateRule(id: Long, rawPattern: String, categoryId: Long) {
        val pattern = rawPattern.trim().lowercase()
        if (pattern.length < 2) return
        db.inTransaction {
            val existing = db.merchantRuleDao().getById(id) ?: return@inTransaction
            // 只在关键词变化时清理占用；否则会误删自身导致 update 落空
            if (pattern != existing.pattern) {
                db.merchantRuleDao().deleteByPattern(pattern)
            }
            db.merchantRuleDao().update(existing.copy(pattern = pattern, categoryId = categoryId))
        }
    }

    /** 规则统一归一化（去空白 + 小写），与 MerchantCategorizer 的忽略大小写匹配保持一致。 */
    private suspend fun upsertRule(rawPattern: String, categoryId: Long) {
        val pattern = rawPattern.trim().lowercase()
        if (pattern.length < 2) return
        db.merchantRuleDao().deleteByPattern(pattern)
        db.merchantRuleDao().insert(MerchantRuleEntity(pattern = pattern, categoryId = categoryId))
    }

    suspend fun deleteRule(id: Long) {
        db.merchantRuleDao().deleteById(id)
    }
}
