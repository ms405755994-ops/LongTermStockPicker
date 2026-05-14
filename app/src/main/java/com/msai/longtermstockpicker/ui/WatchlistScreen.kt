package com.msai.longtermstockpicker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msai.longtermstockpicker.StockPickerViewModel
import com.msai.longtermstockpicker.WatchlistRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    viewModel: StockPickerViewModel,
    onOpenDetail: (String) -> Unit,
) {
    val rows by viewModel.watchlistRows.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshWatchlist()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自选股") },
                actions = {
                    TextButton(onClick = { viewModel.clearWatchlist() }) { Text("清空") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rows.isEmpty()) {
                item {
                    InfoCard("暂无自选股。可在排行榜中点击“加入自选”。")
                }
            }
            items(rows, key = { it.tsCode }) { row ->
                WatchlistCard(
                    row = row,
                    onOpenDetail = { onOpenDetail(row.tsCode) },
                    onRemove = { viewModel.removeFromWatchlist(row.tsCode) },
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WatchlistCard(
    row: WatchlistRow,
    onOpenDetail: () -> Unit,
    onRemove: () -> Unit,
) {
    val scoreText = row.latestScore?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "暂无最新评分"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail() },
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.tsCode, style = MaterialTheme.typography.titleMedium)
            Text(
                "${row.name.orDash()} ｜ ${row.industry.orDash()}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "最近评分日期：${row.latestTradeDate ?: "暂无最新评分"}\n" +
                    "最近总分：$scoreText\n" +
                    "最近信号等级：${row.latestSignalLevel ?: "暂无最新评分"}\n" +
                    "加入时间：${formatMillis(row.addedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onRemove) { Text("移除自选") }
            }
        }
    }
}

private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "—"

private fun formatMillis(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
