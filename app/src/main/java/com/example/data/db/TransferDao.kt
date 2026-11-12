package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransferEntity)

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM transfer_history WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    @Query("DELETE FROM transfer_history")
    suspend fun deleteAll()
}
