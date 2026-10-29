package top.tongtong.money

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.tongtong.money.di.AppContainer

class MoneyApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // 容器按需创建；WorkManager 注册放到后台线程，避免冷启动时主线程做 Keystore/磁盘 IO
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.scheduleWork()
        }
    }
}
