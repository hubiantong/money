package top.tongtong.money.ui.screens.savings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.local.SavingsEntryEntity
import top.tongtong.money.data.local.SavingsType
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.EmptyState
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyTopBar
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.components.formatDateTime
import top.tongtong.money.ui.components.formatMonthLabel
import top.tongtong.money.ui.components.formatSignedCents
import top.tongtong.money.ui.theme.Muted
import top.tongtong.money.ui.theme.SavingsBlue

@Composable
fun SavingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = appViewModel { SavingsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { MoneyTopBar(title = "储蓄账户", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MoneyCard(containerColor = SavingsBlue.copy(alpha = 0.12f)) {
                    Text("累计储蓄", color = SavingsBlue, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatCents(state.balanceCents),
                        style = MaterialTheme.typography.headlineLarge,
                        color = SavingsBlue,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "储蓄由月末清账自动生成：周期结余与跨类手续费在月末统一转入，" +
                            "不参与后续消费抵扣，也不支持手动存取。",
                        color = Muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (state.groups.isEmpty()) {
                item { EmptyState("还没有结余转入，继续保持不超支吧") }
            } else {
                state.groups.forEach { group ->
                    item(key = "month-${group.monthKey}") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatMonthLabel(group.monthKey),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatSignedCents(group.totalCents),
                                style = MaterialTheme.typography.labelLarge,
                                color = SavingsBlue,
                            )
                        }
                    }
                    items(group.entries, key = { it.id }) { entry ->
                        SavingsRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavingsRow(entry: SavingsEntryEntity) {
    MoneyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(typeLabel(entry.type), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = entry.note.ifBlank { formatDateTime(entry.createdAt) },
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = formatSignedCents(entry.amountCents),
                color = SavingsBlue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun typeLabel(type: SavingsType): String = when (type) {
    SavingsType.SURPLUS_ROLLOVER -> "结余结转"
    SavingsType.MONTHLY_QUOTA_ROLLOVER -> "月度配额结余"
    SavingsType.CROSS_FEE -> "跨类抵扣手续费"
}
