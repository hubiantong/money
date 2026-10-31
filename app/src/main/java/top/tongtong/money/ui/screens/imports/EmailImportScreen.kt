package top.tongtong.money.ui.screens.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.local.CandidateStatus
import top.tongtong.money.data.local.ImportCandidateEntity
import top.tongtong.money.data.local.ImportLogEntity
import top.tongtong.money.data.local.ImportStatus
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.EmptyState
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyTopBar
import top.tongtong.money.ui.components.Pill
import top.tongtong.money.ui.components.SectionTitle
import top.tongtong.money.ui.components.formatDateTime
import top.tongtong.money.ui.components.formatFullDateTime
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.DangerBackground
import top.tongtong.money.ui.theme.Mint
import top.tongtong.money.ui.theme.MintDark
import top.tongtong.money.ui.theme.Muted

@Composable
fun EmailImportScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = appViewModel { ImportViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importUri(uri)
    }

    Scaffold(
        topBar = { MoneyTopBar(title = "微信账单导入", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MoneyCard {
                    Text("邮箱自动导入", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = viewModel::setHost,
                        label = { Text("IMAP 服务器") },
                        placeholder = { Text("imap.qq.com / imap.163.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.port,
                            onValueChange = viewModel::setPort,
                            label = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(110.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("SSL 加密", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = state.ssl, onCheckedChange = viewModel::setSsl)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.user,
                        onValueChange = viewModel::setUser,
                        label = { Text("邮箱账号") },
                        placeholder = { Text("例如 3478584509@qq.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.authCode,
                        onValueChange = viewModel::setAuthCode,
                        label = { Text("IMAP 授权码") },
                        placeholder = {
                            Text(if (state.hasAuthCode) "已加密保存，留空则继续使用" else "QQ/163 邮箱设置中生成的授权码")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.folder,
                        onValueChange = viewModel::setFolder,
                        label = { Text("邮箱文件夹") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.fetchDays,
                            onValueChange = viewModel::setFetchDays,
                            label = { Text("拉取最近（天）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.fetchLimit,
                            onValueChange = viewModel::setFetchLimit,
                            label = { Text("最多（封）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::saveConfig,
                            modifier = Modifier.weight(1f),
                        ) { Text("保存配置") }
                        OutlinedButton(
                            onClick = viewModel::testConnection,
                            enabled = !state.testing,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (state.testing) "测试中…" else "测试连接") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("每小时自动同步", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "仅在已启用且联网时检查新账单邮件",
                                color = Muted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.lastSync > 0) {
                                "上次同步：${formatDateTime(state.lastSync)}"
                            } else {
                                "尚未同步"
                            },
                            color = Muted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = viewModel::syncNow,
                            enabled = !state.syncing && state.hasAuthCode,
                        ) { Text(if (state.syncing) "同步中…" else "立即同步") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "微信账单邮件的下载链接 7 天内有效；解压码由「微信支付」公众号单独推送。",
                        color = Muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            state.message?.let { message ->
                item {
                    MoneyCard(containerColor = Mint.copy(alpha = 0.12f)) {
                        Text(message, color = MintDark, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            state.error?.let { message ->
                item {
                    MoneyCard(containerColor = DangerBackground) {
                        Text(message, color = Danger, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item { SectionTitle("账单邮件") }

            if (state.candidates.isEmpty()) {
                item { EmptyState("还没有收到账单邮件，可先手动导入") }
            } else {
                // 候选与日志是两个表，自增 id 会重复，key 必须加前缀
                items(state.candidates, key = { "candidate-${it.id}" }) { candidate ->
                    CandidateCard(candidate, onPassword = viewModel::requestPassword)
                }
            }

            item {
                MoneyCard {
                    Text("手动导入", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "支持 .eml 邮件、加密 .zip、.xlsx 与 .csv 账单文件。加密压缩包会提示输入解压码。",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            fileLauncher.launch(
                                arrayOf(
                                    "message/rfc822",
                                    "application/zip",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "text/csv",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        enabled = !state.importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.importing) "正在导入…" else "选择账单文件")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("本机记住解压码", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "使用加密存储保存，方便下次自动解压",
                                color = Muted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Switch(
                            checked = state.rememberZipPassword,
                            onCheckedChange = viewModel::setRememberZipPassword,
                        )
                    }
                }
            }

            item { SectionTitle("导入日志") }

            if (state.logs.isEmpty()) {
                item { EmptyState("暂无导入记录") }
            } else {
                items(state.logs, key = { "log-${it.id}" }) { log ->
                    ImportLogRow(log)
                }
            }
        }
    }

    val prompt = state.passwordPrompt
    if (prompt != null) {
        var password by remember(prompt.candidateId, prompt.title, prompt.error) {
            mutableStateOf("")
        }
        AlertDialog(
            onDismissRequest = {
                if (!state.importing) viewModel.dismissPasswordPrompt()
            },
            title = { Text("输入账单解压码") },
            text = {
                Column {
                    Text(
                        text = prompt.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("解压码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (prompt.error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = prompt.error,
                            // 首次提示"账单已加密"属于说明信息，不应显示为错误色
                            color = if (prompt.candidateId == null && prompt.error.startsWith("账单已加密")) {
                                Muted
                            } else {
                                Danger
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "本机记住解压码",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state.rememberZipPassword,
                            onCheckedChange = viewModel::setRememberZipPassword,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.submitPassword(password) },
                    enabled = !state.importing,
                ) { Text(if (state.importing) "导入中…" else "导入") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissPasswordPrompt,
                    enabled = !state.importing,
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CandidateCard(
    candidate: ImportCandidateEntity,
    onPassword: (Long) -> Unit,
) {
    MoneyCard(
        containerColor = when (candidate.status) {
            CandidateStatus.EXPIRED, CandidateStatus.FAILED -> DangerBackground
            else -> MaterialTheme.colorScheme.surface
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = candidate.subject,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            CandidateStatusPill(candidate.status)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "收件时间 ${formatDateTime(candidate.receivedAt)}",
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
        )
        candidate.error?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                color = if (candidate.status == CandidateStatus.PENDING_PASSWORD) Muted else Danger,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (candidate.status == CandidateStatus.PENDING_PASSWORD) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { onPassword(candidate.id) }) {
                Text("输入解压码")
            }
        }
    }
}

@Composable
private fun CandidateStatusPill(status: CandidateStatus) {
    when (status) {
        CandidateStatus.PENDING_PASSWORD -> Pill(
            "待解压码",
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
        )
        CandidateStatus.READY -> Pill("待导入", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
        CandidateStatus.IMPORTED -> Pill("已导入", MintDark, Mint.copy(alpha = 0.16f))
        CandidateStatus.EXPIRED -> Pill("链接过期", Danger, DangerBackground)
        CandidateStatus.FAILED -> Pill("失败", Danger, DangerBackground)
    }
}

@Composable
private fun ImportLogRow(log: ImportLogEntity) {
    MoneyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.fileName.ifBlank { "账单导入" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatDateTime(log.createdAt)} · 新增 ${log.importedRows} / 重复 ${log.duplicateRows}",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (log.message.isNotBlank()) {
                    Text(
                        text = log.message,
                        color = Muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            when (log.status) {
                ImportStatus.SUCCESS -> Pill("成功", MintDark, Mint.copy(alpha = 0.16f))
                ImportStatus.PARTIAL -> Pill("部分", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                ImportStatus.FAILED -> Pill("失败", Danger, DangerBackground)
            }
        }
        val earliest = log.earliestAt
        val latest = log.latestAt
        if (earliest != null && latest != null) {
            Text(
                text = "账单区间 ${formatFullDateTime(earliest)} — ${formatFullDateTime(latest)}",
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}
