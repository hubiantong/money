package top.tongtong.money.ui.screens.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.Divider
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.Pill
import top.tongtong.money.ui.components.formatCents
import top.tongtong.money.ui.components.formatMonthLabel
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.DangerBackground
import top.tongtong.money.ui.theme.Muted
import top.tongtong.money.ui.theme.SavingsBlue
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ProfileScreen(
    container: AppContainer,
    onOpenBudget: () -> Unit,
    onOpenSavings: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenMerchantRules: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = appViewModel { ProfileViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportBackup(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.readBackupForRestore(uri)
    }

    LaunchedEffect(state.backupMessage, state.backupError) {
        val text = state.backupMessage ?: state.backupError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeBackupResult()
    }

    fun startExport() {
        if (state.exportRunning) return
        val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        exportLauncher.launch("潼潼的账本-备份-$stamp.json")
    }

    // 恢复前二次确认：展示备份摘要，说明合并策略与密钥不含在内
    state.restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRestorePreview,
            title = { Text("恢复这份备份？") },
            text = {
                Text(
                    "备份版本 v${preview.version}，包含 ${preview.categoryCount} 个分类、" +
                        "${preview.transactionCount} 笔交易、${preview.ruleCount} 条商家规则。\n\n" +
                        "同名分类会合并，重复交易自动跳过；邮箱授权码与解压码不在备份内，" +
                        "恢复后需重新配置邮箱导入。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) { Text("开始恢复") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestorePreview) { Text("取消") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("我的", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "${formatMonthLabel(state.monthKey)} · 本地记账，安全可靠",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                MoneyCard {
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("本月生活费预算", color = Muted, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = state.budgetCents?.let { formatCents(it) } ?: "未设置",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("储蓄账户", color = SavingsBlue, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = formatCents(state.savingsBalanceCents),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = SavingsBlue,
                            )
                        }
                    }
                }
            }

            item {
                MoneyCard(contentPadding = PaddingValues(0.dp)) {
                    ProfileRow(
                        emoji = "🎯",
                        title = "预算与配额",
                        subtitle = "${state.categoryCount} 个分类 · 单日 / 单周 / 单月配额",
                        onClick = onOpenBudget,
                    )
                    Divider(Modifier.padding(horizontal = 16.dp))
                    ProfileRow(
                        emoji = "🏦",
                        title = "储蓄账户",
                        subtitle = "结余结转与跨类/同类手续费明细",
                        onClick = onOpenSavings,
                    )
                    Divider(Modifier.padding(horizontal = 16.dp))
                    ProfileRow(
                        emoji = "📥",
                        title = "微信账单导入",
                        subtitle = if (state.emailEnabled) "邮箱自动同步已开启" else "邮箱自动同步未开启",
                        badge = state.pendingImports.takeIf { it > 0 }?.let { "$it 待处理" },
                        onClick = onOpenImport,
                    )
                    Divider(Modifier.padding(horizontal = 16.dp))
                    ProfileRow(
                        emoji = "🏷️",
                        title = "商家分类规则",
                        subtitle = "已设置 ${state.ruleCount} 条规则",
                        onClick = onOpenMerchantRules,
                    )
                    Divider(Modifier.padding(horizontal = 16.dp))
                    ProfileRow(
                        emoji = "🔒",
                        title = "设置与安全",
                        subtitle = if (state.lockEnabled) "应用锁已开启" else "应用锁未开启",
                        badge = state.updateVersionName?.let { "v$it" },
                        onClick = onOpenSettings,
                    )
                    Divider(Modifier.padding(horizontal = 16.dp))
                    ProfileRow(
                        emoji = "📤",
                        title = "导出备份",
                        subtitle = if (state.exportRunning) "正在导出…" else "交易、分类、规则保存为 JSON",
                        onClick = { startExport() },
                    )
                    Divider(Modifier.padding(horizontal = 16.dp))
                    ProfileRow(
                        emoji = "📥",
                        title = "导入备份",
                        subtitle = if (state.restoring) "正在恢复…" else "从备份 JSON 合并恢复数据",
                        onClick = {
                            if (!state.restoring) {
                                importLauncher.launch(arrayOf("application/json", "text/plain"))
                            }
                        },
                    )
                }
            }

            item {
                MoneyCard {
                    Text("关于潼潼的账本", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "账单数据、配额、结余与储蓄全部保存在本机；超额消费会标红提醒，" +
                            "每月结束后剩余额度自动结转储蓄账户。" +
                            "建议定期「导出备份」保存 JSON 文件，换机或数据丢失时可用「导入备份」恢复。",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    emoji: String,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        if (badge != null) {
            Pill(badge, Danger, DangerBackground)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Muted,
        )
    }
}
