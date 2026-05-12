package com.msai.longtermstockpicker.domain

import kotlinx.serialization.Serializable

@Serializable
enum class MacdStatus {
    GREEN_EXPANDING,
    GREEN_SHRINKING,
    GOLDEN_CROSS,
    RED_EXPANDING,
    RED_WEAKENING,
    DEAD_CROSS,
    NEUTRAL,
}

data class MacdSeries(
    val dif: List<Double>,
    val dea: List<Double>,
    val bar: List<Double>,
)

object MacdCalculator {

    fun exponentialMovingAverage(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val out = ArrayList<Double>(values.size)
        var ema = values[0]
        out.add(ema)
        for (i in 1 until values.size) {
            ema = values[i] * k + ema * (1 - k)
            out.add(ema)
        }
        return out
    }

    fun calculateMacd(closes: List<Double>): MacdSeries {
        if (closes.isEmpty()) return MacdSeries(emptyList(), emptyList(), emptyList())
        val ema12 = exponentialMovingAverage(closes, 12)
        val ema26 = exponentialMovingAverage(closes, 26)
        val dif = closes.indices.map { i -> ema12[i] - ema26[i] }
        val dea = exponentialMovingAverage(dif, 9)
        val bar = closes.indices.map { i -> (dif[i] - dea[i]) * 2.0 }
        return MacdSeries(dif, dea, bar)
    }

    fun analyzeMacdStatus(series: MacdSeries): MacdStatus {
        val dif = series.dif
        val dea = series.dea
        val bar = series.bar
        if (dif.size < 2 || dea.size < 2 || bar.size < 3) return MacdStatus.NEUTRAL

        val n = bar.size
        val b0 = bar[n - 3]
        val b1 = bar[n - 2]
        val b2 = bar[n - 1]
        val dPrev = dif[n - 2]
        val dCurr = dif[n - 1]
        val ePrev = dea[n - 2]
        val eCurr = dea[n - 1]

        if (dPrev <= ePrev && dCurr > eCurr) return MacdStatus.GOLDEN_CROSS
        if (dPrev >= ePrev && dCurr < eCurr) return MacdStatus.DEAD_CROSS

        if (b2 < 0 && b0 < 0 && b1 < 0) {
            val a0 = kotlin.math.abs(b0)
            val a1 = kotlin.math.abs(b1)
            val a2 = kotlin.math.abs(b2)
            if (a0 > a1 && a1 > a2) return MacdStatus.GREEN_SHRINKING
            if (a0 < a1 && a1 < a2) return MacdStatus.GREEN_EXPANDING
        }

        if (b2 > 0 && b0 > 0 && b1 > 0) {
            if (b0 < b1 && b1 < b2) return MacdStatus.RED_EXPANDING
        }

        return when {
            b2 > 0 -> MacdStatus.RED_WEAKENING
            else -> MacdStatus.NEUTRAL
        }
    }

    fun statusScore(status: MacdStatus): Double = when (status) {
        MacdStatus.GREEN_SHRINKING -> 75.0
        MacdStatus.GOLDEN_CROSS -> 90.0
        MacdStatus.RED_EXPANDING -> 85.0
        MacdStatus.RED_WEAKENING -> 65.0
        MacdStatus.GREEN_EXPANDING -> 30.0
        MacdStatus.DEAD_CROSS -> 20.0
        MacdStatus.NEUTRAL -> 50.0
    }

    fun macdMultiPeriodScore(monthly: MacdStatus, weekly: MacdStatus, daily: MacdStatus): Double {
        return statusScore(monthly) * 0.45 +
            statusScore(weekly) * 0.35 +
            statusScore(daily) * 0.20
    }
}
