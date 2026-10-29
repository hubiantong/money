package top.tongtong.money.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStore(context: Context) {

    private val appContext = context.applicationContext

    // 延迟创建：避免在 Application.onCreate 主线程上做 Keystore/磁盘 IO，并支持密钥失效后恢复。
    private val prefs: SharedPreferences by lazy {
        createPrefs().getOrElse {
            // 主密钥失效或文件损坏：删除后重建一次
            appContext.deleteSharedPreferences(PREFS_FILE)
            createPrefs().getOrThrow()
        }
    }

    private fun createPrefs(): Result<SharedPreferences> = runCatching {
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun imapAuthCode(): String? = prefs.getString(KEY_IMAP_CODE, null)

    fun setImapAuthCode(value: String?) = putSecret(KEY_IMAP_CODE, value)

    fun zipPassword(): String? = prefs.getString(KEY_ZIP_PASSWORD, null)

    fun setZipPassword(value: String?) = putSecret(KEY_ZIP_PASSWORD, value)

    /** 清空全部密钥（IMAP 授权码、记住的解压码），仅用于"忘记 PIN → 清空重置"。 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun putSecret(key: String, value: String?) {
        val editor = prefs.edit()
        if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    private companion object {
        const val PREFS_FILE = "secure_prefs"
        const val KEY_IMAP_CODE = "imap_auth_code"
        const val KEY_ZIP_PASSWORD = "last_zip_password"
    }
}
