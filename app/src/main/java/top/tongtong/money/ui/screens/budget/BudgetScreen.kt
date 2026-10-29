package top.tongtong.money.ui.screens.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.CategoryAvatar
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyTopBar
import top.tongtong.money.ui.components.Pill
import top.tongtong.money.ui.components.SectionTitle
import top.tongtong.money.ui.components.centsToPlainYuan
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.components.parseYuanToCents
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.DangerBackground
import top.tongtong.money.ui.theme.Mint
import top.tongtong.money.ui.theme.Muted

@Composable
fun BudgetScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEditCategory: (Long) -> Unit,
) {
    val viewModel = appViewModel { BudgetViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingBudget by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { MoneyTopBar(title = "预算与配额", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MoneyCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "本月生活费总预算",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = state.budgetCents?.let { formatCents(it) } ?: "未设置",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        TextButton(onClick = { editingBudget = true }) { Text("修改") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "月度总预算独立于分类配额，用于整体把控；超出后会标红提醒。",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            item {
                MoneyCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "本月可用结余",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatCents(state.totalSurplusCents),
                            color = Mint,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "单日/单周未用完的额度会转为同分类结余，当月内可继续使用。",
                        color = Muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            item {
                SectionTitle(
                    text = "分类配额",
                    trailing = {
                        TextButton(onClick = { onEditCategory(0L) }) { Text("新增分类") }
                    },
                )
            }

            // 可排序视图：未归档、非系统分类按 sortOrder 排列，上移/下移只在这部分内交换。
            // 分类列表很小，直接计算即可；LazyListScope 内不能调用 remember（非 Composable 上下文）。
            val movable = state.categories.filter { !it.category.archived && !it.category.isSystem }
            items(state.categories, key = { it.category.id }) { item ->
                val movableIndex = movable.indexOfFirst { it.category.id == item.category.id }
                CategoryQuotaRow(
                    item = item,
                    onClick = { onEditCategory(item.category.id) },
                    onRestore = { viewModel.restore(item.category) },
                    onMoveUp = movableIndex.takeIf { it > 0 }
                        ?.let { { viewModel.moveCategory(item.category.id, -1) } },
                    onMoveDown = movableIndex.takeIf { it in 0 until movable.size - 1 }
                        ?.let { { viewModel.moveCategory(item.category.id, 1) } },
                )
            }

            item {
                MoneyCard {
                    Text("结余与储蓄规则", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "周期结束时未超支的额度会变成结余，优先抵扣同分类消费；抵扣其他分类按分类费率收取手续费。月末剩余结余与手续费都会转入储蓄账户。",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (editingBudget) {
        var budgetText by rememberSaveable { mutableStateOf(centsToPlainYuan(state.budgetCents)) }
        var budgetError by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editingBudget = false },
            title = { Text("设置月度总预算") },
            text = {
                Column {
                    OutlinedTextField(
                        value = budgetText,
                        onValueChange = {
                            budgetText = it
                            budgetError = false
                        },
                        label = { Text("金额（元）") },
                        singleLine = true,
                        isError = budgetError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (budgetError) "请输入有效金额（大于 0）" else "留空可取消月度预算",
                        color = if (budgetError) Danger else Muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsedCents = parseYuanToCents(budgetText)
                        // 留空表示取消预算；非空但无法解析时不提交，避免误清空
                        if (budgetText.isBlank() || parsedCents != null) {
                            viewModel.setBudget(parsedCents)
                            editingBudget = false
                        } else {
                            budgetError = true
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingBudget = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CategoryQuotaRow(
    item: BudgetCategoryItem,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val category = item.category
    val periodLabel = when (category.periodType) {
        PeriodType.DAILY -> "单日"
        PeriodType.WEEKLY -> "单周"
        PeriodType.MONTHLY -> "单月"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    category.archived -> MaterialTheme.colorScheme.surfaceVariant
                    item.overLimit -> DangerBackground
                    else -> MaterialTheme.colorScheme.surface
                },
            )
            .clickable(enabled = !category.archived, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(category.emoji)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.overLimit) Danger else MaterialTheme.colorScheme.onSurface,
                )
                if (category.isSystem) {
                    Spacer(Modifier.width(6.dp))
                    Pill("系统", Muted, MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            Text(
                text = buildString {
                    append(periodLabel)
                    append(" · ")
                    append(category.quotaCents?.let { formatCents(it) } ?: "未设置配额")
                    if (category.quotaCents != null) {
                        append(" · 已用 ")
                        append(formatCents(item.spentCents))
                    }
                },
                color = if (item.overLimit) Danger else Muted,
                style = MaterialTheme.typography.labelMedium,
            )
            if (item.surplusCents > 0) {
                Text(
                    "可用结余 ${formatCents(item.surplusCents)}",
                    color = Mint,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (item.overLimit) {
                Text("已超额，记录已标红", color = Danger, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (category.archived) {
            TextButton(onClick = onRestore) { Text("恢复") }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onMoveUp ?: {}, enabled = onMoveUp != null) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "上移分类",
                        tint = if (onMoveUp != null) Muted else Muted.copy(alpha = 0.3f),
                    )
                }
                IconButton(onClick = onMoveDown ?: {}, enabled = onMoveDown != null) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "下移分类",
                        tint = if (onMoveDown != null) Muted else Muted.copy(alpha = 0.3f),
                    )
                }
            }
            Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
        }
    }
}
