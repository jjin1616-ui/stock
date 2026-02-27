package com.example.stock.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "premarket_cache")
data class PremarketCacheEntity(
    @PrimaryKey val date: String,
    val generatedAt: String,
    val payloadJson: String,
    val updatedAtMs: Long,
)

@Entity(tableName = "eod_cache")
data class EodCacheEntity(
    @PrimaryKey val date: String,
    val generatedAt: String,
    val payloadJson: String,
    val updatedAtMs: Long,
)

@Entity(tableName = "alerts_cache")
data class AlertCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    val type: String,
    val title: String,
    val body: String,
    val payloadJson: String,
)
