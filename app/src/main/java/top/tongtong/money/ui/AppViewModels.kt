package top.tongtong.money.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import top.tongtong.money.MoneyApp
import top.tongtong.money.di.AppContainer

/** 从 Application 容器创建页面 ViewModel。 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as? MoneyApp
        ?: error("appViewModel() 需要通过 MoneyApp 作为 Application 才能创建 ViewModel")
    val container = app.container
    return viewModel(
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}
