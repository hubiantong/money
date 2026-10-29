package top.tongtong.money.ui.screens.lock

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.tongtong.money.data.prefs.LockManager
import top.tongtong.money.data.prefs.LockoutTracker
import top.tongtong.money.ui.theme.Danger
import top.tongtong.money.ui.theme.Muted

private val keypad = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")

private const val PIN_LENGTH = 6
private const val MAX_ATTEMPTS = 5
private const val COOLDOWN_MILLIS = 30_000L

/**
 * 冷却截止时间基于单调时钟保存：持久化时转成"剩余毫秒"，恢复时按当前 elapsedRealtime 换算，
 * 这样旋转屏幕或进程重启都不会把冷却与失败次数重置。
 */
private val cooldownSaver = Saver<Long, Long>(
    save = { deadline -> (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L) },
    restore = { remaining -> if (remaining > 0L) SystemClock.elapsedRealtime() + remaining else 0L },
)

@Composable
fun LockScreen(
    pinHash: String,
    pinSalt: String,
    biometricEnabled: Boolean,
    onUnlocked: () -> Unit,
    onResetApp: () -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lockoutTracker = remember { LockoutTracker(context) }
    // 首次进入（无已保存状态）时从 LockoutTracker 恢复，配置变化则由 rememberSaveable 接管
    var failedAttempts by rememberSaveable { mutableStateOf(lockoutTracker.failedAttempts()) }
    var cooldownUntil by rememberSaveable(stateSaver = cooldownSaver) {
        mutableStateOf(SystemClock.elapsedRealtime() + lockoutTracker.cooldownRemainingMillis())
    }
    // 使用单调时钟，避免用户修改系统时间跳过冷却
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var biometricHint by remember { mutableStateOf<String?>(null) }
    var authInProgress by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<BiometricPrompt?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)

    val cooldownSeconds = ((cooldownUntil - nowMillis).coerceAtLeast(0L) + 999) / 1000

    LaunchedEffect(cooldownUntil) {
        while (SystemClock.elapsedRealtime() < cooldownUntil) {
            nowMillis = SystemClock.elapsedRealtime()
            delay(500)
        }
        nowMillis = SystemClock.elapsedRealtime()
    }

    LaunchedEffect(pin) {
        if (pin.length == PIN_LENGTH) {
            val ok = withContext(Dispatchers.Default) {
                LockManager.verify(pin, pinSalt, pinHash)
            }
            if (ok) {
                lockoutTracker.clear()
                onUnlocked()
            } else {
                error = true
                pin = ""
                failedAttempts++
                if (failedAttempts >= MAX_ATTEMPTS) {
                    failedAttempts = 0
                    cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MILLIS
                    lockoutTracker.recordFailure(0, COOLDOWN_MILLIS)
                } else {
                    lockoutTracker.recordFailure(failedAttempts, 0L)
                }
            }
        }
    }
    LaunchedEffect(error) {
        if (error) {
            delay(900)
            error = false
        }
    }

    val biometricAvailable = remember(context) {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateBiometric() {
        // 同时只允许一个生物识别弹窗，避免重复创建导致回调叠加
        if (authInProgress) return
        val activity = context as? FragmentActivity
        if (activity == null) {
            biometricHint = "当前环境无法调起生物识别，请使用 PIN"
            return
        }
        if (!biometricAvailable) {
            biometricHint = "设备尚未录入指纹或面容，请使用 PIN"
            return
        }
        biometricHint = null
        authInProgress = true
        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authInProgress = false
                    currentOnUnlocked()
                }

                override fun onAuthenticationFailed() {
                    biometricHint = "未能识别，请重试或输入 PIN"
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authInProgress = false
                    biometricHint = when (errorCode) {
                        BiometricPrompt.ERROR_LOCKOUT -> "生物识别尝试次数过多，请稍后再试或使用 PIN"
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "生物识别已被锁定，请使用 PIN 解锁"
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT -> "生物识别硬件暂不可用，请使用 PIN"
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED -> "已取消生物识别，请输入 PIN"
                        else -> "生物识别不可用，请使用 PIN"
                    }
                }
            },
        )
        prompt = biometricPrompt
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁潼潼的账本")
            .setSubtitle("使用指纹或面容验证")
            .setNegativeButtonText("使用 PIN")
            .build()
        biometricPrompt.authenticate(info)
    }

    // 离开界面时关闭仍显示的生物识别弹窗，避免回调作用于已销毁的界面
    DisposableEffect(Unit) {
        onDispose {
            prompt?.cancelAuthentication()
            prompt = null
        }
    }

    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled) authenticateBiometric()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text("🔒", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text("潼潼的账本", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text("输入 6 位 PIN 解锁", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(PIN_LENGTH) { index ->
                val filled = index < pin.length
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                error -> Danger
                                filled -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = when {
                cooldownSeconds > 0 -> "尝试次数过多，请 $cooldownSeconds 秒后重试"
                error -> "PIN 不正确，请重试"
                else -> " "
            },
            color = if (error || cooldownSeconds > 0) Danger else Color.Transparent,
            style = MaterialTheme.typography.labelMedium,
        )
        biometricHint?.let { hint ->
            Spacer(Modifier.height(6.dp))
            Text(hint, color = Muted, style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.weight(1f))

        keypad.chunked(3).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowKeys.forEach { key ->
                    if (key.isBlank()) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        OutlinedButton(
                            onClick = {
                                when {
                                    key == "⌫" -> pin = pin.dropLast(1)
                                    pin.length < PIN_LENGTH -> {
                                        error = false
                                        biometricHint = null
                                        pin += key
                                    }
                                }
                            },
                            enabled = cooldownSeconds == 0L,
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp),
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (biometricEnabled) {
            TextButton(onClick = { authenticateBiometric() }) {
                Text("使用指纹 / 面容解锁")
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }

        TextButton(onClick = { showResetConfirm = true }) {
            Text("忘记 PIN？", color = Muted, style = MaterialTheme.typography.labelMedium)
        }

        Text(
            "离开应用超过 1 分钟会自动重新锁定",
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("清空数据并重置？") },
            text = {
                Text(
                    "PIN 无法找回，唯一出路是清空全部账本数据并恢复初始设置，" +
                        "包括所有交易、分类、导入与解锁配置。此操作不可恢复，确定继续吗？",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        lockoutTracker.clear()
                        onResetApp()
                    },
                ) {
                    Text("清空并重置", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            },
        )
    }
}
