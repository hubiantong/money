package top.tongtong.money.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2：分类新增同类抵扣费率；交易新增手选资金来源。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE categories ADD COLUMN sameFeeBp INTEGER NOT NULL DEFAULT 100")
        db.execSQL("ALTER TABLE transactions ADD COLUMN surplusSourcePrefs TEXT NOT NULL DEFAULT ''")
    }
}

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
    version = 2,
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
