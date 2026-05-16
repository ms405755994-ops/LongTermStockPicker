package com.msai.longtermstockpicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.msai.longtermstockpicker.StockPickerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyLogicScreen(viewModel: StockPickerViewModel) {
    val status by viewModel.viewerStatus.collectAsState()
    val logic by viewModel.strategyLogic.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("中长线选股逻辑") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LogicCard(
                    title = "当前数据",
                    body = "模型版本：${logic.modelVersion ?: status.modelVersion ?: "暂无"}\n" +
                        "逻辑更新时间：${logic.generatedAt ?: "暂无"}\n" +
                        "逻辑同步时间：${logic.syncedAt?.let { formatMillis(it) } ?: "暂无"}\n" +
                        "最新评分交易日：${logic.tradeDate ?: status.latestTradeDate ?: "暂无"}",
                )
            }

            if (logic.sections.isEmpty()) {
                item {
                    LogicCard(
                        title = "暂无云端选股逻辑",
                        body = "请先在主页点击“同步 GitHub Top100”。同步成功后，手机端会自动读取云端最新选股逻辑并缓存到本地。",
                        emphasized = true,
                    )
                }
            } else {
                items(logic.sections, key = { it.title }) { section ->
                    LogicCard(
                        title = section.title,
                        body = section.body,
                        emphasized = section.emphasized,
                        monospace = section.kind == "formula",
                    )
                }
            }

            logic.errorMessage?.let { message ->
                item {
                    LogicCard(
                        title = "同步提示",
                        body = message,
                        emphasized = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogicCard(
    title: String,
    body: String,
    emphasized: Boolean = false,
    monospace: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(if (emphasized) 3.dp else 1.dp),
        colors = if (emphasized) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else if (monospace) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    emphasized -> MaterialTheme.colorScheme.onErrorContainer
                    monospace -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = when {
                    emphasized -> MaterialTheme.colorScheme.onErrorContainer
                    monospace -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

private fun formatMillis(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
