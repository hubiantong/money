package top.tongtong.money.ui.screens.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tongtong.money.data.local.CandidateStatus
import top.tongtong.money.data.repo.BackupPreview
import top.tongtong.money.data.repo.UpdateState
import top.tongtong.money.di.AppContainer
import top.tongtong.money.domain.backup.LedgerBackupExporter
import top.tongtong.money.domain.backup.LedgerBackupImporter
import top.tongtong.money.domain.backup.ParsedBackup
import top.tongtong.money.domain.ledger.PeriodCalculator
import java.io.ByteArrayOutputStream
import java.io.IOException

data class ProfileUiState(
    val monthKey: String = PeriodCalculator.currentMonthKey(),
    val budgetCents: Long? = null,
    val savingsBalanceCents: Long = 0,
    val categoryCount: Int = 0,
    val ruleCount: Int = 0,
    val pendingImports: Int = 0,
    val emailEnabled: Boolean = false,
    val lockEnabled: Boolean = false,
    /** 有新版本可更新时的版本号，用于"设置"入口角标。 */
    val updateVersionName: String? = null,
    val exportRunning: Boolean = false,
    val restorePreview: BackupPreview? = null,
    val restoring: Boolean = false,
    val backupMessage: String? = null,
    val backupError: String? = null,
)

/** 备份文件读取上限，与账单导入的 20MB 限制对齐。 */
private const val MAX_BACKUP_BYTES = 20L * 1024 * 1024

class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    private val clock: Flow<String> = flow {
        while (true) {
            emit(PeriodCalculator.currentMonthKey())
            // 对齐到下一个整分钟，跨月时能尽快刷新月份标签
            val now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }

    private data class ExportState(
        val running: Boolean = false,
        val message: String? = null,
        val error: String? = null,
    )

    private data class RestoreState(
        val preview: BackupPreview? = null,
        val running: Boolean = false,
    )

    private val exportState = MutableStateFlow(ExportState())
    private val restoreState = MutableStateFlow(RestoreState())
    private var parsedRestore: ParsedBackup? = null

    private val baseState = combine(
        container.settingsRepository.flow,
        container.categoryRepository.categories,
        container.database.savingsDao().observeBalance(),
        container.categoryRepository.merchantRules,
        container.importRepository.candidates,
    ) { settings, categories, savings, rules, candidates ->
        ProfileUiState(
            budgetCents = settings.monthlyBudgetCents,
            savingsBalanceCents = savings,
            categoryCount = categories.count { !it.archived },
            ruleCount = rules.size,
            pendingImports = candidates.count {
                it.status == CandidateStatus.PENDING_PASSWORD || it.status == CandidateStatus.READY
            },
            emailEnabled = settings.emailEnabled,
            lockEnabled = settings.lockEnabled,
        )
    }

    val state: StateFlow<ProfileUiState> = combine(
        baseState,
        clock,
        exportState,
        restoreState,
        container.updateRepository.state,
    ) { current, monthKey, export, restore, update ->
        current.copy(
            monthKey = monthKey,
            exportRunning = export.running,
            restorePreview = restore.preview,
            restoring = restore.running,
            backupMessage = export.message,
            backupError = export.error,
            updateVersionName = when (update) {
                is UpdateState.Available -> update.manifest.versionName
                is UpdateState.Ready -> update.manifest.versionName
                else -> null
            },
        )
    }.retryWhen { _, _ ->
        // 上游异常（如 DataStore/Room 读取失败）后延迟重试，避免状态流被永久取消
        delay(1_000L)
        true
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState(),
    )

    /** 全量备份导出到用户通过 SAF 选择的位置；只写源数据，派生状态由引擎重放。 */
    fun exportBackup(uri: Uri) {
        if (exportState.value.running) return
        exportState.value = ExportState(running = true)
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val db = container.database
                    val settings = container.settingsRepository.flow.first()
                    LedgerBackupExporter.build(
                        categories = db.categoryDao().getAll(),
                        transactions = db.transactionDao().getAll(),
                        merchantRules = db.merchantRuleDao().getAll(),
                        monthlyBudgetCents = settings.monthlyBudgetCents,
                        defaultFeeBp = settings.defaultFeeBp,
                        exportedAt = System.currentTimeMillis(),
                    )
                }
                withContext(Dispatchers.IO) {
                    val output = container.appContext.contentResolver.openOutputStream(uri)
                        ?: throw IOException("无法写入所选位置")
                    output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                exportState.value = ExportState(message = "备份已导出")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                exportState.value = ExportState(error = exception.message ?: "导出失败")
            }
        }
    }

    /** 读取所选备份文件并解析出摘要，等待用户在确认弹窗中提交恢复。 */
    fun readBackupForRestore(uri: Uri) {
        if (restoreState.value.running) return
        restoreState.value = RestoreState()
        viewModelScope.launch {
            try {
                val backup = withContext(Dispatchers.IO) {
                    val text = container.appContext.contentResolver.openInputStream(uri)
                        ?.use { stream ->
                            // 与账单导入对齐：超大文件直接拒绝，避免误选文件时整读爆内存
                            val buffer = ByteArrayOutputStream(minOf(stream.available(), MAX_BACKUP_BYTES.toInt()).coerceAtLeast(0))
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                val read = stream.read(chunk)
                                if (read < 0) break
                                if (buffer.size() + read > MAX_BACKUP_BYTES) {
                                    throw IOException("备份文件过大（上限 20MB）")
                                }
                                buffer.write(chunk, 0, read)
                            }
                            buffer.toString("UTF-8")
                        }
                        ?: throw IOException("无法读取所选文件")
                    LedgerBackupImporter.parse(text)
                }
                parsedRestore = backup
                restoreState.value = RestoreState(
                    preview = BackupPreview(
                        version = backup.version,
                        categoryCount = backup.categories.size,
                        transactionCount = backup.transactions.size,
                        ruleCount = backup.merchantRules.size,
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                restoreState.value = RestoreState()
                exportState.value = ExportState(error = exception.message ?: "备份文件无法识别")
            }
        }
    }

    fun confirmRestore() {
        val backup = parsedRestore ?: return
        if (restoreState.value.running) return
        restoreState.value = RestoreState(running = true)
        viewModelScope.launch {
            try {
                val result = container.backupRepository.restore(backup)
                restoreState.value = RestoreState()
                parsedRestore = null
                exportState.value = ExportState(message = result.describe())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                restoreState.value = RestoreState()
                parsedRestore = null
                exportState.value = ExportState(error = exception.message ?: "恢复失败")
            }
        }
    }

    fun dismissRestorePreview() {
        parsedRestore = null
        restoreState.value = RestoreState()
    }

    fun consumeBackupResult() {
        exportState.value = ExportState()
    }
}
