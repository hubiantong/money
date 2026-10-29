package top.tongtong.money.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.parseYuanToCents
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.Muted

/**
 * 首次启动引导：欢迎页 + 月度预算设置。完成时写入 onboarding_done，
 * MainActivity 根据该设置自动切回主界面，无需导航参与。
 */
@Composable
fun OnboardingScreen(container: AppContainer) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var budgetText by rememberSaveable { mutableStateOf("") }
    var budgetError by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun finish() {
        if (saving) return
        val cents = if (budgetText.isBlank()) null else parseYuanToCents(budgetText)
        if (budgetText.isNotBlank() && cents == null) {
            budgetError = true
            return
        }
        saving = true
        scope.launch {
            try {
                if (cents != null) {
                    container.categoryRepository.setMonthlyBudget(cents)
                }
                container.settingsRepository.setOnboardingDone(true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                saveError = exception.message ?: "保存失败，请重试"
                saving = false
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (page == 0) {
                Text("👋", fontSize = 44.sp)
                Spacer(Modifier.height(12.dp))
                Text("欢迎使用潼潼的账本", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text("本地记账 · 数据只存在这台手机上", color = Muted)
                Spacer(Modifier.height(20.dp))
                MoneyCard {
                    GuideLine("🎯", "预算与配额", "设置月度总预算，再给每个分类单独配单日 / 单周 / 单月额度，超额自动标红。")
                    Spacer(Modifier.height(10.dp))
                    GuideLine("💰", "结余与储蓄", "没用完的额度变成结余：优先抵回本分类，跨分类抵扣收少量手续费，月末统一转入储蓄。")
                    Spacer(Modifier.height(10.dp))
                    GuideLine("📥", "微信账单导入", "在「我的 → 微信账单导入」绑定邮箱后，账单邮件会自动收取并入库。")
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { page = 1 },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) { Text("开始设置") }
                OutlinedButton(
                    onClick = { finish() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) { Text("跳过引导") }
            } else {
                Text("第一步：本月生活费预算", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "预算用于整体把控本月支出；之后可以在「我的 → 预算与配额」随时修改。",
                    color = Muted,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = {
                        budgetText = it
                        budgetError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("本月预算（元）") },
                    placeholder = { Text("留空表示暂不设置预算") },
                    isError = budgetError,
                    supportingText = {
                        Text(
                            if (budgetError) "请输入有效金额（大于 0）" else "例如 3000",
                            color = if (budgetError) Danger else Muted,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                saveError?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(message, color = Danger)
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { finish() },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) { Text(if (saving) "保存中…" else "完成，进入账本") }
            }
        }
    }
}

@Composable
private fun GuideLine(emoji: String, title: String, detail: String) {
    Row {
        Text(emoji, fontSize = 20.sp)
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
