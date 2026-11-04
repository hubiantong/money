package top.tongtong.money.ui.screens.transaction

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.local.TxDirection
import top.tongtong.money.data.repo.SurplusSourceOption
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.ledger.ExpenseAllocation
import top.tongtong.money.domain.ledger.SurplusSourceRef
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.Divider
import top.tongtong.money.ui.components.InfoRow
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyTopBar
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.DangerBackground
import top.tongtong.money.ui.theme.Muted
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@Composable
fun AddTransactionScreen(
    container: AppContainer,
    transactionId: Long,
    onDone: () -> Unit,
) {
    val viewModel = appViewModel { AddTransactionViewModel(it, transactionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var confirmDiscard by remember { mutableStateOf(false) }

    fun requestExit() {
        // 保存已完成时直接退出，不再把基线差异当作未保存修改
        if (!state.saved && viewModel.isDirty(state)) {
            confirmDiscard = true
        } else {
            onDone()
        }
    }

    // 系统返回手势/按键与顶栏返回共用同一确认逻辑；保存中直接拦截不退出
    BackHandler(enabled = !state.saved) {
        if (!state.saving) requestExit()
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的内容？") },
            text = { Text("已填写的记账内容尚未保存，放弃后将无法找回。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDone()
                    },
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            },
        )
    }

    Scaffold(
        topBar = {
            MoneyTopBar(
                title = if (state.editing) "编辑记录" else "记一笔",
                // 保存进行中不退出，避免中断写入
                onBack = { if (!state.saving) requestExit() },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.imePadding(),
            ) {
                Column {
                    state.error?.let { message ->
                        Text(
                            text = message,
                            color = Danger,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    Button(
                        onClick = viewModel::save,
                        enabled = !state.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .height(50.dp),
                    ) {
                        Text(if (state.saving) "保存中…" else "保存")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    TxDirection.EXPENSE to "支出",
                    TxDirection.INCOME to "收入",
                    TxDirection.REFUND to "退款",
                    TxDirection.NEUTRAL to "不计收支",
                ).forEach { (direction, label) ->
                    FilterChip(
                        selected = state.direction == direction,
                        onClick = { viewModel.setDirection(direction) },
                        label = { Text(label) },
                    )
                }
            }

            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::setAmount,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.headlineMedium,
                leadingIcon = { Text("¥", style = MaterialTheme.typography.titleLarge) },
                placeholder = { Text("0.00", color = Muted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            // 不计收支不参与配额与统计，无需选择分类
            if (state.direction != TxDirection.NEUTRAL) {
                Column {
                    Text("分类", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.categories, key = { it.id }) { category ->
                            FilterChip(
                                selected = state.categoryId == category.id,
                                onClick = { viewModel.setCategory(category.id) },
                                label = { Text("${category.emoji} ${category.name}") },
                            )
                        }
                    }
                }
            }

            if (state.direction == TxDirection.EXPENSE && state.surplusSources.isNotEmpty()) {
                Column {
                    Text("超额资金来源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "不勾选时按默认顺序自动抵扣；勾选后只使用所选来源，同类抵扣费率更低。",
                        color = Muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    state.surplusSources.forEach { option ->
                        val ref = SurplusSourceRef(option.categoryId, option.sourcePeriodStart)
                        val checked = state.selectedSources.contains(ref)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleSurplusSource(ref) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.toggleSurplusSource(ref) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${option.categoryEmoji} ${option.categoryName}" +
                                        if (option.sameCategory) "（同类）" else "（跨类）",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${sourcePeriodLabel(option, zone)} · 剩余 " +
                                        formatCents(option.remainingCents),
                                    color = Muted,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            Column {
                Text("时间", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val zoned = Instant.ofEpochMilli(state.occurredAt).atZone(zone)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val initial = Instant.ofEpochMilli(state.occurredAt).atZone(zone)
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    val picked = ZonedDateTime.of(
                                        year,
                                        month + 1,
                                        day,
                                        initial.hour,
                                        initial.minute,
                                        0,
                                        0,
                                        zone,
                                    ).toInstant().toEpochMilli()
                                    viewModel.setOccurredAt(picked)
                                },
                                initial.year,
                                initial.monthValue - 1,
                                initial.dayOfMonth,
                            ).show()
                        },
                    ) {
                        Text("${zoned.monthValue}月${zoned.dayOfMonth}日")
                    }
                    OutlinedButton(
                        onClick = {
                            val initial = Instant.ofEpochMilli(state.occurredAt).atZone(zone)
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    val date = Instant.ofEpochMilli(state.occurredAt)
                                        .atZone(zone)
                                        .toLocalDate()
                                    val picked = date.atTime(hour, minute)
                                        .atZone(zone)
                                        .toInstant()
                                        .toEpochMilli()
                                    viewModel.setOccurredAt(picked)
                                },
                                initial.hour,
                                initial.minute,
                                DateFormat.is24HourFormat(context),
                            ).show()
                        },
                    ) {
                        Text(String.format(Locale.CHINA, "%02d:%02d", zoned.hour, zoned.minute))
                    }
                    TextButton(onClick = { viewModel.setOccurredAt(System.currentTimeMillis()) }) {
                        Text("现在")
                    }
                }
            }

            OutlinedTextField(
                value = state.merchant,
                onValueChange = viewModel::setMerchant,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("商家 / 交易对方") },
                singleLine = true,
            )

            OutlinedTextField(
                value = state.payMethod,
                onValueChange = viewModel::setPayMethod,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("支付方式") },
                placeholder = { Text("例如 零钱 / 招商银行 / 现金", color = Muted) },
                singleLine = true,
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注") },
                minLines = 2,
            )

            if (state.direction != TxDirection.NEUTRAL &&
                state.merchant.isNotBlank() &&
                state.categoryId != null
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("记住该商家分类", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "下次导入相同商家时自动归类",
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

            if (state.direction == TxDirection.EXPENSE) {
                state.preview?.let {
                    PreviewCard(allocation = it, manualSources = state.selectedSources.isNotEmpty())
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(allocation: ExpenseAllocation, manualSources: Boolean) {
    MoneyCard(
        containerColor = if (allocation.overLimit) DangerBackground else MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = "实时试算",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        if (manualSources) {
            Text(
                "按已勾选的资金来源抵扣",
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
        }
        InfoRow("配额抵扣", formatCents(allocation.quotaCoveredCents))
        InfoRow(
            "同类结余抵扣",
            if (allocation.sameSurplusCents > 0 && allocation.sameFeeCents > 0) {
                "${formatCents(allocation.sameSurplusCents)}（手续费 ${formatCents(allocation.sameFeeCents)}）"
            } else {
                formatCents(allocation.sameSurplusCents)
            },
        )
        InfoRow(
            "跨类结余抵扣",
            if (allocation.crossSurplusCents > 0) {
                "${formatCents(allocation.crossSurplusCents)}（手续费 ${formatCents(allocation.crossFeeCents)}）"
            } else {
                formatCents(0)
            },
        )
        InfoRow("未抵扣（超支）", formatCents(allocation.uncoveredCents))
        Divider()
        if (allocation.overLimit) {
            Text(
                "本笔入账后超出周期配额，将标红提醒",
                color = Danger,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "本笔在配额范围内",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun sourcePeriodLabel(option: SurplusSourceOption, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(option.sourcePeriodStart).atZone(zone).toLocalDate()
    val end = Instant.ofEpochMilli(option.sourcePeriodEnd - 1).atZone(zone).toLocalDate()
    return if (start == end) {
        "${start.monthValue}月${start.dayOfMonth}日"
    } else {
        "${start.monthValue}月${start.dayOfMonth}日-${end.monthValue}月${end.dayOfMonth}日"
    }
}
