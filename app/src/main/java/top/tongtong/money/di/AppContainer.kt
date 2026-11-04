package top.tongtong.money.di

import android.content.Context
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.tongtong.money.data.local.AppDatabase
import top.tongtong.money.data.local.MIGRATION_1_2
import top.tongtong.money.data.prefs.SecureStore
import top.tongtong.money.data.prefs.SettingsRepository
import top.tongtong.money.data.repo.BackupRepository
import top.tongtong.money.data.repo.CategoryRepository
import top.tongtong.money.data.repo.ImportRepository
import top.tongtong.money.data.repo.LedgerRepository
import top.tongtong.money.data.repo.UpdateRepository
import top.tongtong.money.domain.imports.BillDownloader
import top.tongtong.money.domain.imports.BillImportService
import top.tongtong.money.domain.imports.ImapMailClient
import top.tongtong.money.worker.EmailSyncWorker
import top.tongtong.money.worker.SettlementWorker
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "money.db",
    ).addMigrations(MIGRATION_1_2).build()

    val settingsRepository = SettingsRepository(appContext)
    val secureStore = SecureStore(appContext)
    val ledgerRepository = LedgerRepository(database)
    val billImportService = BillImportService(
        context = appContext,
        db = database,
        ledgerRepository = ledgerRepository,
        downloader = BillDownloader(),
    )
    val importRepository = ImportRepository(
        context = appContext,
        db = database,
        settingsRepository = settingsRepository,
        secureStore = secureStore,
        importService = billImportService,
        mailClient = ImapMailClient(),
    )
    val categoryRepository = CategoryRepository(
        db = database,
        settingsRepository = settingsRepository,
        ledgerRepository = ledgerRepository,
    )
    val backupRepository = BackupRepository(
        db = database,
        ledgerRepository = ledgerRepository,
        settingsRepository = settingsRepository,
    )
    val updateRepository = UpdateRepository(
        context = appContext,
        settingsRepository = settingsRepository,
    )

    init {
        scope.launch {
            runCatching { categoryRepository.seedDefaults() }
                .onFailure { if (it is CancellationException) throw it }
            runCatching { ledgerRepository.catchUp() }
                .onFailure { if (it is CancellationException) throw it }
            // 24 小时节流由仓库内部处理；失败不影响应用启动
            runCatching { updateRepository.autoCheck() }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    fun scheduleWork() {
        val workManager = WorkManager.getInstance(appContext)

        val settlement = PeriodicWorkRequestBuilder<SettlementWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(millisUntil(5, 5), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            SETTLEMENT_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            settlement,
        )

        val email = PeriodicWorkRequestBuilder<EmailSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            EMAIL_SYNC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            email,
        )
    }

    /** 距离下一个指定时刻（本地时间）的毫秒数。 */
    private fun millisUntil(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now()
        var target = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis()
    }

    /**
     * 清空全部账本数据并恢复初始状态：忘记 PIN 时的唯一出路。
     * 只在锁屏界面上用户二次确认后调用；清空后重播种分类，应用即可直接使用。
     */
    fun wipeAllDataAndReset() {
        scope.launch {
            runCatching {
                // clearAllTables 禁止在主线程调用，本 scope 固定在 IO
                database.clearAllTables()
                settingsRepository.resetAll()
                secureStore.clearAll()
                categoryRepository.seedDefaults()
                ledgerRepository.catchUp()
            }.onFailure {
                // 中途失败（如数据已清但 PIN 残留）不静默：记录日志便于排查，用户可再次清空恢复
                if (it is CancellationException) throw it
                android.util.Log.e("AppContainer", "清空账本数据失败", it)
            }
        }
    }

    companion object {
        const val SETTLEMENT_WORK = "money_settlement"
        const val EMAIL_SYNC_WORK = "money_email_sync"
    }
}
