package com.msai.longtermstockpicker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.msai.longtermstockpicker.StockPickerViewModel
import com.msai.longtermstockpicker.ui.theme.LongTermStockPickerTheme

@Composable
fun App(viewModel: StockPickerViewModel) {
    LongTermStockPickerTheme {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    viewModel = viewModel,
                    onGoResults = { nav.navigate("results") },
                )
            }
            composable("results") {
                ResultListScreen(
                    viewModel = viewModel,
                    onGoHome = { nav.popBackStack("home", inclusive = false) },
                    onOpenDetail = { ts -> nav.navigate("detail/${ts}") },
                )
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
