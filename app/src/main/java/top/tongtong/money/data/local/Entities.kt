package top.tongtong.money.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PeriodType { DAILY, WEEKLY, MONTHLY }

enum class TxDirection { EXPENSE, INCOME, REFUND, NEUTRAL }

enum class TxSource { MANUAL, WECHAT }

enum class SavingsType { SURPLUS_ROLLOVER, MONTHLY_QUOTA_ROLLOVER, CROSS_FEE, SAME_FEE }

enum class CandidateStatus { PENDING_PASSWORD, READY, IMPORTED, EXPIRED, FAILED }

enum class ImportStatus { SUCCESS, PARTIAL, FAILED }

@Entity(
    tableName = "categories",
    indices = [Index("sortOrder")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "💸",
    val colorArgb: Long = 0xFFF06B6BL,
    val quotaCents: Long? = null,
    val periodType: PeriodType = PeriodType.MONTHLY,
    /** 跨类结余抵扣手续费（基点）。 */
    val feeBp: Int = 1000,
    /** 同类结余抵扣手续费（基点），默认 1%。 */
    val sameFeeBp: Int = 100,
    val isSystem: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["externalId"], unique = true),
        Index(value = ["dedupKey"], unique = true),
        Index("occurredAt"),
        Index("categoryId"),
        Index("direction"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalId: String? = null,
    val dedupKey: String? = null,
    val source: TxSource = TxSource.MANUAL,
    val direction: TxDirection = TxDirection.EXPENSE,
    val amountCents: Long,
    val categoryId: Long? = null,
    val merchant: String = "",
    val product: String = "",
    val note: String = "",
    val occurredAt: Long,
    val rawType: String = "",
    val payMethod: String = "",
    val status: String = "",
    /** 手动指定超额资金来源，编码为 `categoryId:sourcePeriodStart`，逗号分隔；空串表示自动抵扣。 */
    val surplusSourcePrefs: String = "",
    val overLimit: Boolean = false,
    val quotaCoveredCents: Long = 0,
    val sameSurplusCents: Long = 0,
    val crossSurplusCents: Long = 0,
    val feeCents: Long = 0,
    val uncoveredCents: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "period_buckets",
    indices = [
        Index(value = ["categoryId", "periodType", "periodStart"], unique = true),
        Index("monthKey"),
    ],
)
data class PeriodBucketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val periodType: PeriodType,
    val periodStart: Long,
    val periodEnd: Long,
    val quotaCents: Long,
    val spentCents: Long = 0,
    val overLimit: Boolean = false,
    val settled: Boolean = false,
    val monthKey: String,
)

@Entity(
    tableName = "surplus_grants",
    indices = [
        Index(value = ["categoryId", "sourcePeriodStart"], unique = true),
        Index("monthKey"),
        Index("expiresAt"),
    ],
)
data class SurplusGrantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val sourcePeriodStart: Long,
    val sourcePeriodEnd: Long,
    val amountCents: Long,
    val remainingCents: Long,
    val expiresAt: Long,
    val monthKey: String,
    val createdAt: Long,
)

@Entity(
    tableName = "surplus_usages",
    indices = [Index("monthKey"), Index("transactionId")],
)
data class SurplusUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val grantCategoryId: Long,
    val grantSourcePeriodStart: Long,
    val amountCents: Long,
    val feeCents: Long,
    val crossCategory: Boolean,
    val monthKey: String,
    val createdAt: Long,
)

@Entity(
    tableName = "savings_entries",
    indices = [Index("monthKey"), Index("type"), Index("createdAt")],
)
data class SavingsEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SavingsType,
    val amountCents: Long,
    val monthKey: String,
    val note: String = "",
    val createdAt: Long,
)

@Entity(
    tableName = "merchant_rules",
    indices = [Index("pattern"), Index("categoryId")],
)
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val categoryId: Long,
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "import_candidates",
    indices = [Index(value = ["messageId"], unique = true), Index("status")],
)
data class ImportCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val subject: String,
    val fromAddress: String,
    val receivedAt: Long,
    val periodStart: Long? = null,
    val periodEnd: Long? = null,
    val downloadUrl: String? = null,
    val fileName: String? = null,
    val status: CandidateStatus = CandidateStatus.PENDING_PASSWORD,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val importedAt: Long? = null,
)

@Entity(
    tableName = "import_logs",
    indices = [Index("createdAt"), Index("status")],
)
data class ImportLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val candidateId: Long? = null,
    val fileName: String,
    val totalRows: Int = 0,
    val importedRows: Int = 0,
    val duplicateRows: Int = 0,
    val skippedRows: Int = 0,
    val earliestAt: Long? = null,
    val latestAt: Long? = null,
    val status: ImportStatus = ImportStatus.SUCCESS,
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ledger_state")
data class LedgerStateEntity(
    @PrimaryKey val id: Long = 1,
    val schemaVersion: Int = 1,
    val lastRebuildAt: Long = 0,
)
