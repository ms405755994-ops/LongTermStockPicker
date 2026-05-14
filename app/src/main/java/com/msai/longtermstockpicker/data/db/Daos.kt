package com.msai.longtermstockpicker.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface StockBasicDao {
    @Upsert
    suspend fun upsert(item: StockBasicEntity)

    @Upsert
    suspend fun upsertAll(items: List<StockBasicEntity>)

    @Query("SELECT * FROM stock_basic WHERE tsCode = :tsCode")
    suspend fun getByTsCode(tsCode: String): StockBasicEntity?

    @Query("SELECT * FROM stock_basic WHERE tsCode = :tsCode")
    suspend fun getLatestByTsCode(tsCode: String): StockBasicEntity?

    @Query("SELECT * FROM stock_basic ORDER BY tsCode ASC")
    suspend fun getAll(): List<StockBasicEntity>

    @Query("SELECT COUNT(*) FROM stock_basic")
    suspend fun count(): Int

    @Query("DELETE FROM stock_basic")
    suspend fun deleteAll()
}

@Dao
interface DailyQuoteDao {
    @Upsert
    suspend fun upsert(item: DailyQuoteEntity)

    @Upsert
    suspend fun upsertAll(items: List<DailyQuoteEntity>)

    @Query("SELECT * FROM daily_quotes WHERE tsCode = :tsCode ORDER BY tradeDate ASC")
    suspend fun getByTsCode(tsCode: String): List<DailyQuoteEntity>

    @Query("SELECT * FROM daily_quotes WHERE tsCode = :tsCode AND tradeDate BETWEEN :startDate AND :endDate ORDER BY tradeDate ASC")
    suspend fun getQuotes(tsCode: String, startDate: String, endDate: String): List<DailyQuoteEntity>

    @Query("SELECT * FROM daily_quotes WHERE tsCode = :tsCode ORDER BY tradeDate DESC LIMIT 1")
    suspend fun getLatestByTsCode(tsCode: String): DailyQuoteEntity?

    @Query("SELECT MAX(tradeDate) FROM daily_quotes WHERE tsCode = :tsCode")
    suspend fun getLatestTradeDate(tsCode: String): String?

    @Query("SELECT MIN(tradeDate) FROM daily_quotes WHERE tsCode = :tsCode")
    suspend fun getEarliestTradeDate(tsCode: String): String?

    @Query("SELECT COUNT(*) FROM daily_quotes WHERE tsCode = :tsCode")
    suspend fun getQuoteCount(tsCode: String): Int

    @Query("SELECT COUNT(*) FROM daily_quotes")
    suspend fun countAll(): Int

    @Query("SELECT MAX(tradeDate) FROM daily_quotes")
    suspend fun getMaxTradeDate(): String?

    @Query("SELECT COUNT(*) FROM daily_quotes")
    suspend fun count(): Int

    @Query("DELETE FROM daily_quotes")
    suspend fun deleteAll()
}

@Dao
interface FinancialSnapshotDao {
    @Upsert
    suspend fun upsert(item: FinancialSnapshotEntity)

    @Upsert
    suspend fun upsertAll(items: List<FinancialSnapshotEntity>)

    @Query("SELECT * FROM financial_snapshot WHERE tsCode = :tsCode ORDER BY reportDate DESC")
    suspend fun getByTsCode(tsCode: String): List<FinancialSnapshotEntity>

    @Query("SELECT * FROM financial_snapshot WHERE tsCode = :tsCode ORDER BY reportDate DESC LIMIT 1")
    suspend fun getLatestByTsCode(tsCode: String): FinancialSnapshotEntity?

    @Query("SELECT COUNT(*) FROM financial_snapshot")
    suspend fun count(): Int

    @Query("DELETE FROM financial_snapshot")
    suspend fun deleteAll()
}

@Dao
interface ScoreResultDao {
    @Upsert
    suspend fun upsert(item: ScoreResultEntity)

    @Upsert
    suspend fun upsertAll(items: List<ScoreResultEntity>)

    @Query("SELECT * FROM score_result WHERE tsCode = :tsCode ORDER BY tradeDate DESC")
    suspend fun getByTsCode(tsCode: String): List<ScoreResultEntity>

    @Query("SELECT * FROM score_result WHERE tsCode = :tsCode ORDER BY tradeDate DESC LIMIT 1")
    suspend fun getLatestByTsCode(tsCode: String): ScoreResultEntity?

