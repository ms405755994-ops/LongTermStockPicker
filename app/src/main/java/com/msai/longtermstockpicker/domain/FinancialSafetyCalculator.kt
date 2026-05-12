package com.msai.longtermstockpicker.domain

import com.msai.longtermstockpicker.data.FinancialStatementData

data class FinancialSafetyBreakdown(
    val reportPeriod: String? = null,
    val shortDebtCoverage: Double? = null,
    val interestBearingDebtRatio: Double? = null,
    val operatingCashflowQuality: Double? = null,
    val debtToAssets: Double? = null,
    val shortDebtCoverageScore: Double = 60.0,
    val interestBearingDebtRatioScore: Double = 60.0,
    val operatingCashflowQualityScore: Double = 60.0,
    val debtToAssetsScore: Double = 60.0,
    val totalScore: Double = 60.0,
    val note: String = "财务数据缺失，暂用默认分",
)

object FinancialSafetyCalculator {

    /** Tushare daily `amount` 单位为千元；均额过低视为流动性风险 */
    private const val AMOUNT_AVG60_WARN_THRESHOLD = 30_000.0

    fun compute(
        dailiesAscending: List<Quote>,
        currentClose: Double,
    ): Pair<Double, List<String>> {
        val warnings = mutableListOf<String>()
        var score = 60.0

        val last60 = dailiesAscending.takeLast(60)
        if (last60.size >= 20) {
            val avgAmt = last60.map { it.amount }.average()
            if (avgAmt < AMOUNT_AVG60_WARN_THRESHOLD) {
                score -= 10.0
                warnings.add("近60日平均成交额偏低（流动性风险代理）")
            }
        }

        if (dailiesAscending.size >= 250) {
            val ref = dailiesAscending[dailiesAscending.size - 250].close
            if (ref > 0) {
                val drop = (currentClose - ref) / ref
                if (drop < -0.60) {
                    score -= 10.0
                    warnings.add("近250日跌幅超过60%（风险代理）")
                }
            }
        }

        if (currentClose < 2.0) {
            score -= 10.0
            warnings.add("当前价低于2元（风险代理）")
        }

        return score.coerceIn(0.0, 100.0) to warnings
    }

    fun avgAmountTooLowForRiskFlag(dailiesAscending: List<Quote>): Boolean {
        val last60 = dailiesAscending.takeLast(60)
        if (last60.size < 20) return false
        return last60.map { it.amount }.average() < AMOUNT_AVG60_WARN_THRESHOLD
    }

    fun computeFromFinancial(data: FinancialStatementData?): FinancialSafetyBreakdown {
        if (data == null) return FinancialSafetyBreakdown()

        val balance = data.balanceSheets.maxByOrNull { it.endDate }
        val indicator = data.finaIndicators.maxByOrNull { it.endDate }
        val reportPeriod = listOfNotNull(
            balance?.endDate,
            data.cashflows.maxByOrNull { it.endDate }?.endDate,
            data.incomes.maxByOrNull { it.endDate }?.endDate,
            indicator?.endDate,
        ).maxOrNull()

        val shortBorrow = balance?.shortBorrow.orZero()
        val dueOneYear = balance?.nonCurrentLiabDue1y.orZero()
        val shortDebt = shortBorrow + dueOneYear
        val shortDebtCoverage = ratio(balance?.moneyCap, shortDebt)

        val interestBearingDebt = shortBorrow +
            balance?.longBorrow.orZero() +
            balance?.bondPayable.orZero() +
            dueOneYear
        val interestBearingDebtRatio = ratio(interestBearingDebt, balance?.totalAssets)

        val cashflowSum = data.cashflows.sortedByDescending { it.endDate }.take(4).sumOf { it.netCashflowAct.orZero() }
        val netIncomeSum = data.incomes.sortedByDescending { it.endDate }.take(4).sumOf { it.netIncome.orZero() }
        val cashflowQuality = ratio(cashflowSum, netIncomeSum)

        val indicatorDebtRatio = indicator?.debtToAssets?.let { if (it > 1.0) it / 100.0 else it }
        val computedDebtRatio = ratio(balance?.totalLiab, balance?.totalAssets)
        val debtRatio = indicatorDebtRatio ?: computedDebtRatio

        val shortDebtScore = scoreShortDebtCoverage(shortDebtCoverage)
        val interestDebtScore = scoreInterestBearingDebtRatio(interestBearingDebtRatio)
        val cashflowScore = scoreCashflowQuality(cashflowQuality)
        val debtRatioScore = scoreDebtToAssets(debtRatio)
        val total = shortDebtScore * 0.35 +
            interestDebtScore * 0.25 +
            cashflowScore * 0.25 +
            debtRatioScore * 0.15

        val missing = listOf(
            shortDebtCoverage to "短债覆盖率",
            interestBearingDebtRatio to "有息负债率",
            cashflowQuality to "经营现金流质量",
            debtRatio to "资产负债率",
        ).mapNotNull { (value, label) -> if (value == null || !value.isFinite()) label else null }

        val note = if (missing.isEmpty()) {
            "基于最近一期或最近4期 Tushare 财务数据计算。"
        } else {
            "部分财务数据缺失，${missing.joinToString("、")}按60分处理。"
        }

        return FinancialSafetyBreakdown(
            reportPeriod = reportPeriod,
            shortDebtCoverage = shortDebtCoverage,
            interestBearingDebtRatio = interestBearingDebtRatio,
            operatingCashflowQuality = cashflowQuality,
            debtToAssets = debtRatio,
            shortDebtCoverageScore = shortDebtScore,
            interestBearingDebtRatioScore = interestDebtScore,
            operatingCashflowQualityScore = cashflowScore,
            debtToAssetsScore = debtRatioScore,
            totalScore = total.coerceIn(0.0, 100.0),
            note = note,
        )
    }

    private fun ratio(numerator: Double?, denominator: Double?): Double? {
        if (numerator == null || denominator == null || denominator == 0.0) return null
        return numerator / denominator
    }

    private fun ratio(numerator: Double?, denominator: Double): Double? {
        if (numerator == null || denominator == 0.0) return null
        return numerator / denominator
    }

    private fun scoreShortDebtCoverage(value: Double?): Double = when {
        value == null || !value.isFinite() -> 60.0
        value > 2.0 -> 100.0
        value >= 1.0 -> 80.0
        value >= 0.5 -> 60.0
        else -> 30.0
    }

    private fun scoreInterestBearingDebtRatio(value: Double?): Double = when {
        value == null || !value.isFinite() -> 60.0
        value < 0.20 -> 100.0
        value <= 0.40 -> 80.0
        value <= 0.60 -> 60.0
        else -> 30.0
    }

    private fun scoreCashflowQuality(value: Double?): Double = when {
        value == null || !value.isFinite() -> 60.0
        value > 1.0 -> 100.0
        value >= 0.5 -> 80.0
        value >= 0.0 -> 60.0
        else -> 30.0
    }

    private fun scoreDebtToAssets(value: Double?): Double = when {
        value == null || !value.isFinite() -> 60.0
        value < 0.40 -> 100.0
        value <= 0.60 -> 80.0
        value <= 0.75 -> 60.0
        else -> 30.0
    }

    private fun Double?.orZero(): Double = this ?: 0.0
}
