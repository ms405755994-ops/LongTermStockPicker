package com.msai.longtermstockpicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
                    body = "模型版本：${status.modelVersion ?: "暂无"}\n" +
                        "最新同步时间：${status.lastSyncAt?.let { formatMillis(it) } ?: "暂无"}\n" +
                        "最新评分交易日：${status.latestTradeDate ?: "暂无"}",
                )
            }
            item {
                LogicCard(
                    title = "模型定位",
                    body = "本模型是“中长线低位反转选股模型”，主要用于从全市场股票中筛选：\n" +
                        "- 长期价格处于相对低位\n" +
                        "- 月线 / 周线 / 日线 MACD 有修复迹象\n" +
                        "- 财务风险相对可控\n" +
                        "- 企业性质有一定确定性\n" +
                        "- 适合进入中长线观察池的股票\n\n" +
                        "提示：本模型只用于研究和观察，不构成投资建议。",
                    emphasized = true,
                )
            }
            item {
                LogicCard(
                    title = "数据来源",
                    body = "电脑端读取本地 PostgreSQL 数据库，主要数据包括：\n" +
                        "- 股票基础信息 stock_basic\n" +
                        "- 日线行情 daily_quotes\n" +
                        "- 财务数据 financial_snapshot / fina_indicator\n" +
                        "- 企业性质映射 company_ownership\n" +
                        "- 模型评分结果 score_result\n\n" +
                        "手机端只读取 GitHub 上的 Top100 结果，不直接请求 Tushare，不连接 PostgreSQL。",
                )
            }
            item {
                LogicCard(
                    title = "硬过滤逻辑",
                    body = "以下股票优先过滤或降低优先级：\n" +
                        "- ST / *ST 股票\n" +
                        "- 上市不足 5 年\n" +
                        "- 日线数据严重不足\n" +
                        "- 当前价格异常或接近退市风险\n" +
                        "- 财务数据严重缺失时降低可信度\n" +
                        "- 历史数据不足 10 年时会显示风险提示",
                )
            }
            item {
                FormulaCard()
            }
            item {
                LogicCard(
                    title = "价格低位分逻辑",
                    body = "使用最近 10 年历史收盘价计算：\n" +
                        "- 当前价格分位\n" +
                        "- 距离 10 年最低价\n" +
                        "- 10 年窗口最低价\n\n" +
                        "评分参考：\n" +
                        "- 价格分位 ≤ 10%：高分\n" +
                        "- 价格分位 10%-20%：较高分\n" +
                        "- 价格分位 20%-30%：中高分\n" +
                        "- 价格分位 30%-40%：中等分\n" +
                        "- 高于 40%：低位优势减弱\n\n" +
                        "如果历史数据不足 10 年，详情页会提示：“历史数据不足10年，评分可信度下降”。",
                )
            }
            item {
                LogicCard(
                    title = "MACD 多周期逻辑",
                    body = "MACD 使用三个周期：\n" +
                        "- 月线 MACD：权重 45%\n" +
                        "- 周线 MACD：权重 35%\n" +
                        "- 日线 MACD：权重 20%\n\n" +
                        "优先关注：\n" +
                        "- 月线绿柱缩短\n" +
                        "- 周线金叉\n" +
                        "- 日线回踩后重新走强\n" +
                        "- 红柱刚开始放大但股价仍在低位\n\n" +
                        "风险状态：\n" +
                        "- 月线死叉\n" +
                        "- 周线死叉\n" +
                        "- 日线绿柱继续放大\n" +
                        "- 红柱持续缩短",
                )
            }
            item {
                LogicCard(
                    title = "财务安全分逻辑",
                    body = "财务安全分主要参考：\n" +
                        "- 短债覆盖率\n" +
                        "- 有息负债率\n" +
                        "- 经营现金流质量\n" +
                        "- 资产负债率\n\n" +
                        "权重：\n" +
                        "- 短债覆盖率 × 35%\n" +
                        "- 有息负债率 × 25%\n" +
                        "- 经营现金流质量 × 25%\n" +
                        "- 资产负债率 × 15%\n\n" +
                        "如果财务数据缺失：\n" +
                        "- 默认财务分为 60\n" +
                        "- 详情页显示“财务数据缺失，暂用默认分”",
                )
            }
            item {
                LogicCard(
                    title = "企业性质分逻辑",
                    body = "企业性质分参考：\n" +
                        "- 中央国资\n" +
                        "- 省属国资\n" +
                        "- 地方国资\n" +
                        "- 国资参股\n" +
                        "- 优质民企\n" +
                        "- 普通民企\n" +
                        "- 未知\n\n" +
                        "企业性质不是决定性因素，只作为中长线确定性加权。",
                )
            }
            item {
                LogicCard(
                    title = "信号等级解释",
                    body = "根据信号总分分层：\n" +
                        "- 85 分以上：核心观察\n" +
                        "- 75-85 分：优先观察\n" +
                        "- 65-75 分：低位潜伏\n" +
                        "- 55-65 分：只观察\n" +
                        "- 55 分以下：剔除 / 不关注\n\n" +
                        "注意：信号等级不是买入建议，只代表模型观察优先级。",
                )
            }
            item {
                LogicCard(
                    title = "Top100 输出逻辑",
                    body = "电脑端每天定时运行：\n" +
                        "- 17:00 收盘后更新\n" +
                        "- 03:00 凌晨补跑\n\n" +
                        "电脑端会：\n" +
                        "- 增量更新数据\n" +
                        "- 全市场评分\n" +
                        "- 只输出总分排名前 100 只\n" +
                        "- 上传 latest_score_top100.json 到 GitHub\n\n" +
                        "手机端点击“同步 GitHub Top100”后读取最新结果。",
                )
            }
            item {
                LogicCard(
                    title = "自选股说明",
                    body = "自选股只保存在手机本地数据库。\n" +
                        "用户可以在排行榜或详情页将股票加入自选。\n" +
                        "同步新数据不会清空自选股。\n" +
                        "如果自选股不在最新 Top100 中，仍然保留在自选股页，但可能显示“暂无最新评分”。",
                )
            }
            item {
                LogicCard(
                    title = "风险提示",
                    body = "本模型存在以下限制：\n" +
                        "- 历史数据不足 10 年会影响低位判断\n" +
                        "- 财务数据缺失会影响资金风险判断\n" +
                        "- MACD 是滞后指标\n" +
                        "- 低位不代表马上反转\n" +
                        "- 模型结果需要结合人工判断\n" +
                        "- 不构成投资建议",
                    emphasized = true,
                )
            }
        }
    }
}

@Composable
private fun FormulaCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("总分公式", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "总分 = 价格低位分 × 30%\n" +
                    "     + MACD 多周期分 × 35%\n" +
                    "     + 财务安全分 × 25%\n" +
                    "     + 企业性质分 × 10%",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "价格低位分判断当前股价是否处于 5-10 年历史低位；MACD 多周期分判断月线、周线、日线是否出现底部修复；财务安全分判断短债、长债、现金流和资产负债风险；企业性质分判断央国资、省国资、地方国资、民企等确定性。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun LogicCard(
    title: String,
    body: String,
    emphasized: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(if (emphasized) 3.dp else 1.dp),
        colors = if (emphasized) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (emphasized) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (emphasized) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatMillis(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
