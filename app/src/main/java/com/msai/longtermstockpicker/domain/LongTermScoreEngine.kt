package com.msai.longtermstockpicker.domain

import com.msai.longtermstockpicker.data.StockBasicInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object LongTermScoreEngine {

    private fun signalLevel(total: Double): String = when {
        total >= 85 -> "核心观察"
        total >= 75 -> "优先观察"
        total >= 65 -> "低位潜伏"
        total >= 55 -> "只观察"
        else -> "剔除/不关注"
    }

    private fun describeMacd(period: String, s: MacdStatus): String = when (s) {
        MacdStatus.GREEN_SHRINKING -> "${period}绿柱缩短"
        MacdStatus.GOLDEN_CROSS -> "${period}金叉"
        MacdStatus.RED_EXPANDING -> "${period}红柱放大"
        MacdStatus.RED_WEAKENING -> "${period}红柱减弱"
        MacdStatus.GREEN_EXPANDING -> "${period}绿柱放大"
        MacdStatus.DEAD_CROSS -> "${period}死叉"
        MacdStatus.NEUTRAL -> "${period}震荡"
    }

    private fun buildReason(
        priceScore: Double,
        m: MacdStatus,
        w: MacdStatus,
        d: MacdStatus,
        fin: Double,
        riskWarnings: List<String>,
    ): String {
        val parts = mutableListOf<String>()
        if (priceScore >= 70) {
            parts.add("当前价格处于统计窗口内的相对低位区")
        } else if (priceScore >= 55) {
            parts.add("价格位置中等偏低")
        }
        val macdBits = mutableListOf<String>()
        if (m == MacdStatus.GREEN_SHRINKING || m == MacdStatus.GOLDEN_CROSS) {
            macdBits.add(describeMacd("月线", m))
        }
        if (w == MacdStatus.GOLDEN_CROSS || w == MacdStatus.GREEN_SHRINKING) {
            macdBits.add(describeMacd("周线", w))
        }
        if (d == MacdStatus.GOLDEN_CROSS || d == MacdStatus.RED_EXPANDING) {
            macdBits.add(describeMacd("日线", d))
        }
        if (macdBits.isNotEmpty()) {
            parts.add(macdBits.joinToString("，") + "，具备一定底部修复/趋势特征（模型简化判断）")
        }
        if (fin >= 55) {
            parts.add("财务风险分正常")
        } else {
            parts.add("财务风险分偏低，需关注财报指标触发的扣分项")
        }
        if (riskWarnings.isNotEmpty()) {
            parts.add("风险提示：" + riskWarnings.joinToString("；"))
        }
        return if (parts.isEmpty()) {
            "模型未捕捉到强信号；建议结合基本面与资金面前瞻验证。"
        } else {
            parts.joinToString("；") + "。"
        }
    }

    fun score(
        tsCode: String,
        dailies: List<Quote>,
        basic: StockBasicInfo? = null,
        financial: FinancialSafetyBreakdown = FinancialSafetyBreakdown(),
        ownership: OwnershipInfo? = null,
        dailyDataSource: String? = null,
        financialDataSource: String? = null,
    ): ScoreResult {
        val sorted = dailies.sortedBy { it.tradeDate }
        val risk = mutableListOf<String>()
        val weekly = ResampleCalculator.toWeekly(sorted)
        val monthly = ResampleCalculator.toMonthly(sorted)
        val closes = sorted.map { it.close }
        val latest = sorted.lastOrNull()
        val current = latest?.close
        val windowLowestClose = closes.minOrNull()
        val ownershipInfo = OwnershipScoreCalculator.infoFor(tsCode, ownership)
        val hasTenYearData = hasAtLeastTenYears(sorted.firstOrNull()?.tradeDate, latest?.tradeDate)
        val dataWarning = if (hasTenYearData) null else "历史数据不足10年，评分可信度下降"

        if (sorted.size < 500) {
            return ScoreResult(
                tsCode = tsCode,
                isScored = false,
                exclusionReason = "历史数据不足（日线少于500条）",
                totalScore = 0.0,
                signalLevel = "—",
                stockName = basic?.name,
                industry = basic?.industry,
                listDate = basic?.listDate,
                market = basic?.market,
                dailyDataSource = dailyDataSource,
                financialDataSource = financialDataSource,
                pricePositionScore = null,
                macdMultiPeriodScore = null,
                financialSafetyScore = null,
                ownershipScore = ownershipInfo.ownershipScore,
                ownershipTierLabel = ownershipInfo.companyType,
                companyType = ownershipInfo.companyType,
                ownershipSource = ownershipInfo.source,
                ownershipRemark = ownershipInfo.remark,
                latestTradeDate = latest?.tradeDate,
                currentClose = current,
                windowLowestClose = windowLowestClose,
                dailyLineCount = sorted.size,
                weeklyLineCount = weekly.size,
                monthlyLineCount = monthly.size,
                pricePercentile = null,
                distanceToLow = null,
                monthlyMacd = null,
                weeklyMacd = null,
                dailyMacd = null,
                reason = "日线数据不足500条，未参与评分。",
                riskWarnings = emptyList(),
                hasTenYearData = hasTenYearData,
                dataWarning = dataWarning,
            )
        }

        val currentClose = current ?: 0.0
        val minWin = closes.minOrNull() ?: 0.0
        if (minWin <= 0.0 || !minWin.isFinite()) {
            return ScoreResult(
                tsCode = tsCode,
                isScored = false,
                exclusionReason = "近窗口最低价异常，已剔除",
                totalScore = 0.0,
                signalLevel = "剔除/不关注",
                stockName = basic?.name,
                industry = basic?.industry,
                listDate = basic?.listDate,
                market = basic?.market,
                dailyDataSource = dailyDataSource,
                financialDataSource = financialDataSource,
                pricePositionScore = null,
                macdMultiPeriodScore = null,
                financialSafetyScore = null,
                ownershipScore = ownershipInfo.ownershipScore,
                ownershipTierLabel = ownershipInfo.companyType,
                companyType = ownershipInfo.companyType,
                ownershipSource = ownershipInfo.source,
                ownershipRemark = ownershipInfo.remark,
                latestTradeDate = latest?.tradeDate,
                currentClose = current,
                windowLowestClose = windowLowestClose,
                dailyLineCount = sorted.size,
                weeklyLineCount = weekly.size,
                monthlyLineCount = monthly.size,
                pricePercentile = null,
                distanceToLow = null,
                monthlyMacd = null,
                weeklyMacd = null,
                dailyMacd = null,
                reason = "最低价异常（为0或非有限值），模型剔除。",
                riskWarnings = emptyList(),
                hasTenYearData = hasTenYearData,
                dataWarning = dataWarning,
            )
        }

        if (currentClose <= 1.0) {
            risk.add("当前价不高于1元（风险提示）")
        }
        if (FinancialSafetyCalculator.avgAmountTooLowForRiskFlag(sorted)) {
            risk.add("近60日平均成交额过低（风险提示）")
        }
        dataWarning?.let { risk.add(it) }

        val macdD = MacdCalculator.calculateMacd(closes)
        val macdW = MacdCalculator.calculateMacd(weekly.map { it.close })
        val macdM = MacdCalculator.calculateMacd(monthly.map { it.close })

        val stD = MacdCalculator.analyzeMacdStatus(macdD)
        val stW = MacdCalculator.analyzeMacdStatus(macdW)
        val stM = MacdCalculator.analyzeMacdStatus(macdM)

        val macdScore = MacdCalculator.macdMultiPeriodScore(stM, stW, stD)
        val priceBreakdown = PricePositionCalculator.compute(closes, currentClose)
        val finScore = financial.totalScore

        val ownScore = ownershipInfo.ownershipScore

        val total = priceBreakdown.score * 0.30 +
            macdScore * 0.35 +
            finScore * 0.25 +
            ownScore * 0.10

        val signal = signalLevel(total)
        val reason = buildReason(priceBreakdown.score, stM, stW, stD, finScore, risk)

        return ScoreResult(
            tsCode = tsCode,
            isScored = true,
            exclusionReason = null,
            totalScore = total,
            signalLevel = signal,
            stockName = basic?.name,
            industry = basic?.industry,
            listDate = basic?.listDate,
            market = basic?.market,
            dailyDataSource = dailyDataSource,
            financialDataSource = financialDataSource,
            pricePositionScore = priceBreakdown.score,
            macdMultiPeriodScore = macdScore,
            financialSafetyScore = finScore,
            financialReportPeriod = financial.reportPeriod,
            shortDebtCoverage = financial.shortDebtCoverage,
            interestBearingDebtRatio = financial.interestBearingDebtRatio,
            operatingCashflowQuality = financial.operatingCashflowQuality,
            debtToAssets = financial.debtToAssets,
            financialRiskNote = financial.note,
            ownershipScore = ownScore,
            ownershipTierLabel = ownershipInfo.companyType,
            companyType = ownershipInfo.companyType,
            ownershipSource = ownershipInfo.source,
            ownershipRemark = ownershipInfo.remark,
            latestTradeDate = latest?.tradeDate,
            currentClose = currentClose,
            windowLowestClose = windowLowestClose,
            dailyLineCount = sorted.size,
            weeklyLineCount = weekly.size,
            monthlyLineCount = monthly.size,
            pricePercentile = priceBreakdown.pricePercentile,
            distanceToLow = priceBreakdown.distanceToLow,
            monthlyMacd = stM,
            weeklyMacd = stW,
            dailyMacd = stD,
            reason = reason,
            riskWarnings = risk.toList(),
            hasTenYearData = hasTenYearData,
            dataWarning = dataWarning,
        )
    }

    private fun hasAtLeastTenYears(first: String?, latest: String?): Boolean {
        if (first.isNullOrBlank() || latest.isNullOrBlank()) return false
        return runCatching {
            val firstDate = LocalDate.parse(first, DateTimeFormatter.BASIC_ISO_DATE)
            val latestDate = LocalDate.parse(latest, DateTimeFormatter.BASIC_ISO_DATE)
            !firstDate.isAfter(latestDate.minusYears(10).plusDays(7))
        }.getOrDefault(false)
    }
}
