package com.itantra.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val peerName: String,
    val peerAddress: String = "",
    val alias: String? = null,
    val timestampMs: Long,
    val durationMs: Long,
    val transportType: String,
    val languageUsed: String,
    val status: String = "SUCCESS" // "SUCCESS", "FAILED", "ABORTED"
)
