package top.tongtong.money.domain.ledger

import top.tongtong.money.data.local.PeriodType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object PeriodCalculator {

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun monthKeyOf(epochMillis: Long, zone: ZoneId = zone()): String =
        YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()).toString()

    fun currentMonthKey(now: Long = System.currentTimeMillis(), zone: ZoneId = zone()): String =
        monthKeyOf(now, zone)

    fun monthStart(monthKey: String, zone: ZoneId = zone()): Long =
        YearMonth.parse(monthKey).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun monthEndExclusive(monthKey: String, zone: ZoneId = zone()): Long =
        YearMonth.parse(monthKey).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun monthEndInclusive(monthKey: String, zone: ZoneId = zone()): Long =
        monthEndExclusive(monthKey, zone) - 1L

    fun shiftMonth(monthKey: String, months: Long): String =
        YearMonth.parse(monthKey).plusMonths(months).toString()

    /** 周期区间：start 含、end 不含（左闭右开）。 */
    data class Period(val start: Long, val end: Long)

    /** 单日、单周、单月在指定时间点所属的周期（周期被自然月裁剪，跨月周会在月初/月末断开）。 */
    fun periodOf(epochMillis: Long, type: PeriodType, zone: ZoneId = zone()): Period {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val monthKey = YearMonth.from(date).toString()
        val startOfMonth = monthStart(monthKey, zone)
        val endOfMonth = monthEndExclusive(monthKey, zone)
        // 直接计算所属周期，避免为每笔交易生成整月周期列表
        return when (type) {
            PeriodType.MONTHLY -> Period(startOfMonth, endOfMonth)
            PeriodType.DAILY -> Period(
                date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            PeriodType.WEEKLY -> {
                val weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                val weekEnd = date.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                Period(maxOf(weekStart, startOfMonth), minOf(weekEnd, endOfMonth))
            }
        }
    }

    /**
     * 某个月内的全部周期。
     * 单日：每天一段；单周：从月初开始，到下周一断开，最后一段在月末结束；单月：整月一段。
     */
    fun periodsOfMonth(monthKey: String, type: PeriodType, zone: ZoneId = zone()): List<Period> {
        val month = YearMonth.parse(monthKey)
        val startOfMonth = monthStart(monthKey, zone)
        val endOfMonth = monthEndExclusive(monthKey, zone)
        val lastDay = month.atEndOfMonth()
        return when (type) {
            PeriodType.MONTHLY -> listOf(Period(startOfMonth, endOfMonth))
            PeriodType.DAILY -> {
                val result = mutableListOf<Period>()
                var date = month.atDay(1)
                while (!date.isAfter(lastDay)) {
                    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    result += Period(start, end)
                    date = date.plusDays(1)
                }
                result
            }
            PeriodType.WEEKLY -> {
                val result = mutableListOf<Period>()
                var cursorDate = month.atDay(1)
                while (!cursorDate.isAfter(lastDay)) {
                    val start = cursorDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val boundaryStart = nextWeekBoundary(cursorDate)
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = minOf(boundaryStart, endOfMonth)
                    result += Period(start, end)
                    cursorDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
                }
                result
            }
        }
    }

    private fun nextWeekBoundary(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.next(DayOfWeek.MONDAY))

    fun monthRange(monthKey: String, zone: ZoneId = zone()): LongRange =
        monthStart(monthKey, zone) until monthEndExclusive(monthKey, zone)

    fun periodLabel(type: PeriodType): String = when (type) {
        PeriodType.DAILY -> "单日"
        PeriodType.WEEKLY -> "单周"
        PeriodType.MONTHLY -> "单月"
    }
}
