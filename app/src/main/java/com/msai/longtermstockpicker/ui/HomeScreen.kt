package com.msai.longtermstockpicker.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msai.longtermstockpicker.StockPickerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: StockPickerViewModel,
    onGoResults: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val status by viewModel.viewerStatus.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val importResult by viewModel.lastImportResult.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()

    LaunchedEffect(lastError) {
        val msg = lastError ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearError()
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToResults.collect { onGoResults() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("中长线选股结果查看器") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("当前模式：云端计算 / GitHub同步 / 手机端查看", style = MaterialTheme.typography.titleMedium)
            Text(
                "最新数据更新时间：${status.generatedAt ?: "—"}\n" +
                    "最新评分交易日：${status.latestTradeDate ?: "—"}\n" +
                    "已导入股票数量：${status.importedCount}\n" +
                    "模型版本：${status.modelVersion ?: "—"}\n" +
                    "上次同步时间：${status.lastSyncAt?.let { formatMillis(it) } ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            importResult?.let {
                Text(
                    "最近导入：${it.generatedAt}\n评分交易日：${it.tradeDate}\n导入数量：${it.importedCount}\n模型版本：${it.modelVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = { viewModel.syncComputerResults() },
                enabled = !syncStatus.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (syncStatus.isLoading) "正在下载" else "同步 GitHub Top100") }

            if (syncStatus.message.isNotBlank()) {
                Text(
                    "同步状态：${syncStatus.message}\n" +
                        "评分日期：${syncStatus.tradeDate ?: "—"}\n" +
                        "导入数量：${syncStatus.importedCount ?: "—"}\n" +
                        "模型版本：${syncStatus.modelVersion ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncStatus.message == "下载成功") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }

            Button(
                onClick = { viewModel.loadLatestRanking(100) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查看最新排行榜") }

            Spacer(Modifier.height(16.dp))
            Text(
                "手机端只同步 GitHub Top100 结果和选股逻辑，并保存到本地 Room。手机端不请求 Tushare，也不需要 Token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

private fun formatMillis(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
