package top.tongtong.money.ui.screens.transaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.ui.components.Divider
import top.tongtong.money.ui.components.EmptyState
import top.tongtong.money.ui.components.InfoRow
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyProgress
import top.tongtong.money.ui.components.Pill
import top.tongtong.money.ui.components.TransactionRow
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.DangerBackground
import top.tongtong.money.ui.theme.Mint
import top.tongtong.money.ui.theme.MintDark
import top.tongtong.money.ui.theme.Muted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val calendarWeekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
private val detailDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

@Composable
fun CalendarCard(
    calendar: CalendarUiState,
    onSelectDate: (LocalDate) -> Unit,
    onClearSelectedDate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overLimitCount = calendar.overLimitDays.sumOf { it.overLimitItems.size }
    MoneyCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "支出日历",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (overLimitCount > 0) {
                Text(
                    text = "${calendar.overLimitDays.size} 天 · $overLimitCount 笔超额",
                    color = Danger,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    text = "本月暂无超额",
                    color = Mint,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            calendarWeekLabels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val today = LocalDate.now()
        val cells: List<CalendarDayUi?> = buildList {
            repeat(calendar.leadingBlanks) { add(null) }
            addAll(calendar.days)
            while (size % 7 != 0) add(null)
        }
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        DayCell(
                            day = day,
                            selected = calendar.selectedDate == day.date,
                            today = today,
                            onClick = {
                                if (calendar.selectedDate == day.date) {
                                    onClearSelectedDate()
                                } else {
                                    onSelectDate(day.date)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(
    day: CalendarDayUi,
    selected: Boolean,
    today: LocalDate,
    onClick: () -> Unit,
) {
    val overLimit = day.overLimitItems.isNotEmpty()
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        overLimit -> DangerBackground
        else -> Color.Transparent
    }
    val description = buildString {
        append("${day.date.monthValue}月${day.date.dayOfMonth}日，${day.items.size}笔记录")
        if (day.overLimitItems.isNotEmpty()) {
            append("，${day.overLimitItems.size}笔超额")
        }
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(2.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(
                if (!selected && day.date == today) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClickLabel = "查看当日明细", onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = day.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (overLimit) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                overLimit -> Danger
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        when {
            overLimit -> {
                val first = day.overLimitItems.first()
                val label = buildString {
                    append(first.category?.emoji ?: "💸")
                    append(compactYuan(first.transaction.amountCents))
                    if (day.overLimitItems.size > 1) {
                        append("+${day.overLimitItems.size - 1}")
                    }
                }
                Text(
                    text = label,
                    color = Danger,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            day.items.isNotEmpty() -> Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(3.dp)
                    .background(Muted, CircleShape),
            )
            else -> Unit
        }
    }
}

/** 日历格内的金额压缩展示：仅保留整数元，避免撑破格子。 */
private fun compactYuan(cents: Long): String {
    val yuan = cents / 100
    return if (yuan >= 10_000) "¥${yuan / 10_000}万" else "¥$yuan"
}

@Composable
fun DayDetailPanel(
    visible: Boolean,
    day: CalendarDayUi?,
    onCollapse: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onOpenSourceCategory: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 收起时 selectedDay 会立即变为 null，保留最后一帧数据，让退出动画有内容可收缩
    var retainedDay by remember { mutableStateOf<CalendarDayUi?>(null) }
    LaunchedEffect(day) {
        if (day != null) retainedDay = day
    }
    val displayDay = day ?: retainedDay
    AnimatedVisibility(
        visible = visible && day != null,
        modifier = modifier,
        enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(120)),
    ) {
        val target = displayDay ?: return@AnimatedVisibility
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = target.date.format(detailDateFormatter),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCollapse) { Text("收起") }
            }
            if (target.items.isEmpty()) {
                EmptyState("这一天没有记录")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayTotalCard("支出", target.expenseCents, Danger, Modifier.weight(1f))
                    DayTotalCard("收入", target.incomeCents, MintDark, Modifier.weight(1f))
                    DayTotalCard("退款", target.refundCents, MintDark, Modifier.weight(1f))
                }
                target.items.forEach { item ->
                    DayItemCard(
                        item = item,
                        onOpenTransaction = onOpenTransaction,
                        onOpenSourceCategory = onOpenSourceCategory,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayTotalCard(label: String, cents: Long, color: Color, modifier: Modifier = Modifier) {
    MoneyCard(modifier = modifier, contentPadding = PaddingValues(10.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        Text(
            text = formatCents(cents),
            color = color,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DayItemCard(
    item: DayItemUi,
    onOpenTransaction: (Long) -> Unit,
    onOpenSourceCategory: (Long) -> Unit,
) {
    val transaction = item.transaction
    MoneyCard(
        containerColor = if (transaction.overLimit) {
            DangerBackground
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentPadding = PaddingValues(10.dp),
    ) {
        TransactionRow(
            transaction = transaction,
            category = item.category,
            onClick = { onOpenTransaction(transaction.id) },
        )
        if (transaction.direction == TxDirection.EXPENSE) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    transaction.overLimit -> Pill("已超额", Color.White, Danger)
                    item.quota != null -> Pill(
                        "正常",
                        MaterialTheme.colorScheme.onSurface,
                        Mint.copy(alpha = 0.18f),
                    )
                    else -> Pill(
                        "未参与配额",
                        Muted,
                        MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                if (transaction.status.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "状态：${transaction.status}",
                        color = Muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(6.dp))
            Text("金额详情", color = Muted, style = MaterialTheme.typography.labelLarge)
            InfoRow("配额抵扣", formatCents(transaction.quotaCoveredCents))
            InfoRow("同类结余", formatCents(transaction.sameSurplusCents))
            InfoRow("跨类结余", formatCents(transaction.crossSurplusCents))
            InfoRow("抵扣手续费", formatCents(transaction.feeCents))
            InfoRow(
                "未抵扣（超支）",
                formatCents(transaction.uncoveredCents),
                valueColor = if (transaction.uncoveredCents > 0) {
                    Danger
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(6.dp))
            Text("超额阈值对比", color = Muted, style = MaterialTheme.typography.labelLarge)
            val quota = item.quota
            when {
                quota != null -> {
                    Text(
                        text = "${quota.periodLabel}配额 ${quota.periodText}：已用 " +
                            "${formatCents(quota.spentCents)} / ${formatCents(quota.quotaCents)}",
                        color = if (quota.overLimit) Danger else Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    MoneyProgress(
                        usedCents = quota.spentCents,
                        quotaCents = quota.quotaCents,
                        over = quota.overLimit,
                    )
                    if (quota.overLimit) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "超出 ${formatCents((quota.spentCents - quota.quotaCents).coerceAtLeast(0))}",
                            color = Danger,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                item.category == null -> Text(
                    text = "未分类，不参与配额计算",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
                item.category?.quotaCents != null -> Text(
                    text = "该分类本周期暂无配额记录",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
                else -> Text(
                    text = "该分类未设置配额，不参与超额预警",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("结余来源", color = Muted, style = MaterialTheme.typography.labelLarge)
            if (item.sources.isEmpty()) {
                val sourceMessage = when {
                    transaction.uncoveredCents > 0 ->
                        "无可用结余，未抵扣 ${formatCents(transaction.uncoveredCents)}"
                    transaction.quotaCoveredCents > 0 ->
                        "本笔全部由配额抵扣，无结余来源"
                    else ->
                        "本笔未参与配额抵扣，无结余来源"
                }
                Text(
                    text = sourceMessage,
                    color = if (transaction.uncoveredCents > 0) Danger else Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                item.sources.forEach { source ->
                    SourceUsageRow(source = source, onOpenSourceCategory = onOpenSourceCategory)
                }
            }
        } else if (transaction.status.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "状态：${transaction.status}",
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun OverLimitOverviewCard(
    overLimitDays: List<CalendarDayUi>,
    onSelectDate: (LocalDate) -> Unit,
    onOpenSourceCategory: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (overLimitDays.isEmpty()) return
    val itemCount = overLimitDays.sumOf { it.overLimitItems.size }
    MoneyCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本月超额概览",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${overLimitDays.size} 天 · $itemCount 笔",
                color = Danger,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(6.dp))
        overLimitDays.forEachIndexed { index, day ->
            if (index > 0) {
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(4.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "查看当日明细") { onSelectDate(day.date) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${day.date.monthValue}月${day.date.dayOfMonth}日",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${day.overLimitItems.size} 笔超额",
                    color = Danger,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(6.dp))
                Text("查看 ›", color = Muted, style = MaterialTheme.typography.labelMedium)
            }
            day.overLimitItems.forEach { item ->
                OverLimitItemBlock(item = item, onOpenSourceCategory = onOpenSourceCategory)
            }
        }
    }
}

@Composable
private fun OverLimitItemBlock(
    item: DayItemUi,
    onOpenSourceCategory: (Long) -> Unit,
) {
    val transaction = item.transaction
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${item.category?.emoji ?: "💸"} ${item.category?.name ?: "未分类"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatCents(transaction.amountCents),
            color = Danger,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val merchant = transaction.merchant.ifBlank { transaction.product.ifBlank { transaction.note } }
    if (merchant.isNotBlank()) {
        Text(
            text = merchant,
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (item.sources.isEmpty()) {
        if (transaction.uncoveredCents > 0) {
            Text(
                text = "无可用结余，未抵扣 ${formatCents(transaction.uncoveredCents)}",
                color = Danger,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    } else {
        Text(
            text = "结余来源",
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        item.sources.forEach { source ->
            SourceUsageRow(source = source, onOpenSourceCategory = onOpenSourceCategory)
        }
    }
}

@Composable
private fun SourceUsageRow(
    source: SourceUsageUi,
    onOpenSourceCategory: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "查看来源分类明细") {
                onOpenSourceCategory(source.sourceCategoryId)
            }
            .padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${source.sourceCategoryEmoji}${source.sourceCategoryName}结余",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                if (source.crossCategory) {
                    Pill("跨类", Danger, DangerBackground)
                } else {
                    Pill("同类", MintDark, Mint.copy(alpha = 0.18f))
                }
            }
            Text(
                text = "周期 ${source.sourcePeriodLabel}",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatCents(source.amountCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (source.feeCents > 0) {
                Text(
                    text = "手续费 ${formatCents(source.feeCents)}",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Text("›", color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
