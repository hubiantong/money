package top.tongtong.money.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MonthSwitcher
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.theme.Mint
import top.tongtong.money.ui.theme.Muted
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

@Composable
fun StatsScreen(
    container: AppContainer,
    onOpenCategory: (categoryId: Long, monthKey: String) -> Unit = { _, _ -> },
) {
    val viewModel = appViewModel { StatsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "统计",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp),
        )
        MonthSwitcher(monthKey = state.monthKey, onShift = { viewModel.shiftMonth(it.toLong()) })

        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MoneyCard {
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("本月净支出", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Text(
                            formatCents(state.netExpenseCents),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("退款", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Text(
                            formatCents(state.refundCents),
                            style = MaterialTheme.typography.titleMedium,
                            color = Mint,
                        )
                    }
                }
            }

            MoneyCard {
                Text("分类占比", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (state.slices.isEmpty()) {
                    Text("本月暂无支出记录", color = Muted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    val total = state.slices.sumOf { it.cents }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(
                            slices = state.slices,
                            total = total,
                            onSliceClick = { slice ->
                                slice.categoryId?.let { onOpenCategory(it, state.monthKey) }
                            },
                        )
                        Column(
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.slices.take(6).forEach { slice ->
                                SliceLegend(
                                    slice,
                                    total,
                                    onClick = slice.categoryId
                                        ?.let { id -> { onOpenCategory(id, state.monthKey) } },
                                )
                            }
                        }
                    }
                }
            }

            MoneyCard {
                Text("每日支出", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (state.dailyCents.isEmpty()) {
                    Text("本月暂无数据", color = Muted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    BarChart(
                        values = state.dailyCents,
                        color = Color(0xFFF06B6B),
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf("1日", "10日", "20日", "月末").forEach {
                            Text(it, color = Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            MoneyCard {
                Text("近 6 个月趋势", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                BarChart(
                    values = state.months.map { it.cents },
                    color = Mint,
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    state.months.forEach { month ->
                        Text(
                            text = month.label,
                            color = Muted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SliceLegend(slice: CategorySlice, total: Long, onClick: (() -> Unit)?) {
    val percent = if (total <= 0) 0f else slice.cents.toFloat() / total * 100
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color(slice.colorArgb), CircleShape),
        )
        Text(
            text = " ${slice.emoji}${slice.name}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = String.format(Locale.CHINA, "%.0f%%", percent),
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun DonutChart(
    slices: List<CategorySlice>,
    total: Long,
    onSliceClick: (CategorySlice) -> Unit,
) {
    Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices, total) {
                    detectTapGestures { offset ->
                        val stroke = 20.dp.toPx()
                        sliceAt(
                            slices = slices,
                            total = total,
                            x = offset.x,
                            y = offset.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            strokePx = stroke,
                        )?.let(onSliceClick)
                    }
                },
        ) {
            val stroke = 20.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            if (total <= 0) {
                drawCircle(
                    color = Color(0xFFE8DDD8),
                    radius = (size.minDimension - stroke) / 2,
                    style = Stroke(width = stroke),
                )
                return@Canvas
            }
            var start = -90f
            slices.forEach { slice ->
                val sweep = 360f * slice.cents.toFloat() / total.toFloat()
                drawArc(
                    color = Color(slice.colorArgb),
                    startAngle = start,
                    sweepAngle = (sweep - 1.5f).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("本月支出", color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(
                text = formatCents(total),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 判断环形图上的点击命中的扇区：半径需落在环带内，角度换算到从 12 点开始的顺时针区间。 */
private fun sliceAt(
    slices: List<CategorySlice>,
    total: Long,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    strokePx: Float,
): CategorySlice? {
    if (total <= 0) return null
    val dx = x - width / 2f
    val dy = y - height / 2f
    val distance = sqrt(dx * dx + dy * dy)
    val outer = minOf(width, height) / 2f
    val inner = outer - strokePx
    if (distance < inner || distance > outer) return null
    var angle = Math.toDegrees(atan2(dy, dx).toDouble())
    if (angle < 0) angle += 360.0
    angle -= 90.0
    if (angle < 0) angle += 360.0
    var accumulated = 0.0
    slices.forEachIndexed { index, slice ->
        val sweep = 360.0 * slice.cents / total
        // 浮点累计可能有 1 度以内的余量，最后一扇区兜底
        if (index == slices.lastIndex || angle < accumulated + sweep) return slice
        accumulated += sweep
    }
    return null
}

@Composable
private fun BarChart(values: List<Long>, color: Color, modifier: Modifier) {
    if (values.isEmpty()) return
    val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Canvas(modifier = modifier) {
        val count = values.size
        val slot = size.width / count
        val gap = slot * 0.35f
        val barWidth = (slot - gap).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            val ratio = value.toFloat() / maxValue.toFloat()
            val barHeight = (size.height * ratio).coerceAtLeast(if (value > 0) 2.dp.toPx() else 0f)
            val left = index * slot + gap / 2
            drawRoundRect(
                color = color,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}
