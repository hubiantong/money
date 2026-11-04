package top.tongtong.money.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.tongtong.money.data.prefs.SettingsRepository
import top.tongtong.money.domain.update.UpdateManifest
import top.tongtong.money.domain.update.UpdateManifestParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long, val versionName: String) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest) : UpdateState
    data class Ready(val manifest: UpdateManifest, val filePath: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

enum class InstallResult { LAUNCHED, NEEDS_PERMISSION, FAILED }

/**
 * 检查 GitHub Releases 上的更新清单、下载 APK 并校验 SHA-256。
 * 启动时自动检查，24 小时内不重复请求（失败不写入时间戳，下次启动会重试）。
 */
class UpdateRepository(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build(),
) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installedVersionName: String
        get() = runCatching { packageInfo().versionName }.getOrNull().orEmpty().ifBlank { "未知" }

    /** 启动时调用：距上次成功检查不足 24 小时则跳过。 */
    suspend fun autoCheck() {
        val last = settingsRepository.flow.first().updateLastCheck
        if (last > 0 && System.currentTimeMillis() - last < CHECK_INTERVAL_MILLIS) return
        // 后台检查失败不打扰用户：重置为未检查，等用户主动点"检查更新"时再展示错误
        if (!check()) _state.value = UpdateState.Idle
    }

    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        when (_state.value) {
            is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Ready -> return@withContext false
            else -> Unit
        }
        _state.value = UpdateState.Checking
        try {
            val manifest = UpdateManifestParser.parse(fetch(MANIFEST_URL))
            if (manifest.packageName.isNotBlank() && manifest.packageName != appContext.packageName) {
                throw IllegalArgumentException("更新包与当前应用不匹配")
            }
            settingsRepository.setUpdateLastCheck(System.currentTimeMillis())
            _state.value = if (manifest.versionCode > installedVersionCode()) {
                UpdateState.Available(manifest)
            } else {
                UpdateState.UpToDate(System.currentTimeMillis(), installedVersionName)
            }
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            _state.value = UpdateState.Failed(exception.message ?: "检查更新失败")
            false
        }
    }

    suspend fun download(manifest: UpdateManifest): Boolean = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Downloading) return@withContext false
        if (Build.VERSION.SDK_INT < manifest.minSdk) {
            _state.value = UpdateState.Failed("当前系统版本过低，需要 Android ${manifest.minSdk} 及以上")
            return@withContext false
        }
        _state.value = UpdateState.Downloading(manifest)
        try {
            if (manifest.sha256.isBlank()) throw IOException("更新清单缺少 SHA-256，已停止下载")
            val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "tongtong-ledger-${manifest.versionName}.apk")
            if (file.exists()) file.delete()
            client.newCall(
                Request.Builder()
                    .url(manifest.apkUrl)
                    .header("User-Agent", USER_AGENT)
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
                val declared = response.body.contentLength()
                if (declared > MAX_APK_BYTES) throw IOException("安装包过大")
                response.body.byteStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (file.length() > MAX_APK_BYTES) {
                file.delete()
                throw IOException("安装包过大")
            }
            manifest.size?.let { expected ->
                if (file.length() != expected) {
                    file.delete()
                    throw IOException("安装包大小与清单不一致")
                }
            }
            if (sha256(file) != manifest.sha256) {
                file.delete()
                throw IOException("安装包校验失败，请稍后重试")
            }
            dir.listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }
            _state.value = UpdateState.Ready(manifest, file.absolutePath)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            _state.value = UpdateState.Failed(exception.message ?: "下载更新失败")
            false
        }
    }

    /** 拉起系统安装器；未授予"安装未知应用"权限时返回 [InstallResult.NEEDS_PERMISSION]。 */
    fun install(filePath: String): InstallResult {
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            return InstallResult.NEEDS_PERMISSION
        }
        val file = File(filePath)
        if (!file.exists()) return InstallResult.FAILED
        return try {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(intent)
            InstallResult.LAUNCHED
        } catch (exception: Exception) {
            InstallResult.FAILED
        }
    }

    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("检查更新失败：HTTP ${response.code}")
            val text = response.body.string()
            if (text.length > MAX_MANIFEST_CHARS) throw IOException("更新清单异常")
            return text
        }
    }

    private fun packageInfo() =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)

    private fun installedVersionCode(): Long {
        val info = packageInfo()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANIFEST_URL = "https://github.com/hubiantong/money/releases/latest/download/update.json"
        const val USER_AGENT = "TongTongLedger/Android"
        const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_APK_BYTES = 60L * 1024 * 1024
        const val MAX_MANIFEST_CHARS = 64 * 1024
    }
}
