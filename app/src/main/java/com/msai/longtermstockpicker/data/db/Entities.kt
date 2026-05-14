package com.msai.longtermstockpicker.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "stock_basic", primaryKeys = ["tsCode"])
data class StockBasicEntity(
    val tsCode: String,
    val name: String,
    val industry: String?,
    val listDate: String?,
    val market: String?,
    val isSt: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "daily_quotes",
    primaryKeys = ["tsCode", "tradeDate"],
    indices = [
        Index("tsCode"),
        Index("tradeDate"),
        Index(value = ["tsCode", "tradeDate"]),
    ],
)
data class DailyQuoteEntity(
    val tsCode: String,
    val tradeDate: String,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val close: Double?,
    val preClose: Double?,
    val change: Double?,
    val pctChg: Double?,
    val vol: Double?,
    val amount: Double?,
)

@Entity(
    tableName = "financial_snapshot",
    primaryKeys = ["tsCode", "reportDate"],
    indices = [
        Index("tsCode"),
        Index("reportDate"),
    ],
)
data class FinancialSnapshotEntity(
    val tsCode: String,
    val reportDate: String,
    val annDate: String?,
    val shortDebtCoverage: Double?,
    val interestBearingDebtRatio: Double?,
    val cashflowQuality: Double?,
    val debtToAssets: Double?,
    val financialSafetyScore: Double?,
    val riskText: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "score_result",
    primaryKeys = ["tsCode", "tradeDate"],
    indices = [
        Index("tradeDate"),
        Index("totalScore"),
        Index("signalLevel"),
    ],
)
data class ScoreResultEntity(
    val tsCode: String,
    val tradeDate: String,
    val name: String?,
    val industry: String?,
    val totalScore: Double,
    val signalLevel: String,
    val pricePositionScore: Double,
    val macdMultiPeriodScore: Double,
    val financialSafetyScore: Double,
    val financialReportPeriod: String? = null,
    val shortDebtCoverage: Double? = null,
    val interestBearingDebtRatio: Double? = null,
    val cashflowQuality: Double? = null,
    val debtToAssets: Double? = null,
    val financialRiskNote: String? = null,
    val ownershipScore: Double,
    val companyType: String? = null,
    val ownershipSource: String? = null,
    val ownershipRemark: String? = null,
    val pricePercentile: Double?,
    val distanceToLow: Double?,
    val monthlyMacdStatus: String?,
    val weeklyMacdStatus: String?,
    val dailyMacdStatus: String?,
    val reason: String?,
    val hasTenYearData: Boolean = true,
    val dataWarning: String? = null,
    val currentClose: Double? = null,
    val tenYearLow: Double? = null,
    val dailyCount: Int? = null,
    val weeklyCount: Int? = null,
    val monthlyCount: Int? = null,
    val updatedAt: Long,
)

@Entity(tableName = "update_meta", primaryKeys = ["key"])
data class UpdateMetaEntity(
    val key: String,
    val value: String,
    val updatedAt: Long,
)

@Entity(tableName = "import_meta", primaryKeys = ["id"])
data class ImportMetaEntity(
    val id: Int = 1,
    val generatedAt: String,
    val tradeDate: String,
    val modelVersion: String,
    val totalCount: Int,
    val importedAt: Long,
)

@Entity(tableName = "watchlist", primaryKeys = ["tsCode"])
data class WatchlistEntity(
    val tsCode: String,
    val name: String?,
    val industry: String?,
    val addedAt: Long,
)

@Entity(tableName = "scan_task", primaryKeys = ["scanId"])
data class ScanTaskEntity(
    val scanId: String,
    val mode: String,
    val targetEndDate: String,
    val maxScanCount: Int,
    val cursorIndex: Int,
    val totalCount: Int,
    val status: String,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val cacheHitCount: Int,
    val networkRequestCount: Int,
    val startedAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "failed_task", primaryKeys = ["tsCode"])
data class FailedTaskEntity(
    val tsCode: String,
    val name: String?,
    val reason: String,
    val retryCount: Int,
    val lastFailedAt: Long,
    val permanentlyFailed: Boolean,
)
