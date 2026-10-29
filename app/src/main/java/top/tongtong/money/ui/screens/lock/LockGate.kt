package top.tongtong.money.ui.screens.lock

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 退到后台超过该时长后重新锁定。 */
const val RE_LOCK_MILLIS = 60_000L

@Composable
fun LockGate(
    enabled: Boolean,
    pinHash: String,
    pinSalt: String,
    biometricEnabled: Boolean,
    onResetApp: () -> Unit,
    content: @Composable () -> Unit,
) {
    // MainActivity 只在设置加载完成后才组合 LockGate，首帧即可用 enabled 初始化
    var locked by rememberSaveable { mutableStateOf(enabled) }
    var backgroundAt by rememberSaveable { mutableLongStateOf(0L) }
    var previousEnabled by rememberSaveable { mutableStateOf(enabled) }

    LaunchedEffect(enabled) {
        if (enabled && !previousEnabled) {
            // 刚在设置中开启应用锁时立即生效，便于确认 PIN 可用
            locked = true
        }
        previousEnabled = enabled
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (currentEnabled) backgroundAt = SystemClock.elapsedRealtime()
                }
                Lifecycle.Event.ON_START -> {
                    val at = backgroundAt
                    backgroundAt = 0L
                    if (currentEnabled && at > 0) {
                        // 使用单调递增的 elapsedRealtime，避免修改系统时间绕过重锁
                        val elapsed = SystemClock.elapsedRealtime() - at
                        if (elapsed >= RE_LOCK_MILLIS || elapsed < 0L) locked = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // PIN 缺失（数据异常）时不锁定，避免把用户永久锁在门外
    val pinConfigured = pinHash.isNotBlank() && pinSalt.isNotBlank()
    val shouldLock = enabled && pinConfigured && locked
    if (shouldLock) {
        LockScreen(
            pinHash = pinHash,
            pinSalt = pinSalt,
            biometricEnabled = biometricEnabled,
            onUnlocked = { locked = false },
            onResetApp = onResetApp,
        )
    } else {
        content()
    }
}
