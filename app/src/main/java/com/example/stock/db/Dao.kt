package com.example.stock.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PremarketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PremarketCacheEntity)

    @Query("SELECT * FROM premarket_cache WHERE date = :date LIMIT 1")
    suspend fun byDate(date: String): PremarketCacheEntity?

    @Query("SELECT * FROM premarket_cache ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun latest(): PremarketCacheEntity?
}

@Dao
interface EodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: EodCacheEntity)

    @Query("SELECT * FROM eod_cache WHERE date = :date LIMIT 1")
    suspend fun byDate(date: String): EodCacheEntity?

    @Query("SELECT * FROM eod_cache ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun latest(): EodCacheEntity?
}

@Dao
interface AlertsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AlertCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: AlertCacheEntity)

    @Query("SELECT * FROM alerts_cache ORDER BY id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<AlertCacheEntity>
}
