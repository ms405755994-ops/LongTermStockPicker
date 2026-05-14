package com.msai.longtermstockpicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msai.longtermstockpicker.StockPickerViewModel
import com.msai.longtermstockpicker.domain.MacdStatus
import com.msai.longtermstockpicker.domain.ScoreResult
import com.msai.longtermstockpicker.ui.components.ScoreCard
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    viewModel: StockPickerViewModel,
    tsCode: String,
    onBack: () -> Unit,
    onOpenLogic: () -> Unit,
) {
    val results by viewModel.results.collectAsState()
    val watchlistCodes by viewModel.watchlistCodes.collectAsState()
    val item = results.firstOrNull { it.tsCode == tsCode }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.tsCode ?: tsCode) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回排行榜") }
                },
            )
        },
    ) { padding ->
        if (item == null) {
            Text("未找到该股票结果", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScoreCard(
                "股票基础信息",
                "股票代码：${item.tsCode}\n" +
                    "股票名称：${item.stockName.displayText()}\n" +
                    "行业：${item.industry.displayText()}\n" +
                    "上市日期：${item.listDate.displayText()}\n" +
                    "市场类型：${item.market.displayText()}",
            )
            ScoreCard("总分", fmt(item.totalScore, item))
            ScoreCard("信号等级", item.signalLevel)
            item.errorMessage?.let { ScoreCard("错误", it) }
            item.exclusionReason?.let { ScoreCard("说明", it) }

            if (item.tsCode in watchlistCodes) {
                OutlinedButton(onClick = { viewModel.removeFromWatchlist(item.tsCode) }) {
                    Text("移除自选")
                }
            } else {
                Button(onClick = { viewModel.addToWatchlist(item) }) {
                    Text("加入自选")
                }
            }

            ScoreCard(
                "评分公式",
                "总分 = 价格低位分×30% + MACD多周期分×35% + 财务安全分×25% + 企业性质分×10%",
            )

            ScoreCard("价格低位分", fmt1(item.pricePositionScore))
            ScoreCard("MACD 多周期分", fmt1(item.macdMultiPeriodScore))
            ScoreCard(
                "财务安全分",
                "${fmt1(item.financialSafetyScore)}\n${item.financialRiskNote.displayText()}",
            )
            ScoreCard(
                "财务指标拆解",
                "财务报告期：${item.financialReportPeriod.displayText()}\n" +
                    "短债覆盖率：${fmtRatio(item.shortDebtCoverage)}\n" +
                    "有息负债率：${fmtPercent(item.interestBearingDebtRatio)}\n" +
                    "经营现金流质量：${fmtRatio(item.operatingCashflowQuality)}\n" +
                    "资产负债率：${fmtPercent(item.debtToAssets)}\n" +
                    "财务安全总分：${fmt1(item.financialSafetyScore)}\n" +
                    "财务风险说明：${item.financialRiskNote.displayText()}",
            )
            ScoreCard(
                "企业性质",
                item.ownershipScore?.let { o ->
                    String.format(
                        Locale.getDefault(),
                        "企业性质：%s\n企业性质分：%.1f\n数据来源：%s\n备注：%s",
                        item.companyType.displayText(),
                        o,
                        item.ownershipSource.displayText(),
                        item.ownershipRemark.displayText(),
                    )
                } ?: "—",
            )

            ScoreCard("最新交易日", item.latestTradeDate ?: "—")
            ScoreCard(
                "10年数据窗口",
                "是否满足10年数据：${if (item.hasTenYearData) "是" else "否"}\n" +
                    "提示：${item.dataWarning.displayText()}",
            )
            ScoreCard("当前收盘价", fmt2(item.currentClose))
            ScoreCard("10年窗口最低价", fmt2(item.windowLowestClose))
            ScoreCard("日线数据条数", item.dailyLineCount?.toString() ?: "—")
            ScoreCard("周线数据条数", item.weeklyLineCount?.toString() ?: "—")
            ScoreCard("月线数据条数", item.monthlyLineCount?.toString() ?: "—")
            ScoreCard(
                "数据来源",
                "daily 数据来源：${item.dailyDataSource.displayText()}\n" +
                    "financial 数据来源：${item.financialDataSource.displayText()}",
            )

            val pct = item.pricePercentile?.let { String.format(Locale.getDefault(), "%.1f%%", it * 100) } ?: "—"
            ScoreCard("10年（窗口）价格分位", pct)
            val dist = item.distanceToLow?.let { String.format(Locale.getDefault(), "%.2f%%", it * 100) } ?: "—"
            ScoreCard("距离近10年（窗口）最低价", dist)

            ScoreCard("月线 MACD 状态", item.monthlyMacd.macdZh())
            ScoreCard("周线 MACD 状态", item.weeklyMacd.macdZh())
            ScoreCard("日线 MACD 状态", item.dailyMacd.macdZh())

            Text("入选原因", style = MaterialTheme.typography.titleSmall)
            Text(item.reason, style = MaterialTheme.typography.bodyMedium)

            if (item.riskWarnings.isNotEmpty()) {
                Text("风险提示", style = MaterialTheme.typography.titleSmall)
                item.riskWarnings.forEach { w ->
                    Text("· $w", style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedButton(onClick = onOpenLogic) {
                Text("查看选股逻辑")
            }
        }
    }
}

private fun fmt(total: Double, item: ScoreResult): String = when {
    item.errorMessage != null -> "失败"
    !item.isScored -> "—"
    else -> String.format(Locale.getDefault(), "%.2f", total)
}

private fun fmt1(value: Double?): String = value?.let {
    String.format(Locale.getDefault(), "%.1f", it)
} ?: "—"

private fun fmt2(value: Double?): String = value?.let {
    String.format(Locale.getDefault(), "%.2f", it)
} ?: "—"

private fun fmtRatio(value: Double?): String = value?.let {
    String.format(Locale.getDefault(), "%.2f", it)
} ?: "—"

private fun fmtPercent(value: Double?): String = value?.let {
    String.format(Locale.getDefault(), "%.1f%%", it * 100)
} ?: "—"

private fun String?.displayText(): String = this?.takeIf { it.isNotBlank() } ?: "—"

private fun MacdStatus?.macdZh(): String = when (this) {
    null -> "—"
    MacdStatus.GREEN_EXPANDING -> "绿柱放大（弱）"
    MacdStatus.GREEN_SHRINKING -> "绿柱缩短（底部修复）"
    MacdStatus.GOLDEN_CROSS -> "金叉"
    MacdStatus.RED_EXPANDING -> "红柱放大"
    MacdStatus.RED_WEAKENING -> "红柱缩短/减弱"
    MacdStatus.DEAD_CROSS -> "死叉"
    MacdStatus.NEUTRAL -> "中性/震荡"
}
