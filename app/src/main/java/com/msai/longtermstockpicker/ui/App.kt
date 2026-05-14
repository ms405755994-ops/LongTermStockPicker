package com.msai.longtermstockpicker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.msai.longtermstockpicker.StockPickerViewModel
import com.msai.longtermstockpicker.ui.theme.LongTermStockPickerTheme

@Composable
fun App(viewModel: StockPickerViewModel) {
    LongTermStockPickerTheme {
        val nav = rememberNavController()
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route.orEmpty()
        val tabs = listOf(
            NavTab("home", "主页"),
            NavTab("results", "排行榜"),
            NavTab("watchlist", "自选股"),
            NavTab("logic", "选股逻辑"),
        )
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            label = { Text(tab.label) },
                            icon = { Text(tab.label.take(1)) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "home",
                modifier = androidx.compose.ui.Modifier.padding(padding),
            ) {
                composable("home") {
                    HomeScreen(
                        viewModel = viewModel,
                        onGoResults = { nav.navigate("results") },
                    )
                }
                composable("results") {
                    ResultListScreen(
                        viewModel = viewModel,
                        onGoHome = { nav.navigate("home") },
                        onOpenDetail = { ts -> nav.navigate("detail/${ts}") },
                    )
                }
                composable("watchlist") {
                    WatchlistScreen(
                        viewModel = viewModel,
                        onOpenDetail = { ts -> nav.navigate("detail/${ts}") },
                    )
                }
                composable("logic") {
                    StrategyLogicScreen(viewModel = viewModel)
                }
                composable(
                    route = "detail/{ts}",
                    arguments = listOf(navArgument("ts") { type = NavType.StringType }),
                ) { entry ->
                    val ts = entry.arguments?.getString("ts").orEmpty()
                    StockDetailScreen(
                        viewModel = viewModel,
                        tsCode = ts,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}

private data class NavTab(val route: String, val label: String)
