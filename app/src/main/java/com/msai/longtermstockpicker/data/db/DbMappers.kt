package com.msai.longtermstockpicker.data.db

import com.msai.longtermstockpicker.data.StockBasicInfo
import com.msai.longtermstockpicker.domain.MacdStatus
import com.msai.longtermstockpicker.domain.Quote
import com.msai.longtermstockpicker.domain.ScoreResult

fun StockBasicInfo.toEntity(updatedAt: Long = System.currentTimeMillis()): StockBasicEntity {
    val upperName = name.uppercase()
    return StockBasicEntity(
        tsCode = tsCode,
        name = name,
        industry = industry.takeIf { it.isNotBlank() },
        listDate = listDate.takeIf { it.isNotBlank() },
        market = market.takeIf { it.isNotBlank() },
        isSt = upperName.contains("*ST") || upperName.contains("ST"),
        updatedAt = updatedAt,
    )
}

fun StockBasicEntity.toModel(): StockBasicInfo = StockBasicInfo(
    tsCode = tsCode,
    name = name,
    industry = industry.orEmpty(),
    listDate = listDate.orEmpty(),
    market = market.orEmpty(),
)

fun Quote.toEntity(): DailyQuoteEntity = DailyQuoteEntity(
    tsCode = tsCode,
    tradeDate = tradeDate,
    open = open,
    high = high,
    low = low,
    close = close,
    preClose = preClose,
    change = change,
    pctChg = pctChg,
    vol = vol,
    amount = amount,
)

fun DailyQuoteEntity.toModel(): Quote = Quote(
    tsCode = tsCode,
    tradeDate = tradeDate,
    open = open ?: 0.0,
    high = high ?: 0.0,
    low = low ?: 0.0,
    close = close ?: 0.0,
    preClose = preClose,
    change = change,
    pctChg = pctChg,
    vol = vol ?: 0.0,
    amount = amount ?: 0.0,
)

fun ScoreResult.toEntity(tradeDate: String = latestTradeDate.orEmpty(), updatedAt: Long = System.currentTimeMillis()): ScoreResultEntity =
    ScoreResultEntity(
        tsCode = tsCode,
        tradeDate = tradeDate,
        name = stockName,
        industry = industry,
        totalScore = totalScore,
        signalLevel = signalLevel,
        pricePositionScore = pricePositionScore ?: 0.0,
        macdMultiPeriodScore = macdMultiPeriodScore ?: 0.0,
        financialSafetyScore = financialSafetyScore ?: 0.0,
        financialReportPeriod = financialReportPeriod,
        shortDebtCoverage = shortDebtCoverage,
        interestBearingDebtRatio = interestBearingDebtRatio,
        cashflowQuality = operatingCashflowQuality,
        debtToAssets = debtToAssets,
        financialRiskNote = financialRiskNote,
        ownershipScore = ownershipScore ?: 0.0,
        companyType = companyType ?: ownershipTierLabel,
        ownershipSource = ownershipSource,
        ownershipRemark = ownershipRemark,
        pricePercentile = pricePercentile,
        distanceToLow = distanceToLow,
        monthlyMacdStatus = monthlyMacd?.name,
        weeklyMacdStatus = weeklyMacd?.name,
        dailyMacdStatus = dailyMacd?.name,
        reason = reason,
        hasTenYearData = hasTenYearData,
        dataWarning = dataWarning,
        currentClose = currentClose,
        tenYearLow = windowLowestClose,
        dailyCount = dailyLineCount,
        weeklyCount = weeklyLineCount,
        monthlyCount = monthlyLineCount,
        updatedAt = updatedAt,
    )

fun ScoreResultEntity.toScoreResult(): ScoreResult = ScoreResult(
    tsCode = tsCode,
    isScored = true,
    exclusionReason = null,
    totalScore = totalScore,
    signalLevel = signalLevel,
    stockName = name,
    industry = industry,
    dailyDataSource = "数据库",
    financialDataSource = "数据库",
    pricePositionScore = pricePositionScore,
    macdMultiPeriodScore = macdMultiPeriodScore,
    financialSafetyScore = financialSafetyScore,
    financialReportPeriod = financialReportPeriod,
    shortDebtCoverage = shortDebtCoverage,
    interestBearingDebtRatio = interestBearingDebtRatio,
    operatingCashflowQuality = cashflowQuality,
    debtToAssets = debtToAssets,
    financialRiskNote = financialRiskNote,
    ownershipScore = ownershipScore,
    ownershipTierLabel = companyType,
    companyType = companyType,
    ownershipSource = ownershipSource,
    ownershipRemark = ownershipRemark,
    latestTradeDate = tradeDate,
    currentClose = currentClose,
    windowLowestClose = tenYearLow,
    dailyLineCount = dailyCount,
    weeklyLineCount = weeklyCount,
    monthlyLineCount = monthlyCount,
    pricePercentile = pricePercentile,
    distanceToLow = distanceToLow,
    monthlyMacd = monthlyMacdStatus.toMacdStatus(),
    weeklyMacd = weeklyMacdStatus.toMacdStatus(),
    dailyMacd = dailyMacdStatus.toMacdStatus(),
    reason = reason ?: "数据库评分结果。",
    riskWarnings = dataWarning?.let { listOf(it) } ?: emptyList(),
    hasTenYearData = hasTenYearData,
    dataWarning = dataWarning,
)

private fun String?.toMacdStatus(): MacdStatus? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { MacdStatus.valueOf(value) }.getOrNull() ?: when {
        value.contains("绿柱缩短") -> MacdStatus.GREEN_SHRINKING
        value.contains("绿柱放大") -> MacdStatus.GREEN_EXPANDING
        value.contains("金叉") -> MacdStatus.GOLDEN_CROSS
        value.contains("死叉") -> MacdStatus.DEAD_CROSS
        value.contains("红柱放大") -> MacdStatus.RED_EXPANDING
        value.contains("红柱缩短") || value.contains("红柱减弱") -> MacdStatus.RED_WEAKENING
        else -> MacdStatus.NEUTRAL
    }
}
