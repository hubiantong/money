package top.tongtong.money.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.screens.budget.BudgetScreen
import top.tongtong.money.ui.screens.budget.CategoryEditScreen
import top.tongtong.money.ui.screens.home.HomeScreen
import top.tongtong.money.ui.screens.imports.EmailImportScreen
import top.tongtong.money.ui.screens.profile.ProfileScreen
import top.tongtong.money.ui.screens.rules.MerchantRulesScreen
import top.tongtong.money.ui.screens.savings.SavingsScreen
import top.tongtong.money.ui.screens.settings.SettingsScreen
import top.tongtong.money.ui.screens.stats.StatsScreen
import top.tongtong.money.ui.screens.transaction.AddTransactionScreen
import top.tongtong.money.ui.screens.transaction.TransactionDetailScreen
import top.tongtong.money.ui.screens.transaction.TransactionListScreen

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabItem("home", "首页", Icons.Filled.Home),
    TabItem("transactions", "明细", Icons.AutoMirrored.Filled.List),
    TabItem("stats", "统计", Icons.Filled.PieChart),
    TabItem("profile", "我的", Icons.Filled.Person),
)

@Composable
fun MoneyNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 二级路由带可选参数（如 transactions?categoryId={categoryId}），底栏匹配只看主路径
    val currentBaseRoute = currentRoute?.substringBefore('?')
    val showBottomBar = tabs.any { it.route == currentBaseRoute }

    fun navigateToTab(route: String) {
        if (currentBaseRoute == route) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentBaseRoute == tab.route,
                            onClick = { navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    container = container,
                    onAdd = { navController.navigate("add") },
                    onOpenTransaction = { navController.navigate("detail/$it") },
                    onOpenSavings = { navController.navigate("savings") },
                    onOpenBudget = { navController.navigate("budget") },
                    onViewAll = { navigateToTab("transactions") },
                )
            }

            composable(
                route = "transactions?categoryId={categoryId}&month={month}",
                arguments = listOf(
                    navArgument("categoryId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument("month") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val categoryId = entry.arguments?.getLong("categoryId") ?: -1L
                val monthKey = entry.arguments?.getString("month").orEmpty()
                TransactionListScreen(
                    container = container,
                    initialCategoryId = categoryId.takeIf { it > 0 },
                    initialMonthKey = monthKey.takeIf { it.isNotBlank() },
                    onOpenTransaction = { navController.navigate("detail/$it") },
                )
            }

            composable("stats") {
                StatsScreen(
                    container = container,
                    onOpenCategory = { categoryId, monthKey ->
                        navController.navigate("transactions?categoryId=$categoryId&month=$monthKey")
                    },
                )
            }

            composable("profile") {
                ProfileScreen(
                    container = container,
                    onOpenBudget = { navController.navigate("budget") },
                    onOpenSavings = { navController.navigate("savings") },
                    onOpenImport = { navController.navigate("import") },
                    onOpenMerchantRules = { navController.navigate("merchant-rules") },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }

            composable(
                route = "add?transactionId={transactionId}",
                arguments = listOf(
                    navArgument("transactionId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                AddTransactionScreen(
                    container = container,
                    transactionId = entry.arguments?.getLong("transactionId") ?: 0L,
                    onDone = { navController.popBackStack() },
                )
            }

            composable(
                route = "detail/{transactionId}",
                arguments = listOf(navArgument("transactionId") { type = NavType.LongType }),
            ) { entry ->
                val id = requireNotNull(entry.arguments).getLong("transactionId")
                TransactionDetailScreen(
                    container = container,
                    transactionId = id,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate("add?transactionId=$id") },
                )
            }

            composable("budget") {
                BudgetScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onEditCategory = { navController.navigate("category/$it") },
                )
            }

            composable(
                route = "category/{categoryId}",
                arguments = listOf(navArgument("categoryId") { type = NavType.LongType }),
            ) { entry ->
                CategoryEditScreen(
                    container = container,
                    categoryId = requireNotNull(entry.arguments).getLong("categoryId"),
                    onDone = { navController.popBackStack() },
                )
            }

            composable("savings") {
                SavingsScreen(container = container, onBack = { navController.popBackStack() })
            }

            composable("import") {
                EmailImportScreen(container = container, onBack = { navController.popBackStack() })
            }

            composable("merchant-rules") {
                MerchantRulesScreen(container = container, onBack = { navController.popBackStack() })
            }

            composable("settings") {
                SettingsScreen(container = container, onBack = { navController.popBackStack() })
            }
        }
    }
}
