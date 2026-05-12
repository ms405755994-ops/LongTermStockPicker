package com.msai.longtermstockpicker.data

import android.content.Context
import com.msai.longtermstockpicker.domain.OwnershipInfo

class OwnershipCsvLoader(private val context: Context) {

    fun load(): Map<String, OwnershipInfo> {
        return runCatching {
            context.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.drop(1)
                    .mapNotNull { parseLine(it) }
                    .associateBy { it.tsCode }
            }
        }.getOrDefault(emptyMap())
    }

    private fun parseLine(line: String): OwnershipInfo? {
        if (line.isBlank()) return null
        val cols = parseCsvLine(line)
        if (cols.size < 6) return null
        val tsCode = cols[0].trim().uppercase()
        if (tsCode.isBlank()) return null
        return OwnershipInfo(
            tsCode = tsCode,
            name = cols[1].trim(),
            companyType = cols[2].trim().ifBlank { "未知" },
            ownershipScore = cols[3].trim().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 60.0,
            source = cols[4].trim().ifBlank { "unknown" },
            remark = cols[5].trim(),
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out.add(cell.toString())
                    cell.clear()
                }
                else -> cell.append(ch)
            }
            i++
        }
        out.add(cell.toString())
        return out
    }

    private companion object {
        const val FILE_NAME = "company_ownership.csv"
    }
}
