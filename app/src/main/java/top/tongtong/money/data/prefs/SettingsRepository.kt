package top.tongtong.money.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 设置项的默认值，保证数据类默认值与读取回退值只有一处定义。 */
private object SettingsDefaults {
    const val FEE_BP = 1000
    const val EMAIL_HOST = "imap.qq.com"
    const val EMAIL_PORT = 993
    const val EMAIL_FOLDER = "INBOX"
    const val EMAIL_FETCH_DAYS = 7
    const val EMAIL_FETCH_LIMIT = 20
}

data class UserSettings(
    val monthlyBudgetCents: Long? = null,
    val defaultFeeBp: Int = SettingsDefaults.FEE_BP,
    val emailHost: String = SettingsDefaults.EMAIL_HOST,
    val emailPort: Int = SettingsDefaults.EMAIL_PORT,
    val emailSsl: Boolean = true,
    val emailUser: String = "",
    val emailFolder: String = SettingsDefaults.EMAIL_FOLDER,
    val emailFetchDays: Int = SettingsDefaults.EMAIL_FETCH_DAYS,
    val emailFetchLimit: Int = SettingsDefaults.EMAIL_FETCH_LIMIT,
    val emailEnabled: Boolean = false,
    val emailLastSync: Long = 0,
    val lockEnabled: Boolean = false,
    val pinHash: String = "",
    val pinSalt: String = "",
    val biometricEnabled: Boolean = false,
    val rememberZipPassword: Boolean = false,
    val onboardingDone: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val monthlyBudget = longPreferencesKey("monthly_budget_cents")
        val defaultFeeBp = intPreferencesKey("default_fee_bp")
        val emailHost = stringPreferencesKey("email_host")
        val emailPort = intPreferencesKey("email_port")
        val emailSsl = booleanPreferencesKey("email_ssl")
        val emailUser = stringPreferencesKey("email_user")
        val emailFolder = stringPreferencesKey("email_folder")
        val emailFetchDays = intPreferencesKey("email_fetch_days")
        val emailFetchLimit = intPreferencesKey("email_fetch_limit")
        val emailEnabled = booleanPreferencesKey("email_enabled")
        val emailLastSync = longPreferencesKey("email_last_sync")
        val lockEnabled = booleanPreferencesKey("lock_enabled")
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
        val biometric = booleanPreferencesKey("biometric_enabled")
        val rememberZip = booleanPreferencesKey("remember_zip_password")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
    }

    /** 最近一次成功读取的设置：读取抖动时用它兜底，避免应用锁状态被静默重置。 */
    @Volatile
    private var lastGood: UserSettings? = null

    val flow: Flow<UserSettings> = context.dataStore.data
        .map { prefs ->
            UserSettings(
                monthlyBudgetCents = prefs[Keys.monthlyBudget],
                defaultFeeBp = prefs[Keys.defaultFeeBp] ?: SettingsDefaults.FEE_BP,
                emailHost = prefs[Keys.emailHost] ?: SettingsDefaults.EMAIL_HOST,
                emailPort = prefs[Keys.emailPort] ?: SettingsDefaults.EMAIL_PORT,
                emailSsl = prefs[Keys.emailSsl] ?: true,
                emailUser = prefs[Keys.emailUser] ?: "",
                emailFolder = prefs[Keys.emailFolder] ?: SettingsDefaults.EMAIL_FOLDER,
                emailFetchDays = prefs[Keys.emailFetchDays] ?: SettingsDefaults.EMAIL_FETCH_DAYS,
                emailFetchLimit = prefs[Keys.emailFetchLimit] ?: SettingsDefaults.EMAIL_FETCH_LIMIT,
                emailEnabled = prefs[Keys.emailEnabled] ?: false,
                emailLastSync = prefs[Keys.emailLastSync] ?: 0,
                lockEnabled = prefs[Keys.lockEnabled] ?: false,
                pinHash = prefs[Keys.pinHash] ?: "",
                pinSalt = prefs[Keys.pinSalt] ?: "",
                biometricEnabled = prefs[Keys.biometric] ?: false,
                rememberZipPassword = prefs[Keys.rememberZip] ?: false,
                onboardingDone = prefs[Keys.onboardingDone] ?: false,
            )
        }
        .onEach { settings -> lastGood = settings }
        .catch { e ->
            if (e is IOException) {
                // 读取失败时回退到最近一次成功快照；冷启动即失败无法恢复锁状态（已知限制）
                emit(lastGood ?: UserSettings())
            } else {
                throw e
            }
        }

    suspend fun setMonthlyBudget(cents: Long?) {
        context.dataStore.edit { prefs ->
            if (cents == null) prefs.remove(Keys.monthlyBudget) else prefs[Keys.monthlyBudget] = cents
        }
    }

    suspend fun setDefaultFeeBp(bp: Int) {
        context.dataStore.edit { it[Keys.defaultFeeBp] = bp.coerceIn(0, 10000) }
    }

    suspend fun setEmailConfig(
        host: String,
        port: Int,
        ssl: Boolean,
        user: String,
        folder: String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.emailHost] = host.trim()
            prefs[Keys.emailPort] = port.coerceIn(1, 65535)
            prefs[Keys.emailSsl] = ssl
            prefs[Keys.emailUser] = user.trim()
            prefs[Keys.emailFolder] = folder.trim().ifBlank { SettingsDefaults.EMAIL_FOLDER }
        }
    }

    suspend fun setEmailEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.emailEnabled] = enabled }
    }

    /** 拉取范围：最近 N 天、最多 M 封，限制范围防止误配导致全量拉取。 */
    suspend fun setEmailFetchScope(days: Int, limit: Int) {
        context.dataStore.edit {
            it[Keys.emailFetchDays] = days.coerceIn(1, 90)
            it[Keys.emailFetchLimit] = limit.coerceIn(5, 100)
        }
    }

    suspend fun setEmailLastSync(at: Long) {
        context.dataStore.edit { it[Keys.emailLastSync] = at }
    }

    suspend fun setLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.lockEnabled] = enabled }
    }

    suspend fun setPin(hash: String, salt: String) {
        context.dataStore.edit {
            it[Keys.pinHash] = hash
            it[Keys.pinSalt] = salt
            it[Keys.lockEnabled] = true
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.biometric] = enabled }
    }

    suspend fun setRememberZipPassword(enabled: Boolean) {
        context.dataStore.edit { it[Keys.rememberZip] = enabled }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[Keys.onboardingDone] = done }
    }

    /** 清空全部设置恢复默认值，仅用于"忘记 PIN → 清空重置"。 */
    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }
}
