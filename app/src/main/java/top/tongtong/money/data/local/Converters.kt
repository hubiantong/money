package top.tongtong.money.data.local

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromPeriodType(value: PeriodType): String = value.name

    @TypeConverter
    fun toPeriodType(value: String): PeriodType =
        PeriodType.entries.firstOrNull { it.name == value } ?: PeriodType.MONTHLY

    @TypeConverter
    fun fromDirection(value: TxDirection): String = value.name

    @TypeConverter
    fun toDirection(value: String): TxDirection =
        TxDirection.entries.firstOrNull { it.name == value } ?: TxDirection.EXPENSE

    @TypeConverter
    fun fromSource(value: TxSource): String = value.name

    @TypeConverter
    fun toSource(value: String): TxSource =
        TxSource.entries.firstOrNull { it.name == value } ?: TxSource.MANUAL

    @TypeConverter
    fun fromSavingsType(value: SavingsType): String = value.name

    @TypeConverter
    fun toSavingsType(value: String): SavingsType =
        SavingsType.entries.firstOrNull { it.name == value } ?: SavingsType.SURPLUS_ROLLOVER

    @TypeConverter
    fun fromCandidateStatus(value: CandidateStatus): String = value.name

    @TypeConverter
    fun toCandidateStatus(value: String): CandidateStatus =
        CandidateStatus.entries.firstOrNull { it.name == value } ?: CandidateStatus.FAILED

    @TypeConverter
    fun fromImportStatus(value: ImportStatus): String = value.name

    @TypeConverter
    fun toImportStatus(value: String): ImportStatus =
        ImportStatus.entries.firstOrNull { it.name == value } ?: ImportStatus.FAILED
}
