package top.tongtong.money.data.prefs

import android.content.Context
import android.os.SystemClock

/**
 * 跨进程死亡保留锁屏的失败次数与冷却截止。
 * 存普通 SharedPreferences 即可：这里没有需要保密的值，冷却截止基于单调时钟
 * elapsedRealtime，设备重启后时钟归零（now < savedAt），冷却视为自然失效。
 */
class LockoutTracker(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun failedAttempts(): Int = prefs.getInt(KEY_ATTEMPTS, 0)

    fun cooldownRemainingMillis(): Long {
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (savedAt <= 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        if (now < savedAt) return 0L
        val deadline = prefs.getLong(KEY_DEADLINE, 0L)
        return (deadline - now).coerceAtLeast(0L)
    }

    /** 记录一次失败；cooldownRemainingMillis > 0 表示同时进入冷却。 */
    fun recordFailure(attempts: Int, cooldownRemainingMillis: Long) {
        val now = SystemClock.elapsedRealtime()
        prefs.edit()
            .putInt(KEY_ATTEMPTS, attempts)
            .putLong(KEY_DEADLINE, now + cooldownRemainingMillis)
            .putLong(KEY_SAVED_AT, now)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "lock_state"
        const val KEY_ATTEMPTS = "failed_attempts"
        const val KEY_DEADLINE = "cooldown_deadline"
        const val KEY_SAVED_AT = "cooldown_saved_at"
    }
}
