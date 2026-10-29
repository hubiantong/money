package top.tongtong.money.data.repo

import top.tongtong.money.data.local.AppDatabase
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.MerchantRuleEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.prefs.SettingsRepository
import top.tongtong.money.domain.backup.ParsedBackup
import top.tongtong.money.domain.ledger.PeriodCalculator

data class BackupPreview(
    val version: Int,
    val categoryCount: Int,
    val transactionCount: Int,
    val ruleCount: Int,
)

data class RestoreResult(
    val categoriesAdded: Int,
    val transactionsAdded: Int,
    val transactionsSkipped: Int,
    val rulesAdded: Int,
    val rulesSkipped: Int,
) {
    fun describe(): String =
        "已恢复 $transactionsAdded 笔交易、$categoriesAdded 个分类、$rulesAdded 条规则" +
            "（跳过重复 $transactionsSkipped 笔）"
}

class BackupRepository(
    private val db: AppDatabase,
    private val ledgerRepository: LedgerRepository,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 恢复备份。合并策略：
     * - 分类按名称匹配现有分类（含归档），匹配不到才新建，避免同名分类出现两份；
     * - 交易按 externalId / dedupKey 去重，两者皆无的手动交易用内容指纹查重，
     *   防止同一份备份重复导入产生成倍记录；
     * - 规则按归一化关键词去重，分类引用映射到恢复后的新 id；
     * - 月预算仅在备份里非空时覆盖，默认费率总是恢复；
     * - 交易落库后从最早交易所在月起重建派生数据（周期桶/结余/储蓄）。
     * 授权码、解压码等密钥不包含在备份内，恢复后需重新配置邮箱。
     */
    suspend fun restore(backup: ParsedBackup): RestoreResult {
        val result = db.inTransaction {
            // 同名分类可能存在活跃+归档两份（name 无唯一约束）：优先匹配活跃分类，
            // 避免交易被挂到归档分类上导致回放时数据"看似消失"
            val existingByName = db.categoryDao().getAll()
                .sortedBy { it.archived }
                .associateBy { it.name.trim() }
            val categoryMap = HashMap<Long, Long>()
            var categoriesAdded = 0
            for (category in backup.categories) {
                val existing = existingByName[category.name]
                val newId = existing?.id ?: db.categoryDao().insert(
                    CategoryEntity(
                        name = category.name,
                        emoji = category.emoji,
                        colorArgb = category.colorArgb,
                        quotaCents = category.quotaCents,
                        periodType = category.periodType,
                        feeBp = category.feeBp,
                        isSystem = category.isSystem,
                        archived = category.archived,
                        sortOrder = category.sortOrder,
                        createdAt = category.createdAt,
                    ),
                )
                categoryMap[category.id] = newId
                if (existing == null) categoriesAdded++
            }
            val uncategorizedId = existingByName["未分类"]?.id

            var transactionsAdded = 0
            var transactionsSkipped = 0
            val now = System.currentTimeMillis()
            for (tx in backup.transactions) {
                if (tx.amountCents <= 0 || tx.occurredAt <= 0) {
                    transactionsSkipped++
                    continue
                }
                if (tx.externalId != null &&
                    db.transactionDao().findByExternalId(tx.externalId) != null
                ) {
                    transactionsSkipped++
                    continue
                }
                if (tx.dedupKey != null && db.transactionDao().findByDedupKey(tx.dedupKey) != null) {
                    transactionsSkipped++
                    continue
                }
                if (tx.externalId == null && tx.dedupKey == null &&
                    db.transactionDao().findFirstSimilar(
                        tx.occurredAt,
                        tx.amountCents,
                        tx.direction.name,
                        tx.merchant,
                        tx.product,
                        tx.note,
                    ) != null
                ) {
                    transactionsSkipped++
                    continue
                }
                db.transactionDao().insert(
                    TransactionEntity(
                        externalId = tx.externalId,
                        dedupKey = tx.dedupKey,
                        source = tx.source,
                        direction = tx.direction,
                        amountCents = tx.amountCents,
                        categoryId = tx.categoryId?.let { categoryMap[it] ?: uncategorizedId },
                        merchant = tx.merchant,
                        product = tx.product,
                        note = tx.note,
                        occurredAt = tx.occurredAt,
                        rawType = tx.rawType,
                        payMethod = tx.payMethod,
                        status = tx.status,
                        createdAt = tx.createdAt,
                        updatedAt = now,
                    ),
                )
                transactionsAdded++
            }

            val existingPatterns = db.merchantRuleDao().getAll().associateBy { it.pattern }
            var rulesAdded = 0
            var rulesSkipped = 0
            for (rule in backup.merchantRules) {
                val pattern = rule.pattern.trim().lowercase()
                val categoryId = categoryMap[rule.categoryId]
                if (pattern.length < 2 || categoryId == null || existingPatterns.containsKey(pattern)) {
                    rulesSkipped++
                    continue
                }
                db.merchantRuleDao().insert(
                    MerchantRuleEntity(
                        pattern = pattern,
                        categoryId = categoryId,
                        priority = rule.priority,
                        createdAt = rule.createdAt,
                    ),
                )
                rulesAdded++
            }
            RestoreResult(
                categoriesAdded = categoriesAdded,
                transactionsAdded = transactionsAdded,
                transactionsSkipped = transactionsSkipped,
                rulesAdded = rulesAdded,
                rulesSkipped = rulesSkipped,
            )
        }

        backup.monthlyBudgetCents?.let { settingsRepository.setMonthlyBudget(it) }
        settingsRepository.setDefaultFeeBp(backup.defaultFeeBp)

        val earliest = db.transactionDao().earliestOccurredAt()
        if (earliest != null) {
            ledgerRepository.rebuildFrom(PeriodCalculator.monthKeyOf(earliest))
        }
        return result
    }
}
