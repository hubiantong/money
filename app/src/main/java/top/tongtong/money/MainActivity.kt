package top.tongtong.money

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.prefs.UserSettings
import top.tongtong.money.ui.navigation.MoneyNavHost
import top.tongtong.money.ui.screens.lock.LockGate
import top.tongtong.money.ui.screens.onboarding.OnboardingScreen
import top.tongtong.money.ui.theme.MoneyTheme

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MoneyApp).container

        setContent {
            MoneyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val settings by container.settingsRepository.flow
                        .collectAsStateWithLifecycle<UserSettings?>(initialValue = null)
                    val current = settings
                    if (current == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (!current.onboardingDone) {
                        // 首次启动（或升级后）展示引导；完成写入 onboarding_done 后自动切回
                        OnboardingScreen(container)
                    } else {
                        LockGate(
                            enabled = current.lockEnabled,
                            pinHash = current.pinHash,
                            pinSalt = current.pinSalt,
                            biometricEnabled = current.biometricEnabled,
                            onResetApp = container::wipeAllDataAndReset,
                        ) {
                            MoneyNavHost(container)
                        }
                    }
                }
            }
        }
    }
}
