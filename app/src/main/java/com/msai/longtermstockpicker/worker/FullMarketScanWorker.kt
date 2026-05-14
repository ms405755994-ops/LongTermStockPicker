package com.msai.longtermstockpicker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class FullMarketScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.failure()

    companion object {
        const val UNIQUE_WORK_NAME = "full_market_scan_worker_disabled"
        const val KEY_START_DATE = "startDate"
        const val KEY_END_DATE = "endDate"
        const val KEY_USE_LOCAL_CACHE = "useLocalCache"
        const val KEY_FORCE_REFRESH = "forceRefresh"
        const val KEY_MAX_SCAN_COUNT = "maxScanCount"
    }
}
