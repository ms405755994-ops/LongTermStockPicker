package com.msai.longtermstockpicker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msai.longtermstockpicker.StockPickerViewModel
import com.msai.longtermstockpicker.domain.ScoreResult
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultListScreen(
    viewModel: StockPickerViewModel,
    onGoHome: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val results by viewModel.results.collectAsState()
    val watchlistCodes by viewModel.watchlistCodes.collectAsState()
    var topLimit by remember { mutableStateOf(100) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(topLimit) {
        viewModel.loadLatestRanking(topLimit, navigate = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("中长线评分排行榜") },
                navigationIcon = {
                    TextButton(onClick = onGoHome) { Text("返回首页") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索股票代码或名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 200, 5000).forEach { limit ->
                        TextButton(
                            onClick = {
                                topLimit = limit
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (limit >= 5000) "全部" else "Top$limit")
                        }
                    }
                }
            }
            item { SummaryCard("云端结果查看模式 ｜ 本页仅展示已导入的 score_result") }
            if (results.isEmpty()) {
                item {
                    SummaryCard("暂无评分结果，请先同步云端结果")
                }
            }
            val filtered = results.filter {
                val q = query.trim()
                q.isBlank() || it.tsCode.contains(q, ignoreCase = true) || it.stockName.orEmpty().contains(q, ignoreCase = true)
            }.take(topLimit)
            items(filtered, key = { it.tsCode }) { row ->
                ResultRow(
                    item = row,
                    isWatchlisted = row.tsCode in watchlistCodes,
                    onClick = { onOpenDetail(row.tsCode) },
                    onToggleWatchlist = {
                        if (row.tsCode in watchlistCodes) {
                            viewModel.removeFromWatchlist(row.tsCode)
                        } else {
                            viewModel.addToWatchlist(row)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ResultRow(
    item: ScoreResult,
    isWatchlisted: Boolean,
    onClick: () -> Unit,
    onToggleWatchlist: () -> Unit,
) {
    val scoreText = when {
        item.errorMessage != null -> "失败"
        !item.isScored -> "—"
        else -> String.format(Locale.getDefault(), "%.1f", item.totalScore)
    }
    val tag = signalTag(item)
    val accent = signalAccent(tag.label)
    val name = item.stockName?.takeIf { it.isNotBlank() } ?: "—"
    val industry = item.industry?.takeIf { it.isNotBlank() } ?: "—"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.tsCode, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        "$name ｜ $industry",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accent.copy(alpha = 0.18f),
                ) {
                    Text(
                        tag.label,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                    )
                }
            }
            Text(
                "总分 $scoreText ｜ 信号等级 ${item.signalLevel}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onToggleWatchlist) {
                    Text(if (isWatchlisted) "移除自选" else "加入自选")
                }
            }
            item.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class SignalTag(val label: String)

private fun signalTag(item: ScoreResult): SignalTag {
    if (item.errorMessage != null) return SignalTag("异常/失败")
    return SignalTag(item.signalLevel)
}

private fun signalAccent(label: String): Color = when (label) {
    "核心观察" -> Color(0xFF1B5E20)
    "优先观察" -> Color(0xFF0D47A1)
    "低位潜伏" -> Color(0xFF00695C)
    "只观察" -> Color(0xFF6A1B9A)
    "剔除/不关注" -> Color(0xFFB71C1C)
    else -> Color(0xFF616161)
}
