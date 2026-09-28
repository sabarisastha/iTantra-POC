package com.itantra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM connection_history ORDER BY timestampMs DESC")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insertHistory(history: HistoryEntity)

    @Query("UPDATE connection_history SET alias = :alias WHERE (peerAddress != '' AND peerAddress = :peerKey) OR peerName = :peerKey")
    suspend fun updateAlias(peerKey: String, alias: String?)

    @Query("UPDATE connection_history SET alias = :alias WHERE id = :id")
    suspend fun updateAliasById(id: Long, alias: String?)

    @Query("SELECT alias FROM connection_history WHERE ((peerAddress != '' AND peerAddress = :peerKey) OR peerName = :peerKey) AND alias IS NOT NULL AND alias != '' LIMIT 1")
    suspend fun getAliasForPeer(peerKey: String): String?

    @Query("DELETE FROM connection_history")
    suspend fun clearHistory()
}
