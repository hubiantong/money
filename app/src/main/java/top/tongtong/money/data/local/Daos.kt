package top.tongtong.money.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isSystem ASC, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY isSystem ASC, sortOrder ASC, id ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("UPDATE categories SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC, id DESC")
    fun observeRange(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt ASC, id ASC")
    suspend fun getRangeAsc(from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from ORDER BY occurredAt ASC, id ASC")
    suspend fun getAllFrom(from: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY occurredAt ASC, id ASC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT MIN(occurredAt) FROM transactions")
    suspend fun earliestOccurredAt(): Long?

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: Long): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE externalId = :externalId LIMIT 1")
    suspend fun findByExternalId(externalId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE dedupKey = :dedupKey LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): TransactionEntity?

    /** 备份恢复时对无单号/无指纹的手动交易做内容指纹查重。 */
    @Query(
        "SELECT * FROM transactions WHERE occurredAt = :occurredAt AND amountCents = :amountCents " +
            "AND direction = :direction AND merchant = :merchant AND product = :product AND note = :note LIMIT 1",
    )
    suspend fun findFirstSimilar(
        occurredAt: Long,
        amountCents: Long,
        direction: String,
        merchant: String,
        product: String,
        note: String,
    ): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(transaction: TransactionEntity): Long

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "UPDATE transactions SET overLimit = 0, quotaCoveredCents = 0, sameSurplusCents = 0, " +
            "crossSurplusCents = 0, feeCents = 0, uncoveredCents = 0 WHERE occurredAt >= :from",
    )
    suspend fun clearAllocationsFrom(from: Long)

    @Query(
        "UPDATE transactions SET overLimit = :overLimit, quotaCoveredCents = :quotaCovered, " +
            "sameSurplusCents = :sameSurplus, crossSurplusCents = :crossSurplus, " +
            "feeCents = :fee, uncoveredCents = :uncovered WHERE id = :id",
    )
    suspend fun updateAllocation(
        id: Long,
        overLimit: Boolean,
        quotaCovered: Long,
        sameSurplus: Long,
        crossSurplus: Long,
        fee: Long,
        uncovered: Long,
    )
}

@Dao
interface PeriodBucketDao {
    @Query("DELETE FROM period_buckets WHERE monthKey >= :monthKey")
    suspend fun deleteFromMonth(monthKey: String)

    @Query("DELETE FROM period_buckets")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(buckets: List<PeriodBucketEntity>)

    @Query("SELECT * FROM period_buckets")
    suspend fun getAll(): List<PeriodBucketEntity>

    @Query("SELECT * FROM period_buckets WHERE periodStart <= :at AND periodEnd > :at")
    fun observeActive(at: Long): Flow<List<PeriodBucketEntity>>

    @Query("SELECT * FROM period_buckets WHERE categoryId = :categoryId AND periodType = :periodType ORDER BY periodStart DESC LIMIT 1")
    fun observeLatest(categoryId: Long, periodType: PeriodType): Flow<PeriodBucketEntity?>

    @Query("SELECT * FROM period_buckets WHERE categoryId = :categoryId AND periodStart <= :at AND periodEnd > :at LIMIT 1")
    suspend fun findCurrent(categoryId: Long, at: Long): PeriodBucketEntity?
}

@Dao
interface SurplusGrantDao {
    @Query("DELETE FROM surplus_grants WHERE monthKey >= :monthKey")
    suspend fun deleteFromMonth(monthKey: String)

    @Insert
    suspend fun insertAll(grants: List<SurplusGrantEntity>)

    @Query("SELECT * FROM surplus_grants ORDER BY expiresAt ASC, createdAt ASC")
    suspend fun getAll(): List<SurplusGrantEntity>

    @Query("SELECT * FROM surplus_grants WHERE monthKey = :monthKey AND remainingCents > 0 ORDER BY expiresAt ASC, createdAt ASC")
    fun observeActive(monthKey: String): Flow<List<SurplusGrantEntity>>

    @Query("SELECT COALESCE(SUM(remainingCents), 0) FROM surplus_grants WHERE monthKey = :monthKey AND remainingCents > 0")
    fun observeRemaining(monthKey: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(remainingCents), 0) FROM surplus_grants WHERE categoryId = :categoryId AND monthKey = :monthKey AND remainingCents > 0")
    fun observeRemainingForCategory(categoryId: Long, monthKey: String): Flow<Long>
}

@Dao
interface SurplusUsageDao {
    @Query("DELETE FROM surplus_usages WHERE monthKey >= :monthKey")
    suspend fun deleteFromMonth(monthKey: String)

    @Insert
    suspend fun insertAll(usages: List<SurplusUsageEntity>)

    @Query("SELECT * FROM surplus_usages WHERE transactionId = :transactionId ORDER BY createdAt ASC")
    suspend fun forTransaction(transactionId: Long): List<SurplusUsageEntity>
}

@Dao
interface SavingsDao {
    @Query("DELETE FROM savings_entries WHERE monthKey >= :monthKey")
    suspend fun deleteFromMonth(monthKey: String)

    @Insert
    suspend fun insertAll(entries: List<SavingsEntryEntity>)

    @Query("SELECT * FROM savings_entries ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<SavingsEntryEntity>>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM savings_entries")
    fun observeBalance(): Flow<Long>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM savings_entries WHERE monthKey = :monthKey")
    fun observeMonthTotal(monthKey: String): Flow<Long>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM savings_entries WHERE monthKey = :monthKey")
    suspend fun monthTotal(monthKey: String): Long
}

@Dao
interface MerchantRuleDao {
    @Query("SELECT * FROM merchant_rules ORDER BY priority DESC, pattern ASC")
    fun observeAll(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules WHERE id = :id")
    suspend fun getById(id: Long): MerchantRuleEntity?

    @Query("SELECT * FROM merchant_rules ORDER BY priority DESC, pattern ASC")
    suspend fun getAll(): List<MerchantRuleEntity>

    @Insert
    suspend fun insert(rule: MerchantRuleEntity): Long

    @Update
    suspend fun update(rule: MerchantRuleEntity)

    @Query("DELETE FROM merchant_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM merchant_rules WHERE pattern = :pattern AND categoryId = :categoryId")
    suspend fun deleteExact(pattern: String, categoryId: Long)

    @Query("DELETE FROM merchant_rules WHERE pattern = :pattern")
    suspend fun deleteByPattern(pattern: String)
}

@Dao
interface ImportCandidateDao {
    @Query("SELECT * FROM import_candidates ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<ImportCandidateEntity>>

    @Query("SELECT * FROM import_candidates WHERE messageId = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: String): ImportCandidateEntity?

    @Query("SELECT * FROM import_candidates WHERE id = :id")
    suspend fun getById(id: Long): ImportCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(candidate: ImportCandidateEntity): Long

    @Update
    suspend fun update(candidate: ImportCandidateEntity)

    @Query("UPDATE import_candidates SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: CandidateStatus, error: String?)

    @Query("UPDATE import_candidates SET status = :status, error = :error, importedAt = :importedAt WHERE id = :id")
    suspend fun markImported(id: Long, status: CandidateStatus, error: String?, importedAt: Long)
}

@Dao
interface ImportLogDao {
    @Query("SELECT * FROM import_logs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImportLogEntity>>

    @Insert
    suspend fun insert(log: ImportLogEntity): Long

    @Query("SELECT * FROM import_logs ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(): ImportLogEntity?
}

@Dao
interface LedgerStateDao {
    @Query("SELECT * FROM ledger_state WHERE id = 1")
    suspend fun get(): LedgerStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: LedgerStateEntity)
}
