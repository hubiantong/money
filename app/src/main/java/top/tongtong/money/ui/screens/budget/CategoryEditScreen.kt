package top.tongtong.money.ui.screens.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.local.PeriodType
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyTopBar
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.Muted

private val EMOJI_OPTIONS = listOf(
    "💸", "🍚", "🍜", "🍎", "☕", "🛍️", "🚌", "🚗", "⛽", "🏠",
    "💡", "📱", "💻", "🎮", "🎬", "📚", "💊", "🏥", "🐱", "🐾",
    "🎁", "✈️", "⚽", "🔧", "💧", "👶", "💇", "🧾", "❓",
)

private val PERIOD_OPTIONS = listOf(
    PeriodType.DAILY to "单日",
    PeriodType.WEEKLY to "单周",
    PeriodType.MONTHLY to "单月",
)

@Composable
fun CategoryEditScreen(
    container: AppContainer,
    categoryId: Long,
    onDone: () -> Unit,
) {
    val viewModel = appViewModel { CategoryEditViewModel(it, categoryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmArchive by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Scaffold(
        topBar = {
            MoneyTopBar(
                title = when {
                    !state.loaded -> "分类"
                    state.existing == null && categoryId > 0 -> "分类不存在"
                    state.existing == null -> "新建分类"
                    else -> "编辑分类"
                },
                onBack = onDone,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.loaded && !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(50.dp),
                ) { Text("保存分类") }
            }
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("分类名称") },
                singleLine = true,
            )

            Column {
                Text("图标", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EMOJI_OPTIONS.forEach { emoji ->
                        FilterChip(
                            selected = state.emoji == emoji,
                            onClick = { viewModel.setEmoji(emoji) },
                            label = { Text(emoji) },
                        )
                    }
                }
            }

            Column {
                Text("颜色", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CATEGORY_COLOR_OPTIONS.forEach { argb ->
                        val selected = state.colorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { viewModel.setColorArgb(argb) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选择",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
            }

            Column {
                Text("配额周期", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PERIOD_OPTIONS.forEach { (type, label) ->
                        FilterChip(
                            selected = state.periodType == type,
                            onClick = { viewModel.setPeriodType(type) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.quotaText,
                onValueChange = viewModel::setQuotaText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("周期配额（元）") },
                placeholder = { Text("留空表示不设配额") },
                supportingText = { Text("达到周期配额后，该分类新记录会自动标红") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            OutlinedTextField(
                value = state.feePercentText,
                onValueChange = viewModel::setFeePercentText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("跨类结余抵扣手续费（%）") },
                supportingText = { Text("其他分类的结余抵扣本类消费时，按此比例扣费并转入储蓄") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            OutlinedTextField(
                value = state.sameFeePercentText,
                onValueChange = viewModel::setSameFeePercentText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("同类结余抵扣手续费（%）") },
                supportingText = { Text("本分类的结余抵扣本类消费时按此比例扣费，通常低于跨类费率") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            state.error?.let { message ->
                Text(message, color = Danger, style = MaterialTheme.typography.bodyMedium)
            }

            val existing = state.existing
            if (existing != null) {
                if (existing.archived) {
                    TextButton(onClick = viewModel::restore, modifier = Modifier.fillMaxWidth()) {
                        Text("恢复该分类")
                    }
                } else {
                    TextButton(
                        onClick = { confirmArchive = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("归档该分类", color = Danger)
                    }
                }
            }

            MoneyCard {
                Text("说明", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "配额变更后会从当前月份重新计算，历史月份不受影响。已产生的结余会在月末统一转入储蓄。",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("归档分类") },
            text = { Text("归档后该分类不再参与配额计算，历史记录仍保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmArchive = false
                        viewModel.archive()
                    },
                ) { Text("归档", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }) { Text("取消") }
            },
        )
    }
}
