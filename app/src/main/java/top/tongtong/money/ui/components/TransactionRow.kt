package top.tongtong.money.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.DangerBackground
import top.tongtong.money.ui.theme.MintDark
import top.tongtong.money.ui.theme.Muted

@Composable
fun TransactionRow(
    transaction: TransactionEntity,
    category: CategoryEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountColor = when {
        transaction.overLimit -> Danger
        transaction.direction == TxDirection.REFUND || transaction.direction == TxDirection.INCOME -> MintDark
        transaction.direction == TxDirection.NEUTRAL -> Muted
        else -> MaterialTheme.colorScheme.onSurface
    }
    val title = transaction.merchant.ifBlank {
        transaction.product.ifBlank { category?.name ?: "消费记录" }
    }
    val subtitle = buildString {
        val categoryName = category?.name ?: "未分类"
        // 标题已回退为分类名时不再重复展示
        if (categoryName != title) {
            append(categoryName)
            append(" · ")
        }
        if (transaction.product.isNotBlank() && transaction.product != title) {
            append(transaction.product)
            append(" · ")
        }
        append(formatDateTime(transaction.occurredAt))
    }
    val prefix = when (transaction.direction) {
        TxDirection.EXPENSE -> "-"
        TxDirection.REFUND, TxDirection.INCOME -> "+"
        TxDirection.NEUTRAL -> ""
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (transaction.overLimit) DangerBackground else Color.Transparent)
            .clickable(onClickLabel = "查看交易详情", role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(category?.emoji ?: "❓")
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (transaction.overLimit) Danger else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = if (transaction.overLimit) Danger else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            // 符号统一由方向前缀决定，金额本身只取绝对值
            text = prefix + formatCents(kotlin.math.abs(transaction.amountCents)),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
        )
    }
}
