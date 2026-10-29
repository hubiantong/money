package top.tongtong.money.ui.screens.transaction

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.EmptyState
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MonthSwitcher
import top.tongtong.money.ui.components.TransactionRow
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.MintDark
import top.tongtong.money.ui.theme.Muted

@Composable
fun TransactionListScreen(
    container: AppContainer,
    initialCategoryId: Long? = null,
    initialMonthKey: String? = null,
    onOpenTransaction: (Long) -> Unit,
) {
    val viewModel = appViewModel {
        TransactionsViewModel(it, initialCategoryId, initialMonthKey)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 分类筛选只依赖分类快照，避免每次重组都重新过滤排序
    val visibleCategories = remember(state.categories) {
        state.categories.values.filter { !it.archived }.sortedBy { it.sortOrder }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "明细",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp),
        )
        MonthSwitcher(
            monthKey = state.monthKey,
            onShift = { viewModel.shiftMonth(it.toLong()) },
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            placeholder = { Text("搜索商家 / 商品 / 备注", color = Muted) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Muted) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清除搜索", tint = Muted)
                    }
                }
            },
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MoneyCard(modifier = Modifier.weight(1f)) {
                Text("支出", color = Muted, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = formatCents(state.expenseCents),
                    style = MaterialTheme.typography.titleMedium,
                    color = Danger,
                )
            }
            MoneyCard(modifier = Modifier.weight(1f)) {
                Text("收入", color = Muted, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = formatCents(state.incomeCents),
                    style = MaterialTheme.typography.titleMedium,
                    color = MintDark,
                )
            }
            MoneyCard(modifier = Modifier.weight(1f)) {
                Text("退款", color = Muted, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = formatCents(state.refundCents),
                    style = MaterialTheme.typography.titleMedium,
                    color = MintDark,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.direction == null,
                onClick = { viewModel.setDirection(null) },
                label = { Text("全部收支") },
            )
            listOf(
                TxDirection.EXPENSE to "支出",
                TxDirection.INCOME to "收入",
                TxDirection.REFUND to "退款",
            ).forEach { (direction, label) ->
                FilterChip(
                    selected = state.direction == direction,
                    onClick = {
                        viewModel.setDirection(if (state.direction == direction) null else direction)
                    },
                    label = { Text(label) },
                )
            }
        }

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.categoryId == null,
                onClick = { viewModel.setCategory(null) },
                label = { Text("全部分类") },
            )
            visibleCategories.forEach { category ->
                FilterChip(
                    selected = state.categoryId == category.id,
                    onClick = {
                        viewModel.setCategory(if (state.categoryId == category.id) null else category.id)
                    },
                    label = { Text("${category.emoji} ${category.name}") },
                )
            }
        }

        if (state.groups.isEmpty() && state.isLoading) {
            Text(
                text = "加载中…",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else if (state.groups.isEmpty()) {
            EmptyState("这个月还没有符合条件的记录", modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.groups.forEach { group ->
                    item(key = "day-${group.date}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = Muted,
                                modifier = Modifier.weight(1f),
                            )
                            if (group.expenseCents > 0) {
                                Text(
                                    text = "支出 ${formatCents(group.expenseCents)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Muted,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (group.incomeCents > 0) {
                                Text(
                                    text = "收入 ${formatCents(group.incomeCents)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MintDark,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (group.refundCents > 0) {
                                Text(
                                    text = "退款 ${formatCents(group.refundCents)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MintDark,
                                )
                            }
                        }
                    }
                    items(group.transactions, key = { it.id }) { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            category = state.categories[transaction.categoryId],
                            onClick = { onOpenTransaction(transaction.id) },
                        )
                    }
                }
            }
        }
    }
}
