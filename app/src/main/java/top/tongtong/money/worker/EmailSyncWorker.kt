package top.tongtong.money.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import top.tongtong.money.MoneyApp

/** 每小时检查一次邮箱；未启用邮箱同步时直接成功返回。 */
class EmailSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MoneyApp ?: return Result.success()
        return try {
            // 配置读取一并纳入重试策略，避免 DataStore 读取异常绕过重试
            val settings = app.container.settingsRepository.flow.first()
            if (!settings.emailEnabled) return Result.success()
            app.container.importRepository.syncNow()
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            if (runAttemptCount < MAX_RETRY) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRY = 3
    }
}
