package com.example.model

data class ScanHistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val rawText: String,
    val profileName: String,
    val resultText: String,
    val numericValue: Double? = null,
    val formatName: String
)
