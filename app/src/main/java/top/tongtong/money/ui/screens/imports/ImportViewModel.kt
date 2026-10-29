package top.tongtong.money.ui.screens.imports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.ImportCandidateEntity
import top.tongtong.money.data.local.ImportLogEntity
import top.tongtong.money.data.prefs.SettingsRepository
import top.tongtong.money.data.repo.CandidateAttempt
import top.tongtong.money.data.repo.ImportRepository
import top.tongtong.money.data.repo.PickedBillFile
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.imports.ExpiredBillLinkException
import top.tongtong.money.domain.imports.ImportOutcome
import top.tongtong.money.domain.imports.PasswordRequiredException
import top.tongtong.money.domain.imports.WrongPasswordException

data class PasswordPrompt(
    val candidateId: Long?,
    val title: String,
    val error: String? = null,
)

data class ImportUiState(
    val host: String = "",
    val port: String = "993",
    val ssl: Boolean = true,
    val user: String = "",
    val folder: String = "INBOX",
    val fetchDays: String = "7",
    val fetchLimit: String = "20",
    val authCode: String = "",
    val hasAuthCode: Boolean = false,
    val enabled: Boolean = false,
    val lastSync: Long = 0,
    val rememberZipPassword: Boolean = false,
    val candidates: List<ImportCandidateEntity> = emptyList(),
    val logs: List<ImportLogEntity> = emptyList(),
    val syncing: Boolean = false,
    val testing: Boolean = false,
    val importing: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val passwordPrompt: PasswordPrompt? = null,
)

class ImportViewModel(container: AppContainer) : ViewModel() {

    private val repository: ImportRepository = container.importRepository
    private val settingsRepository: SettingsRepository = container.settingsRepository

    private val _state = MutableStateFlow(ImportUiState(hasAuthCode = repository.hasAuthCode()))
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var pendingFile: PickedBillFile? = null
    private var configLoaded = false

    init {
        viewModelScope.launch {
            repository.candidates.collect { candidates ->
                _state.update { it.copy(candidates = candidates) }
            }
        }
        viewModelScope.launch {
            repository.logs.collect { logs ->
                _state.update { it.copy(logs = logs) }
            }
        }
        viewModelScope.launch {
            settingsRepository.flow.collect { settings ->
                // 在 update 之外计算，避免 CAS 重试时重复执行副作用导致配置丢失
                val loadAll = !configLoaded
                configLoaded = true
                _state.update { current ->
                    if (loadAll) {
                        current.copy(
                            host = settings.emailHost,
                            port = settings.emailPort.toString(),
                            ssl = settings.emailSsl,
                            user = settings.emailUser,
                            folder = settings.emailFolder,
                            fetchDays = settings.emailFetchDays.toString(),
                            fetchLimit = settings.emailFetchLimit.toString(),
                            enabled = settings.emailEnabled,
                            lastSync = settings.emailLastSync,
                            rememberZipPassword = settings.rememberZipPassword,
                        )
                    } else {
                        current.copy(
                            enabled = settings.emailEnabled,
                            lastSync = settings.emailLastSync,
                            rememberZipPassword = settings.rememberZipPassword,
                        )
                    }
                }
            }
        }
    }

    fun setHost(value: String) = _state.update { it.copy(host = value, message = null, error = null) }

    fun setPort(value: String) = _state.update { it.copy(port = value.filter { char -> char.isDigit() }) }

    fun setSsl(value: Boolean) = _state.update { it.copy(ssl = value) }

    fun setUser(value: String) = _state.update { it.copy(user = value, message = null, error = null) }

    fun setFolder(value: String) = _state.update { it.copy(folder = value) }

    fun setFetchDays(value: String) =
        _state.update { it.copy(fetchDays = value.filter { char -> char.isDigit() }.take(3)) }

    fun setFetchLimit(value: String) =
        _state.update { it.copy(fetchLimit = value.filter { char -> char.isDigit() }.take(3)) }

    fun setAuthCode(value: String) = _state.update { it.copy(authCode = value) }

    fun setRememberZipPassword(value: Boolean) {
        _state.update { it.copy(rememberZipPassword = value) }
        viewModelScope.launch { settingsRepository.setRememberZipPassword(value) }
    }

