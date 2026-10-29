package top.tongtong.money.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import top.tongtong.money.MoneyApp

/** 每日结算：补算已结束周期并生成结余/储蓄流水。 */
class SettlementWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MoneyApp ?: return Result.success()
        return try {
            app.container.ledgerRepository.catchUp()
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            // 限制重试次数，避免持续性错误导致无限重试
            if (runAttemptCount < MAX_RETRY) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRY = 3
    }
}
