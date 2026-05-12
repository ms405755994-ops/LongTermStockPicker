package com.msai.longtermstockpicker.domain

data class PricePositionBreakdown(
    val pricePercentile: Double,
    val distanceToLow: Double,
    val score: Double,
)

object PricePositionCalculator {

    fun compute(closes: List<Double>, currentClose: Double): PricePositionBreakdown {
        if (closes.isEmpty()) {
            return PricePositionBreakdown(1.0, 0.0, 30.0)
        }
        val sorted = closes.sorted()
        val rank = sorted.indexOfFirst { it >= currentClose }.let { idx ->
            if (idx < 0) sorted.size else idx
        }
        val pricePercentile = if (sorted.size <= 1) 0.5 else rank.toDouble() / (sorted.size - 1).coerceAtLeast(1)

        val minClose = sorted.minOrNull() ?: currentClose
        val distanceToLow = if (minClose <= 0) Double.POSITIVE_INFINITY else currentClose / minClose - 1.0

        var base = when {
            pricePercentile <= 0.10 -> 100.0
            pricePercentile <= 0.20 -> 85.0
            pricePercentile <= 0.30 -> 70.0
            pricePercentile <= 0.40 -> 55.0
            else -> 30.0
        }

        base += when {
            distanceToLow <= 0.20 -> 10.0
            distanceToLow <= 0.40 -> 5.0
            distanceToLow > 1.00 -> -10.0
            else -> 0.0
        }

        val score = base.coerceIn(0.0, 100.0)
        return PricePositionBreakdown(pricePercentile, distanceToLow, score)
    }
}
