package top.tongtong.money.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.Muted
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 时区在调用时读取，用户切换时区后无需重启进程即可生效
private fun currentMoneyZone(): ZoneId = ZoneId.systemDefault()

// 预编译格式化器，避免列表每行重复构建
private val fullDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

fun formatCents(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    // Long.MIN_VALUE 取绝对值会溢出，单独拆分，避免输出错误符号/位数
    if (cents == Long.MIN_VALUE) {
        val abs = java.math.BigInteger.valueOf(cents).abs()
        val hundred = java.math.BigInteger.valueOf(100)
        val yuan = abs.divide(hundred)
        val fen = abs.mod(hundred).toString().padStart(2, '0')
        return String.format(Locale.CHINA, "%s¥%,d.%s", sign, yuan, fen)
    }
    val abs = kotlin.math.abs(cents)
    val yuan = abs / 100
    val fen = abs % 100
    return String.format(Locale.CHINA, "%s¥%,d.%02d", sign, yuan, fen)
}

fun formatSignedCents(cents: Long): String = if (cents >= 0) "+${formatCents(cents)}" else formatCents(cents)

fun centsToPlainYuan(cents: Long?): String {
    if (cents == null) return ""
    return BigDecimal.valueOf(cents, 2)
        .toPlainString()
        .trimEnd('0')
        .trimEnd('.')
}

fun parseYuanToCents(input: String): Long? {
    val cleaned = input.trim().replace("¥", "").replace("￥", "").replace(",", "")
    if (cleaned.isBlank()) return null
    return runCatching {
        BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()?.takeIf { it > 0 }
}

fun formatMonthLabel(monthKey: String): String = runCatching {
    val month = YearMonth.parse(monthKey)
    "${month.year}年${month.monthValue}月"
}.getOrDefault(monthKey)

fun formatDate(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(currentMoneyZone()).toLocalDate()
    return "${date.monthValue}月${date.dayOfMonth}日"
}

fun formatDateTime(epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(currentMoneyZone())
    return "${time.monthValue}月${time.dayOfMonth}日 ${String.format(Locale.CHINA, "%02d:%02d", time.hour, time.minute)}"
}

fun formatFullDateTime(epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(currentMoneyZone())
    return time.format(fullDateTimeFormatter)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
fun MoneyCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

@Composable
fun MoneyProgress(
    usedCents: Long,
    quotaCents: Long,
    over: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = if (quotaCents <= 0) 0f else (usedCents.toFloat() / quotaCents).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().height(8.dp),
        color = if (over) Danger else MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
fun CategoryAvatar(emoji: String, size: Int = 40) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = (size * 0.5f).sp)
    }
}

@Composable
fun Pill(
    text: String,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🌱", fontSize = 28.sp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun MonthSwitcher(
    monthKey: String,
    onShift: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
        }
        Text(
            text = formatMonthLabel(monthKey),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = { onShift(1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月")
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = valueColor,
            style = valueStyle,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
}