    fun saveConfig() {
        val snapshot = _state.value
        if (snapshot.host.isBlank() || snapshot.user.isBlank()) {
            _state.update { it.copy(error = "请填写邮箱服务器与账号") }
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.saveEmailConfig(
                    host = snapshot.host,
                    port = snapshot.port.toIntOrNull() ?: 993,
                    ssl = snapshot.ssl,
                    user = snapshot.user,
                    folder = snapshot.folder,
                    authCode = snapshot.authCode.takeIf { it.isNotBlank() },
                )
                // 拉取范围留空时回落到默认 7 天 / 20 封
                settingsRepository.setEmailFetchScope(
                    days = snapshot.fetchDays.toIntOrNull() ?: 7,
                    limit = snapshot.fetchLimit.toIntOrNull() ?: 20,
                )
            }.onSuccess {
                _state.update {
                    it.copy(
                        authCode = "",
                        hasAuthCode = repository.hasAuthCode(),
                        message = "邮箱配置已保存",
                        error = null,
                    )
                }
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                _state.update { it.copy(error = exception.message ?: "保存失败") }
            }
        }
    }

    fun testConnection() {
        val snapshot = _state.value
        if (snapshot.testing) return
        if (snapshot.host.isBlank() || snapshot.user.isBlank()) {
            _state.update { it.copy(error = "请填写邮箱服务器与账号") }
            return
        }
        if ((snapshot.port.toIntOrNull() ?: 0) !in 1..65535) {
            _state.update { it.copy(error = "端口号无效") }
            return
        }
        // 先同步置位，避免连续点击时两个协程同时通过 testing 判断
        _state.update { it.copy(testing = true, message = null, error = null) }
        viewModelScope.launch {
            val code = snapshot.authCode.ifBlank { repository.imapAuthCode().orEmpty() }
            runCatching {
                repository.testConnection(
                    host = snapshot.host,
                    port = snapshot.port.toIntOrNull() ?: 993,
                    ssl = snapshot.ssl,
                    user = snapshot.user,
                    folder = snapshot.folder,
                    authCode = code,
                )
            }.onSuccess { count ->
                _state.update {
                    it.copy(testing = false, message = "连接成功，收件箱共 $count 封邮件")
                }
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                _state.update {
                    it.copy(testing = false, error = exception.message ?: "连接失败")
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled) }
        viewModelScope.launch { settingsRepository.setEmailEnabled(enabled) }
    }

    fun syncNow() {
        if (_state.value.syncing) return
        // 先同步置位，避免连续点击时两个协程同时通过 syncing 判断
        _state.update { it.copy(syncing = true, message = null, error = null) }
        viewModelScope.launch {
            runCatching { repository.syncNow() }
                .onSuccess { summary ->
                    _state.update {
                        it.copy(
                            syncing = false,
                            message = "拉取 ${summary.fetched} 封：导入 ${summary.imported}，待解压码 ${summary.needPassword}，失败 ${summary.failed}",
                        )
                    }
                }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    _state.update {
                        it.copy(syncing = false, error = exception.message ?: "同步失败")
                    }
                }
        }
    }

    fun importUri(uri: Uri) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, message = null, error = null) }
            val file = runCatching { repository.readPickedFile(uri) }
                .onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    _state.update {
                        it.copy(importing = false, error = exception.message ?: "无法读取文件")
                    }
                }
                .getOrNull() ?: return@launch
            tryImport(file, password = null)
        }
    }

    fun requestPassword(candidateId: Long) {
        val candidate = _state.value.candidates.firstOrNull { it.id == candidateId }
        _state.update {
            it.copy(
                passwordPrompt = PasswordPrompt(
                    candidateId = candidateId,
                    title = candidate?.subject ?: "账单解压码",
                ),
            )
        }
    }

    fun dismissPasswordPrompt() {
        // 丢弃未完成的手动导入文件，避免稍后误用旧文件继续导入
        pendingFile = null
        _state.update { it.copy(passwordPrompt = null) }
    }

    fun submitPassword(password: String) {
        if (_state.value.importing) return
        val prompt = _state.value.passwordPrompt ?: return
        if (password.isBlank()) {
            _state.update { it.copy(passwordPrompt = prompt.copy(error = "请输入解压码")) }
            return
        }
        val remember = _state.value.rememberZipPassword
        viewModelScope.launch {
            _state.update { it.copy(importing = true, passwordPrompt = prompt.copy(error = null)) }
            if (prompt.candidateId != null) {
                runCatching { repository.submitPassword(prompt.candidateId, password, remember) }
                    .onSuccess { attempt ->
                        when (attempt) {
                            CandidateAttempt.IMPORTED -> _state.update {
                                it.copy(
                                    importing = false,
                                    passwordPrompt = null,
                                    message = "账单导入成功",
                                    error = null,
                                )
                            }
                            CandidateAttempt.NEED_PASSWORD -> _state.update {
                                it.copy(
                                    importing = false,
                                    passwordPrompt = prompt.copy(error = "解压码不正确，请重新输入"),
                                )
                            }
                            CandidateAttempt.FAILED -> _state.update {
                                it.copy(
                                    importing = false,
                                    passwordPrompt = null,
                                    error = "导入失败，请查看候选状态",
                                )
                            }
                        }
                    }
                    .onFailure { exception ->
                        if (exception is CancellationException) throw exception
                        _state.update {
                            it.copy(
                                importing = false,
                                passwordPrompt = prompt.copy(error = exception.message ?: "导入失败"),
                            )
                        }
                    }
            } else {
                val file = pendingFile
                if (file == null) {
                    _state.update { it.copy(importing = false, passwordPrompt = null) }
                } else {
                    tryImport(file, password)
                }
            }
        }
    }

    private suspend fun tryImport(file: PickedBillFile, password: String?) {
        try {
            val outcome = repository.importPickedFile(
                file = file,
                password = password,
                rememberPassword = _state.value.rememberZipPassword,
            )
            pendingFile = null
            _state.update {
                it.copy(
                    importing = false,
                    passwordPrompt = null,
                    message = describe(outcome),
                    error = null,
                )
            }
        } catch (exception: PasswordRequiredException) {
            pendingFile = file
            _state.update {
                it.copy(
                    importing = false,
                    passwordPrompt = PasswordPrompt(null, file.name, "账单已加密，请输入解压码"),
                )
            }
        } catch (exception: WrongPasswordException) {
            pendingFile = file
            _state.update {
                it.copy(
                    importing = false,
                    passwordPrompt = PasswordPrompt(null, file.name, "解压码不正确，请重新输入"),
                )
            }
        } catch (exception: ExpiredBillLinkException) {
            pendingFile = null
            _state.update {
                it.copy(importing = false, error = exception.message ?: "账单下载链接已过期")
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            pendingFile = null
            _state.update {
                it.copy(importing = false, error = exception.message ?: "导入失败")
            }
        }
    }

    private fun describe(outcome: ImportOutcome): String =
        "导入完成：新增 ${outcome.importedRows} 条，重复 ${outcome.duplicateRows} 条，跳过 ${outcome.skippedRows} 条"
}
