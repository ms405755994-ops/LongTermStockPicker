package com.msai.longtermstockpicker.domain

import kotlinx.serialization.Serializable

@Serializable
data class ScoreResult(
    val tsCode: String,
    val isScored: Boolean,
    val exclusionReason: String?,
    val totalScore: Double,
    val signalLevel: String,
    val stockName: String? = null,
    val industry: String? = null,
    val listDate: String? = null,
    val market: String? = null,
    val dailyDataSource: String? = null,
    val financialDataSource: String? = null,
    val pricePositionScore: Double?,
    val macdMultiPeriodScore: Double?,
    val financialSafetyScore: Double?,
    val financialReportPeriod: String? = null,
    val shortDebtCoverage: Double? = null,
    val interestBearingDebtRatio: Double? = null,
    val operatingCashflowQuality: Double? = null,
    val debtToAssets: Double? = null,
    val financialRiskNote: String? = null,
    val ownershipScore: Double?,
    val ownershipTierLabel: String?,
    val companyType: String? = null,
    val ownershipSource: String? = null,
    val ownershipRemark: String? = null,
    val latestTradeDate: String? = null,
    val currentClose: Double? = null,
    val windowLowestClose: Double? = null,
    val dailyLineCount: Int? = null,
    val weeklyLineCount: Int? = null,
    val monthlyLineCount: Int? = null,
    val pricePercentile: Double?,
    val distanceToLow: Double?,
    val monthlyMacd: MacdStatus?,
    val weeklyMacd: MacdStatus?,
    val dailyMacd: MacdStatus?,
    val reason: String,
    val riskWarnings: List<String>,
    val errorMessage: String? = null,
) {
    val sortKey: Double
        get() = when {
            errorMessage != null -> -2.0
            !isScored || exclusionReason != null -> -1.0
            else -> totalScore
        }
}
