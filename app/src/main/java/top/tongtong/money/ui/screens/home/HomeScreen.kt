package top.tongtong.money.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.CategoryAvatar
import top.tongtong.money.ui.components.EmptyState
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyProgress
import top.tongtong.money.ui.components.Pill
import top.tongtong.money.ui.components.SectionTitle
import top.tongtong.money.ui.components.TransactionRow
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.components.formatMonthLabel
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.DangerBackground
import top.tongtong.money.ui.theme.Mint
import top.tongtong.money.ui.theme.MintDark
import top.tongtong.money.ui.theme.Muted
import top.tongtong.money.ui.theme.SavingsBlue

@Composable
fun HomeScreen(
    container: AppContainer,
    onAdd: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onOpenSavings: () -> Unit,
    onOpenBudget: () -> Unit,
    onViewAll: () -> Unit,
) {
    val viewModel = appViewModel { HomeViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        text = "潼潼的账本",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "${formatMonthLabel(state.monthKey)} · 每一笔都算数",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                BudgetCard(
                    budgetCents = state.budgetCents,
                    spentCents = state.spentThisMonthCents,
                    incomeCents = state.incomeThisMonthCents,
                    overBudget = state.overBudget,
                    onOpenBudget = onOpenBudget,
                )
            }

            item {
                SavingsCard(
                    balanceCents = state.savingsBalanceCents,
                    monthCents = state.monthSavingsCents,
                    onClick = onOpenSavings,
                )
            }

            item {
                SectionTitle(
                    text = "分类配额",
                    trailing = {
                        TextButton(onClick = onOpenBudget) { Text("管理") }
                    },
                )
            }

            if (state.quotaItems.isEmpty()) {
                item {
                    MoneyCard {
                        Text("还没有设置分类配额", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "为餐饮、交通等分类设置单日 / 单周 / 单月额度后，超额会自动标红。",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onOpenBudget) { Text("去设置配额") }
                    }
                }
            } else {
                items(state.quotaItems, key = { "quota-${it.category.id}" }) { item ->
                    QuotaCard(item)
                }
            }

            item {
                SectionTitle(
                    text = "最近消费",
                    trailing = {
                        TextButton(onClick = onViewAll) { Text("查看全部") }
                    },
                )
            }

            if (state.recent.isEmpty()) {
                item { EmptyState("还没有记账，点右下角记下第一笔吧") }
            } else {
                items(state.recent, key = { "tx-${it.id}" }) { transaction ->
                    TransactionRow(
                        transaction = transaction,
                        category = state.categories[transaction.categoryId],
                        onClick = { onOpenTransaction(transaction.id) },
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("记一笔") },
        )
    }
}

@Composable
private fun BudgetCard(
    budgetCents: Long?,
    spentCents: Long,
    incomeCents: Long,
    overBudget: Boolean,
    onOpenBudget: () -> Unit,
) {
    if (budgetCents == null) {
        MoneyCard {
            Text("本月生活费预算", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("还没有设置月度预算", color = Muted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenBudget) { Text("去设置") }
        }
        return
    }
    val remaining = budgetCents - spentCents
    MoneyCard(
        containerColor = if (overBudget) DangerBackground else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.clickable(onClick = onOpenBudget),
    ) {
        Text(
            text = if (overBudget) "本月已超支" else "本月可用预算",
            style = MaterialTheme.typography.bodyMedium,
            color = if (overBudget) Danger else MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (overBudget) "超出 ${formatCents(-remaining)}" else formatCents(remaining),
            style = MaterialTheme.typography.headlineMedium,
            color = if (overBudget) Danger else MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(10.dp))
        MoneyProgress(
            usedCents = spentCents.coerceAtLeast(0),
            quotaCents = budgetCents,
            over = overBudget,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "已用 ${formatCents(spentCents.coerceAtLeast(0))}",
                color = if (overBudget) Danger else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (incomeCents > 0) {
                Text(
                    text = "收入 ${formatCents(incomeCents)}",
                    color = MintDark,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = "预算 ${formatCents(budgetCents)}",
                color = if (overBudget) Danger else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SavingsCard(balanceCents: Long, monthCents: Long, onClick: () -> Unit) {
    MoneyCard(
        containerColor = SavingsBlue.copy(alpha = 0.12f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("储蓄账户", style = MaterialTheme.typography.bodyMedium, color = SavingsBlue)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatCents(balanceCents),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SavingsBlue,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("本月转入", style = MaterialTheme.typography.labelMedium, color = Muted)
                Text(
                    text = formatCents(monthCents),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SavingsBlue,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun QuotaCard(item: QuotaItem) {
    MoneyCard(
        containerColor = if (item.overLimit) DangerBackground else MaterialTheme.colorScheme.surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryAvatar(item.category.emoji)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.overLimit) Danger else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${item.periodLabel} · ${item.periodText}",
                    color = if (item.overLimit) Danger else Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (item.overLimit) {
                Pill("已超额", Color.White, Danger)
            } else {
                Pill("正常", MaterialTheme.colorScheme.onSurface, Mint.copy(alpha = 0.22f))
            }
        }
        Spacer(Modifier.height(10.dp))
        MoneyProgress(
            usedCents = item.spentCents,
            quotaCents = item.quotaCents,
            over = item.overLimit,
        )
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "已用 ${formatCents(item.spentCents)} / ${formatCents(item.quotaCents)}",
                color = if (item.overLimit) Danger else Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (item.surplusCents > 0) {
                Text(
                    text = "结余 ${formatCents(item.surplusCents)}",
                    color = Mint,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (item.overLimit) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "超出 ${formatCents(item.spentCents - item.quotaCents)}，本周期消费已标红提醒",
                color = Danger,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
