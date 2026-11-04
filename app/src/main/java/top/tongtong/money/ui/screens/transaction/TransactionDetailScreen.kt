package top.tongtong.money.ui.screens.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.local.SurplusUsageEntity
import top.tongtong.money.data.local.TransactionEntity
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.data.local.TxSource
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.Divider
import top.tongtong.money.ui.components.InfoRow
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyTopBar
import top.tongtong.money.ui.components.Pill
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.components.formatFullDateTime
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.DangerBackground
import top.tongtong.money.ui.theme.Muted

@Composable
fun TransactionDetailScreen(
    container: AppContainer,
    transactionId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val viewModel = appViewModel { TransactionDetailViewModel(it, transactionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    // 删除/改分类失败时给出可见反馈，避免静默失败
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MoneyTopBar(
                title = "记录详情",
                onBack = onBack,
                actions = {
                    if (state.transaction != null) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val transaction = state.transaction
        if (transaction == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("记录不存在或加载中…", color = Muted)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AmountCard(transaction, state.categories[transaction.categoryId]?.name ?: "未分类")

            MoneyCard {
                Text("账单信息", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                InfoRow("商家", transaction.merchant.ifBlank { "—" })
                if (transaction.product.isNotBlank()) {
                    InfoRow("商品", transaction.product)
                }
                InfoRow("时间", formatFullDateTime(transaction.occurredAt))
                InfoRow("备注", transaction.note.ifBlank { "—" })
                InfoRow("来源", if (transaction.source == TxSource.WECHAT) "微信账单导入" else "手动记账")
                if (transaction.status.isNotBlank()) InfoRow("状态", transaction.status)
                if (transaction.externalId != null) {
                    InfoRow("交易单号", transaction.externalId)
                }
            }

            MoneyCard {
                Text("分类", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(
                            text = state.categories[transaction.categoryId]?.let {
                                "${it.emoji} ${it.name}"
                            } ?: "未分类",
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        state.categories.values
                            .filter { !it.archived }
                            .sortedBy { it.sortOrder }
                            .forEach { category ->
                                DropdownMenuItem(
                                    text = { Text("${category.emoji} ${category.name}") },
                                    onClick = {
                                        expanded = false
                                        viewModel.changeCategory(category.id)
                                    },
                                )
                            }
                    }
                }
                if (transaction.merchant.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("记住该商家分类", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "后续导入相同商家自动归类",
                                color = Muted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Switch(
                            checked = state.rememberMerchant,
                            onCheckedChange = viewModel::setRememberMerchant,
                        )
                    }
                }
            }

            if (transaction.direction == TxDirection.EXPENSE) {
                MoneyCard {
                    Text("抵扣明细", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    InfoRow("配额抵扣", formatCents(transaction.quotaCoveredCents))
                    InfoRow("同类结余", formatCents(transaction.sameSurplusCents))
                    InfoRow("跨类结余", formatCents(transaction.crossSurplusCents))
                    InfoRow("抵扣手续费", formatCents(transaction.feeCents))
                    InfoRow(
                        "未抵扣（超支）",
                        formatCents(transaction.uncoveredCents),
                        valueColor = if (transaction.uncoveredCents > 0) Danger else MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.usages.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Divider()
                        Spacer(Modifier.height(6.dp))
                        Text("结余来源", color = Muted, style = MaterialTheme.typography.labelMedium)
                        state.usages.forEach { usage ->
                            UsageRow(usage, state.categories[usage.grantCategoryId]?.name ?: "分类")
                        }
                    }
                    if (transaction.overLimit) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "本笔记录已超出周期配额",
                            color = Danger,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("删除这笔记录", color = Danger)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除记录") },
            text = { Text("删除后会重新计算相关月份的配额、结余与储蓄，确定删除吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                ) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AmountCard(transaction: TransactionEntity, categoryName: String) {
    MoneyCard(
        containerColor = if (transaction.overLimit) DangerBackground else MaterialTheme.colorScheme.surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(categoryName, color = Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            when (transaction.direction) {
                TxDirection.EXPENSE -> Pill("支出", Danger, DangerBackground)
                TxDirection.REFUND -> Pill("退款", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                TxDirection.INCOME -> Pill("收入", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                TxDirection.NEUTRAL -> Pill("不计收支", Muted, MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        val prefix = when (transaction.direction) {
            TxDirection.EXPENSE -> "-"
            TxDirection.REFUND, TxDirection.INCOME -> "+"
            TxDirection.NEUTRAL -> ""
        }
        Text(
            text = prefix + formatCents(transaction.amountCents).removePrefix("-"),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = if (transaction.overLimit) Danger else MaterialTheme.colorScheme.onSurface,
        )
        if (transaction.overLimit) {
            Text("已超出周期配额，注意控制消费", color = Danger, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UsageRow(usage: SurplusUsageEntity, sourceCategory: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$sourceCategory 结余" + if (usage.crossCategory) "（跨类）" else "",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatCents(usage.amountCents) +
                if (usage.feeCents > 0) " · 手续费 ${formatCents(usage.feeCents)}" else "",
            style = MaterialTheme.typography.labelMedium,
            color = Muted,
        )
    }
}
