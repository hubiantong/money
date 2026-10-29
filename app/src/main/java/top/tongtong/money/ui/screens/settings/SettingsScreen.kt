package top.tongtong.money.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.Divider
import top.tongtong.money.ui.components.InfoRow
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyTopBar
import top.tongtong.money.ui.components.SectionTitle
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.MintDark
import top.tongtong.money.ui.theme.Muted

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPinDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "1.0" }
    }

    LaunchedEffect(state.message, state.error) {
        if (state.message != null || state.error != null) {
            // 提示位于列表顶部，动作多在下方，先滚动回顶部保证可见
            scrollState.animateScrollTo(0)
            delay(2_500)
            viewModel.clearFeedback()
        }
    }

    // 设置/修改 PIN 成功后再关闭对话框，失败时保留已输入内容
    LaunchedEffect(state.message, showPinDialog) {
        if (showPinDialog && state.message?.startsWith("PIN 已设置") == true) {
            showPinDialog = false
        }
    }

    Scaffold(
        topBar = { MoneyTopBar(title = "设置", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { message ->
                Text(message, color = MintDark, style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { message ->
                Text(message, color = Danger, style = MaterialTheme.typography.bodyMedium)
            }

            SectionTitle("安全")
            MoneyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("应用锁", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (state.pinSet) "离开 1 分钟后需要验证" else "先设置 6 位 PIN 才能开启",
                            color = Muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Switch(
                        checked = state.lockEnabled,
                        onCheckedChange = viewModel::setLockEnabled,
                        enabled = state.pinSet,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("生物识别解锁", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (state.lockEnabled) "使用指纹或面容快速解锁" else "开启应用锁后可用",
                            color = Muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = viewModel::setBiometricEnabled,
                        enabled = state.lockEnabled,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showPinDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.pinSet) "修改解锁 PIN" else "设置解锁 PIN")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "PIN 使用 PBKDF2 加盐哈希存储，邮箱授权码与解压码使用系统加密存储，账单数据仅保存在本机。",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            SectionTitle("默认规则")
            MoneyCard {
                Text("默认跨类手续费率", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "新建分类时预填该费率；不同分类的结余用于其他分类消费时，按消费分类的费率扣除手续费。",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                var feeInput by rememberSaveable(state.defaultFeeBp) {
                    mutableStateOf(state.defaultFeePercent)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = feeInput,
                        onValueChange = { feeInput = it },
                        label = { Text("费率") },
                        suffix = { Text("%") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { viewModel.setDefaultFee(feeInput) }) { Text("保存") }
                }
            }

            SectionTitle("关于")
            MoneyCard {
                InfoRow("应用版本", versionName)
                Divider()
                InfoRow("账单来源", "微信支付邮件 / 手动导入")
                Divider()
                InfoRow("结算规则", "每月结束后统一结转储蓄")
                Divider()
                InfoRow("数据存储", "仅保存在本机")
                Spacer(Modifier.height(6.dp))
                Text(
                    "所有周期结束后剩余的可用结余、月配额剩余，以及跨类消费手续费，都会自动转入储蓄账户累计。",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showPinDialog) {
        SetPinDialog(
            changing = state.pinSet,
            onDismiss = { showPinDialog = false },
            onConfirm = { oldPin, pin, confirm ->
                viewModel.setPin(oldPin, pin, confirm)
            },
        )
    }
}

@Composable
private fun SetPinDialog(
    changing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (oldPin: String, pin: String, confirm: String) -> Unit,
) {
    var oldPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val oldPinValid = !changing || (oldPin.length == 6 && oldPin.all { it.isDigit() })
    val valid = oldPinValid && pin.length == 6 && pin.all { it.isDigit() } && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changing) "修改解锁 PIN" else "设置解锁 PIN") },
        text = {
            Column {
                if (changing) {
                    OutlinedTextField(
                        value = oldPin,
                        onValueChange = { value ->
                            oldPin = value.filter { it.isDigit() }.take(6)
                        },
                        label = { Text("当前 PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter { it.isDigit() }.take(6)
                    },
                    label = { Text("6 位 PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { value ->
                        confirm = value.filter { it.isDigit() }.take(6)
                    },
                    label = { Text("再次输入 PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pin.isNotEmpty() && confirm.isNotEmpty() && pin != confirm) {
                    Spacer(Modifier.height(6.dp))
                    Text("两次输入的 PIN 不一致", color = Danger, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(oldPin, pin, confirm) },
                enabled = valid,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
