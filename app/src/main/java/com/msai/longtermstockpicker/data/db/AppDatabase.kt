package com.msai.longtermstockpicker.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        StockBasicEntity::class,
        DailyQuoteEntity::class,
        FinancialSnapshotEntity::class,
        ScoreResultEntity::class,
        UpdateMetaEntity::class,
        ImportMetaEntity::class,
        WatchlistEntity::class,
        ScanTaskEntity::class,
        FailedTaskEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stockBasicDao(): StockBasicDao
    abstract fun dailyQuoteDao(): DailyQuoteDao
    abstract fun financialSnapshotDao(): FinancialSnapshotDao
    abstract fun scoreResultDao(): ScoreResultDao
    abstract fun updateMetaDao(): UpdateMetaDao
    abstract fun importMetaDao(): ImportMetaDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun scanTaskDao(): ScanTaskDao
    abstract fun failedTaskDao(): FailedTaskDao
}
