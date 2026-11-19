package com.example.engine

import com.example.model.ScanHistoryItem
import java.util.Locale

data class CartLineItem(
    val rawText: String,
    val profileName: String,
    val formatName: String,
    val resultText: String,
    val unitNumericValue: Double?,
    val quantity: Int,
    val latestTimestamp: Long
) {
    val lineTotal: Double?
        get() = unitNumericValue?.let { it * quantity }
}

/**
 * Aggregates chronological scan history into a live inventory / checkout cart
 * with quantity counters, grand total calculation, and Excel TAB+ENTER row serialization.
 */
object ScanCartAggregator {

    fun aggregate(
        historyItems: List<ScanHistoryItem>,
        quantityOverrides: Map<String, Int> = emptyMap(),
        removedKeys: Set<String> = emptySet()
    ): List<CartLineItem> {
        if (historyItems.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<ScanHistoryItem>>()
        for (item in historyItems) {
            if (item.rawText in removedKeys) continue
            grouped.getOrPut(item.rawText) { mutableListOf() }.add(item)
        }

        return grouped.mapNotNull { (rawKey, entries) ->
            val latest = entries.maxByOrNull { it.timestamp } ?: entries.last()
            val qty = (quantityOverrides[rawKey] ?: entries.size).coerceAtLeast(0)
            if (qty <= 0) return@mapNotNull null
            CartLineItem(
                rawText = rawKey,
                profileName = latest.profileName,
                formatName = latest.formatName,
                resultText = latest.resultText,
                unitNumericValue = latest.numericValue,
                quantity = qty,
                latestTimestamp = latest.timestamp
            )
        }.sortedByDescending { it.latestTimestamp }
    }

    fun totalUnits(items: List<CartLineItem>): Int = items.sumOf { it.quantity }

    fun grandTotal(items: List<CartLineItem>): Double =
        items.sumOf { it.lineTotal ?: 0.0 }

    fun formatMoney(value: Double, locale: Locale = Locale("tr", "TR")): String {
        return String.format(locale, "%,.2f", value)
    }

    /**
     * Returns the ordered Excel columns for a single cart row:
     * [Barcode, Quantity, LineTotalOrResult]
     */
    fun buildExcelColumns(item: CartLineItem): List<String> {
        val totalCol = item.lineTotal?.let { String.format(Locale.US, "%.2f", it) } ?: item.resultText
        return listOf(item.rawText, item.quantity.toString(), totalCol)
    }
}
