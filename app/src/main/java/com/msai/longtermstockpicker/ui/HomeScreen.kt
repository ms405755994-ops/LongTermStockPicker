package com.msai.longtermstockpicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msai.longtermstockpicker.BuildConfig
import com.msai.longtermstockpicker.StockPickerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val ymd: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: StockPickerViewModel,
    onGoResults: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val token = BuildConfig.TUSHARE_TOKEN
    val cloudResultsUrl = BuildConfig.CLOUD_RESULTS_URL
    val defaultEnd = remember { LocalDate.now().format(ymd) }
    val defaultStart = remember { LocalDate.now().minusYears(10).format(ymd) }

    var startDate by remember { mutableStateOf(defaultStart) }
    var endDate by remember { mutableStateOf(defaultEnd) }
    var useCache by remember { mutableStateOf(true) }
    var forceRefresh by remember { mutableStateOf(false) }
    val fullMarketScan = true
    var scanCountText by remember { mutableStateOf("100") }

    val progress by viewModel.progress.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val results by viewModel.results.collectAsState()
    val scanSession by viewModel.scanSession.collectAsState()
    val retrySummary by viewModel.retryQueueSummary.collectAsState()

    LaunchedEffect(lastError) {
        val msg = lastError ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearError()
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToResults.collect {
            onGoResults()
        }
    }

    LaunchedEffect(scanSession?.status) {
        while (scanSession?.status == "running") {
            delay(1500)
            viewModel.refreshScanStateFromDisk()
        }
    }

    val progressSnapshot = progress
    val running = progressSnapshot != null && progressSnapshot.detail != "完成" ||
        scanSession?.status == "running"

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("中长线低位反转选股") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (token.isBlank()) {
                Text(
                    "请先在 local.properties 配置 TUSHARE_TOKEN",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("全市场扫描模式")
                Switch(checked = true, onCheckedChange = {}, enabled = false)
            }

            Text(
                "手机端逐只请求 Tushare 数据，扫描数量越大耗时越长，建议先用100只以内测试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(20, 50, 100).forEach { count ->
                    OutlinedButton(
                        onClick = { scanCountText = count.toString() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${count}只")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(200, 500).forEach { count ->
                    OutlinedButton(
                        onClick = { scanCountText = count.toString() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${count}只")
                    }
                }
                OutlinedTextField(
                    value = scanCountText,
                    onValueChange = { scanCountText = sanitizeScanCountText(it) },
                    label = { Text("自定义数量") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Text(
                "当前选择：本次最多扫描 ${normalizeScanCount(scanCountText)} 只",
                style = MaterialTheme.typography.bodyMedium,
            )

            val session = scanSession
            val hasUnfinished = session?.let { it.status == "running" || it.status == "paused" } == true
            Text(
                "未完成扫描任务：${if (hasUnfinished) "存在" else "无"}\n" +
                    "上次扫描进度：${session?.cursorIndex ?: 0}/${session?.candidateCodes?.size ?: 0}\n" +
                    "状态：${session?.status ?: "idle"}\n" +
                    "成功 ${session?.successCount ?: 0}，失败 ${session?.failedCount ?: 0}，跳过 ${session?.skippedCount ?: 0}\n" +
                    "当前失败队列数量：${retrySummary.totalCount}，可重试数量：${retrySummary.retryableCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("开始日期 yyyyMMdd") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("结束日期 yyyyMMdd") },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("使用本地缓存")
                Switch(checked = useCache, onCheckedChange = { useCache = it })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("强制重新拉取")
                Switch(checked = forceRefresh, onCheckedChange = { forceRefresh = it })
            }

            OutlinedButton(
                onClick = { viewModel.loadCloudResults(cloudResultsUrl) },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("读取云端扫描结果")
            }

            OutlinedButton(
                onClick = {
                    val n = viewModel.clearAllDailyJsonCache()
                    scope.launch { snackbar.showSnackbar("已清空日线缓存 $n 个文件（cache/daily）") }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清空缓存")
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.pauseScan() },
                    enabled = running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("暂停扫描")
                }
                OutlinedButton(
                    onClick = { viewModel.cancelScan() },
                    enabled = running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消任务")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (token.isBlank()) {
                            scope.launch { snackbar.showSnackbar("请先在 local.properties 配置 TUSHARE_TOKEN") }
                            return@OutlinedButton
                        }
                        viewModel.continueLastScan(
                            token = token,
                            startDate = startDate,
                            useLocalCache = useCache,
                            forceRefresh = forceRefresh,
                        )
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("继续上次扫描")
                }
            }

            OutlinedButton(
                onClick = { viewModel.clearScanProgress() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清空扫描进度")
            }

            OutlinedButton(
                onClick = {
                    if (token.isBlank()) {
                        scope.launch { snackbar.showSnackbar("请先在 local.properties 配置 TUSHARE_TOKEN") }
                        return@OutlinedButton
                    }
                    viewModel.retryFailedItems(
                        token = token,
                        startDate = startDate,
                        endDate = endDate,
                        useLocalCache = useCache,
                        forceRefresh = forceRefresh,
                    )
                },
                enabled = !running && retrySummary.retryableCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重试失败项")
            }

            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (progressSnapshot != null) {
                    Text(
                        "进度：${progressSnapshot.currentIndex}/${progressSnapshot.total}  当前：${progressSnapshot.currentCode}\n" +
                            "成功 ${progressSnapshot.successCount}，失败 ${progressSnapshot.failedCount}，跳过 ${progressSnapshot.skippedCount}",
                    )
                } else {
                    Text(
                        "进度：${session?.cursorIndex ?: 0}/${session?.candidateCodes?.size ?: 0}\n" +
                            "成功 ${session?.successCount ?: 0}，失败 ${session?.failedCount ?: 0}，跳过 ${session?.skippedCount ?: 0}",
                    )
                }
            }

            Button(
                onClick = {
                    if (token.isBlank()) {
                        scope.launch { snackbar.showSnackbar("请先在 local.properties 配置 TUSHARE_TOKEN") }
                        return@Button
                    }
                    if (startDate.length != 8 || endDate.length != 8) {
                        scope.launch { snackbar.showSnackbar("日期必须为8位 yyyyMMdd") }
                        return@Button
                    }
                    viewModel.runBatch(
                        token = token,
                        codes = emptyList(),
                        startDate = startDate,
                        endDate = endDate,
                        useLocalCache = useCache,
                        forceRefresh = forceRefresh,
                        fullMarketScan = fullMarketScan,
                        maxScanCount = normalizeScanCount(scanCountText),
                    )
                },
                enabled = !running && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开始计算")
            }

            Button(
                onClick = onGoResults,
                enabled = results.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("查看排行榜")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun sanitizeScanCountText(input: String): String {
    val digits = input.filter { it.isDigit() }
    if (digits.isBlank()) return ""
    val count = digits.toIntOrNull() ?: 1000
    return count.coerceIn(1, 1000).toString()
}

private fun normalizeScanCount(input: String): Int {
    val count = input.toIntOrNull() ?: 100
    return count.coerceIn(1, 1000)
}
