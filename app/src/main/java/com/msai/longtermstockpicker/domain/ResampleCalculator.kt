package com.msai.longtermstockpicker.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

object ResampleCalculator {

    private val dtf = DateTimeFormatter.BASIC_ISO_DATE

    fun toWeekly(dailies: List<Quote>): List<Quote> {
        if (dailies.isEmpty()) return emptyList()
        val sorted = dailies.sortedBy { it.tradeDate }
        val byWeek = sorted.groupBy { q ->
            val ld = LocalDate.parse(q.tradeDate, dtf)
            val y = ld.get(IsoFields.WEEK_BASED_YEAR)
            val w = ld.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            y * 100 + w
        }
        return byWeek.toSortedMap().values.map { week ->
            val first = week.first()
            val last = week.last()
            Quote(
                tsCode = first.tsCode,
                tradeDate = last.tradeDate,
                open = first.open,
                high = week.maxOf { it.high },
                low = week.minOf { it.low },
                close = last.close,
                vol = week.sumOf { it.vol },
                amount = week.sumOf { it.amount },
            )
        }
    }

    fun toMonthly(dailies: List<Quote>): List<Quote> {
        if (dailies.isEmpty()) return emptyList()
        val sorted = dailies.sortedBy { it.tradeDate }
        val byMonth = sorted.groupBy { q ->
            val ld = LocalDate.parse(q.tradeDate, dtf)
            ld.year * 100 + ld.monthValue
        }
        return byMonth.toSortedMap().values.map { month ->
            val first = month.first()
            val last = month.last()
            Quote(
                tsCode = first.tsCode,
                tradeDate = last.tradeDate,
                open = first.open,
                high = month.maxOf { it.high },
                low = month.minOf { it.low },
                close = last.close,
                vol = month.sumOf { it.vol },
                amount = month.sumOf { it.amount },
            )
        }
    }
}
