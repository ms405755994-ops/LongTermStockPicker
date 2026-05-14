package com.msai.longtermstockpicker.data

import com.msai.longtermstockpicker.data.db.ImportMetaEntity
import com.msai.longtermstockpicker.data.db.ScoreResultEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MobileResultPack(
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("trade_date") val tradeDate: String = "",
    @SerialName("model_version") val modelVersion: String = "",
    @SerialName("total_count") val totalCount: Int = 0,
    val results: List<MobileScoreResult> = emptyList(),
)

@Serializable
data class MobileScoreResult(
    @SerialName("trade_date") val tradeDate: String,
    @SerialName("ts_code") val tsCode: String,
    val name: String? = null,
    val industry: String? = null,
    @SerialName("total_score") val totalScore: Double = 0.0,
    @SerialName("signal_level") val signalLevel: String = "",
    @SerialName("price_position_score") val pricePositionScore: Double = 0.0,
    @SerialName("macd_multi_period_score") val macdMultiPeriodScore: Double = 0.0,
    @SerialName("financial_safety_score") val financialSafetyScore: Double = 0.0,
    @SerialName("financial_report_period") val financialReportPeriod: String? = null,
    @SerialName("short_debt_coverage") val shortDebtCoverage: Double? = null,
    @SerialName("interest_bearing_debt_ratio") val interestBearingDebtRatio: Double? = null,
    @SerialName("cashflow_quality") val cashflowQuality: Double? = null,
    @SerialName("debt_to_assets") val debtToAssets: Double? = null,
    @SerialName("financial_risk_note") val financialRiskNote: String? = null,
    @SerialName("ownership_score") val ownershipScore: Double = 0.0,
    @SerialName("company_type") val companyType: String? = null,
    @SerialName("ownership_source") val ownershipSource: String? = null,
    @SerialName("ownership_remark") val ownershipRemark: String? = null,
    @SerialName("price_percentile") val pricePercentile: Double? = null,
    @SerialName("distance_to_low") val distanceToLow: Double? = null,
    @SerialName("monthly_macd_status") val monthlyMacdStatus: String? = null,
    @SerialName("weekly_macd_status") val weeklyMacdStatus: String? = null,
    @SerialName("daily_macd_status") val dailyMacdStatus: String? = null,
    @SerialName("current_close") val currentClose: Double? = null,
    @SerialName("ten_year_low") val tenYearLow: Double? = null,
    @SerialName("daily_count") val dailyCount: Int? = null,
    @SerialName("weekly_count") val weeklyCount: Int? = null,
    @SerialName("monthly_count") val monthlyCount: Int? = null,
    @SerialName("has_ten_year_data") val hasTenYearData: Boolean = true,
    @SerialName("data_warning") val dataWarning: String? = null,
    val reason: String? = null,
)

fun MobileScoreResult.toEntity(updatedAt: Long): ScoreResultEntity = ScoreResultEntity(
    tsCode = tsCode,
    tradeDate = tradeDate,
    name = name,
    industry = industry,
    totalScore = totalScore,
    signalLevel = signalLevel,
    pricePositionScore = pricePositionScore,
    macdMultiPeriodScore = macdMultiPeriodScore,
    financialSafetyScore = financialSafetyScore,
    financialReportPeriod = financialReportPeriod,
    shortDebtCoverage = shortDebtCoverage,
    interestBearingDebtRatio = interestBearingDebtRatio,
    cashflowQuality = cashflowQuality,
    debtToAssets = debtToAssets,
    financialRiskNote = financialRiskNote,
    ownershipScore = ownershipScore,
    companyType = companyType?.takeIf { it.isNotBlank() } ?: "未知",
    ownershipSource = ownershipSource?.takeIf { it.isNotBlank() } ?: "default",
    ownershipRemark = ownershipRemark?.takeIf { it.isNotBlank() },
    pricePercentile = pricePercentile,
    distanceToLow = distanceToLow,
    monthlyMacdStatus = monthlyMacdStatus,
    weeklyMacdStatus = weeklyMacdStatus,
    dailyMacdStatus = dailyMacdStatus,
    reason = reason,
    hasTenYearData = hasTenYearData,
    dataWarning = dataWarning?.takeIf { it.isNotBlank() },
    currentClose = currentClose,
    tenYearLow = tenYearLow,
    dailyCount = dailyCount,
    weeklyCount = weeklyCount,
    monthlyCount = monthlyCount,
    updatedAt = updatedAt,
)

fun MobileResultPack.toImportMeta(importedAt: Long): ImportMetaEntity = ImportMetaEntity(
    generatedAt = generatedAt,
    tradeDate = tradeDate,
    modelVersion = modelVersion,
    totalCount = totalCount,
    importedAt = importedAt,
)