    @Query("SELECT * FROM score_result WHERE tradeDate = :tradeDate ORDER BY totalScore DESC LIMIT :limit")
    suspend fun getTopScoresByTradeDate(tradeDate: String, limit: Int): List<ScoreResultEntity>

    @Query("SELECT * FROM score_result WHERE tradeDate = (SELECT MAX(tradeDate) FROM score_result) ORDER BY totalScore DESC LIMIT :limit")
    suspend fun getTopScoresByLatestTradeDate(limit: Int): List<ScoreResultEntity>

    @Query("SELECT COUNT(*) FROM score_result WHERE tradeDate = :tradeDate")
    suspend fun countByTradeDate(tradeDate: String): Int

    @Query("SELECT MAX(tradeDate) FROM score_result")
    suspend fun getLatestScoreDate(): String?

    @Query("SELECT COUNT(*) FROM score_result")
    suspend fun count(): Int

    @Query("DELETE FROM score_result")
    suspend fun deleteAll()
}

@Dao
interface UpdateMetaDao {
    @Upsert
    suspend fun upsert(item: UpdateMetaEntity)

    @Upsert
    suspend fun upsertAll(items: List<UpdateMetaEntity>)

    @Query("SELECT * FROM update_meta WHERE `key` = :key")
    suspend fun getByKey(key: String): UpdateMetaEntity?

    @Query("SELECT COUNT(*) FROM update_meta")
    suspend fun count(): Int

    @Query("DELETE FROM update_meta")
    suspend fun deleteAll()
}

@Dao
interface ImportMetaDao {
    @Upsert
    suspend fun upsert(item: ImportMetaEntity)

    @Query("SELECT * FROM import_meta WHERE id = 1")
    suspend fun getLatest(): ImportMetaEntity?

    @Query("DELETE FROM import_meta")
    suspend fun deleteAll()
}

@Dao
interface WatchlistDao {
    @Upsert
    suspend fun upsert(item: WatchlistEntity)

    @Query("SELECT * FROM watchlist WHERE tsCode = :tsCode")
    suspend fun getByTsCode(tsCode: String): WatchlistEntity?

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    suspend fun getAll(): List<WatchlistEntity>

    @Query("SELECT tsCode FROM watchlist")
    suspend fun getAllCodes(): List<String>

    @Query("DELETE FROM watchlist WHERE tsCode = :tsCode")
    suspend fun deleteByTsCode(tsCode: String)

    @Query("DELETE FROM watchlist")
    suspend fun deleteAll()
}

@Dao
interface ScanTaskDao {
    @Upsert
    suspend fun upsert(item: ScanTaskEntity)

    @Upsert
    suspend fun upsertAll(items: List<ScanTaskEntity>)

    @Query("SELECT * FROM scan_task WHERE scanId = :scanId")
    suspend fun getByScanId(scanId: String): ScanTaskEntity?

    @Query("SELECT * FROM scan_task WHERE status IN ('running', 'paused') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getUnfinishedScanTask(): ScanTaskEntity?

    @Query(
        """
        UPDATE scan_task
        SET cursorIndex = :cursorIndex,
            status = :status,
            successCount = :successCount,
            failedCount = :failedCount,
            skippedCount = :skippedCount,
            cacheHitCount = :cacheHitCount,
            networkRequestCount = :networkRequestCount,
            updatedAt = :updatedAt
        WHERE scanId = :scanId
        """,
    )
    suspend fun updateScanProgress(
        scanId: String,
        cursorIndex: Int,
        status: String,
        successCount: Int,
        failedCount: Int,
        skippedCount: Int,
        cacheHitCount: Int,
        networkRequestCount: Int,
        updatedAt: Long,
    )

    @Query("SELECT COUNT(*) FROM scan_task")
    suspend fun count(): Int

    @Query("DELETE FROM scan_task")
    suspend fun deleteAll()
}

@Dao
interface FailedTaskDao {
    @Upsert
    suspend fun upsert(item: FailedTaskEntity)

    @Upsert
    suspend fun upsertAll(items: List<FailedTaskEntity>)

    @Query("SELECT * FROM failed_task WHERE tsCode = :tsCode")
    suspend fun getByTsCode(tsCode: String): FailedTaskEntity?

    @Query("SELECT * FROM failed_task ORDER BY lastFailedAt DESC")
    suspend fun getFailedTasks(): List<FailedTaskEntity>

    @Query("SELECT COUNT(*) FROM failed_task")
    suspend fun count(): Int

    @Query("DELETE FROM failed_task")
    suspend fun clearFailedTasks()

    @Query("DELETE FROM failed_task")
    suspend fun deleteAll()
}
