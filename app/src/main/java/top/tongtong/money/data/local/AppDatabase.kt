package top.tongtong.money.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction

@Database(
    entities = [
        CategoryEntity::class,
        TransactionEntity::class,
        PeriodBucketEntity::class,
        SurplusGrantEntity::class,
        SurplusUsageEntity::class,
        SavingsEntryEntity::class,
        MerchantRuleEntity::class,
        ImportCandidateEntity::class,
        ImportLogEntity::class,
        LedgerStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun bucketDao(): PeriodBucketDao
    abstract fun grantDao(): SurplusGrantDao
    abstract fun usageDao(): SurplusUsageDao
    abstract fun savingsDao(): SavingsDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun importCandidateDao(): ImportCandidateDao
    abstract fun importLogDao(): ImportLogDao
    abstract fun ledgerStateDao(): LedgerStateDao

    suspend fun <T> inTransaction(block: suspend () -> T): T = withTransaction(block)
}
