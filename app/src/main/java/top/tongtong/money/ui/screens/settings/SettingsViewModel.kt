package top.tongtong.money.ui.screens.settings

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tongtong.money.data.prefs.LockManager
import top.tongtong.money.data.prefs.SettingsRepository
import top.tongtong.money.data.repo.InstallResult
import top.tongtong.money.data.repo.UpdateRepository
import top.tongtong.money.data.repo.UpdateState
import top.tongtong.money.di.AppContainer
import java.math.BigDecimal
import java.math.RoundingMode

data class SettingsUiState(
    val lockEnabled: Boolean = false,
    val pinSet: Boolean = false,
    val biometricEnabled: Boolean = false,
    val defaultFeeBp: Int = 1000,
    val updateState: UpdateState = UpdateState.Idle,
    val error: String? = null,
    val message: String? = null,
) {
    val defaultFeePercent: String
        get() = BigDecimal(defaultFeeBp)
            .divide(BigDecimal(100))
            .stripTrailingZeros()
            .toPlainString()
}

private data class Feedback(val text: String, val isError: Boolean)

private const val MAX_PIN_ATTEMPTS = 5
private const val PIN_COOLDOWN_MILLIS = 30_000L

class SettingsViewModel(container: AppContainer) : ViewModel() {

    private val settingsRepository: SettingsRepository = container.settingsRepository
    private val updateRepository: UpdateRepository = container.updateRepository
    private val feedback = MutableStateFlow<Feedback?>(null)
    private var oldPinFailures = 0
    private var pinCooldownUntil = 0L

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepository.flow,
        feedback,
        updateRepository.state,
    ) { settings, current, update ->
        SettingsUiState(
            lockEnabled = settings.lockEnabled,
            pinSet = settings.pinHash.isNotBlank(),
            biometricEnabled = settings.biometricEnabled,
            defaultFeeBp = settings.defaultFeeBp,
            updateState = update,
            error = current?.takeIf { it.isError }?.text,
            message = current?.takeIf { !it.isError }?.text,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setPin(oldPin: String?, pin: String, confirm: String) {
        feedback.value = null
        when {
            pin.length != 6 || pin.any { !it.isDigit() } ->
                feedback.value = Feedback("PIN 需要 6 位数字", true)
            pin != confirm ->
                feedback.value = Feedback("两次输入的 PIN 不一致", true)
            else -> viewModelScope.launch {
                try {
                    val current = settingsRepository.flow.first()
                    if (current.pinHash.isNotBlank()) {
                        val now = SystemClock.elapsedRealtime()
                        if (now < pinCooldownUntil) {
                            feedback.value = Feedback("旧 PIN 尝试次数过多，请稍后重试", true)
                            return@launch
                        }
                        val matched = withContext(Dispatchers.Default) {
                            LockManager.verify(oldPin.orEmpty(), current.pinSalt, current.pinHash)
                        }
                        if (!matched) {
                            oldPinFailures++
                            if (oldPinFailures >= MAX_PIN_ATTEMPTS) {
                                oldPinFailures = 0
                                pinCooldownUntil = SystemClock.elapsedRealtime() + PIN_COOLDOWN_MILLIS
                            }
                            feedback.value = Feedback("当前 PIN 不正确，无法修改", true)
                            return@launch
                        }
                        oldPinFailures = 0
                    }
                    // 哈希是 CPU 密集操作，放到后台线程避免阻塞主线程
                    val (hash, salt) = withContext(Dispatchers.Default) {
                        val newSalt = LockManager.newSalt()
                        LockManager.hashPin(pin, newSalt) to newSalt
                    }
                    settingsRepository.setPin(hash, salt)
                    feedback.value = Feedback("PIN 已设置，应用锁已开启", false)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    feedback.value = Feedback("保存失败，请重试", true)
                }
            }
        }
    }

    fun setLockEnabled(enabled: Boolean) {
        feedback.value = null
        viewModelScope.launch {
            try {
                // 直接读仓库状态，避免 state 未订阅时的初始值/过期值误判
                val current = settingsRepository.flow.first()
                if (enabled && current.pinHash.isBlank()) {
                    feedback.value = Feedback("请先设置 6 位解锁 PIN", true)
                    return@launch
                }
                settingsRepository.setLockEnabled(enabled)
                if (!enabled) settingsRepository.setBiometricEnabled(false)
                feedback.value = Feedback(
                    if (enabled) "应用锁已开启" else "应用锁已关闭",
                    false,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                feedback.value = Feedback("保存失败，请重试", true)
            }
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        feedback.value = null
        viewModelScope.launch {
            try {
                if (enabled) {
                    val current = settingsRepository.flow.first()
                    if (!current.lockEnabled || current.pinHash.isBlank()) {
                        feedback.value = Feedback("请先开启应用锁并设置 PIN", true)
                        return@launch
                    }
                }
                settingsRepository.setBiometricEnabled(enabled)
                feedback.value = Feedback(
                    if (enabled) "已开启生物识别解锁" else "已关闭生物识别解锁",
                    false,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                feedback.value = Feedback("保存失败，请重试", true)
            }
        }
    }

    fun setDefaultFee(input: String) {
        feedback.value = null
        val cleaned = input.trim().removeSuffix("%")
        val percent = runCatching { BigDecimal(cleaned) }.getOrNull()
        if (percent == null || percent < BigDecimal.ZERO || percent > BigDecimal(100)) {
            feedback.value = Feedback("请输入 0 到 100 之间的百分比", true)
            return
        }
        val bp = percent.multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
        viewModelScope.launch {
            try {
                settingsRepository.setDefaultFeeBp(bp)
                feedback.value = Feedback("默认手续费率已保存", false)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                feedback.value = Feedback("保存失败，请重试", true)
            }
        }
    }

    fun clearFeedback() {
        feedback.value = null
    }

    fun checkUpdate() {
        feedback.value = null
        viewModelScope.launch {
            updateRepository.check()
        }
    }

    fun downloadUpdate() {
        val manifest = (updateRepository.state.value as? UpdateState.Available)?.manifest ?: return
        viewModelScope.launch {
            updateRepository.download(manifest)
        }
    }

    fun installUpdate() {
        val ready = updateRepository.state.value as? UpdateState.Ready ?: return
        when (updateRepository.install(ready.filePath)) {
            InstallResult.NEEDS_PERMISSION -> {
                updateRepository.openInstallPermissionSettings()
                feedback.value = Feedback("请允许本应用安装未知来源应用后再点安装", true)
            }
            InstallResult.FAILED -> feedback.value = Feedback("无法启动安装器，请重试", true)
            InstallResult.LAUNCHED -> Unit
        }
    }
}
