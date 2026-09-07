package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    // Branches
    @Query("SELECT * FROM conversation_branches ORDER BY updatedAt DESC")
    fun getAllBranches(): Flow<List<ConversationBranchEntity>>

    @Query("SELECT * FROM conversation_branches WHERE branchName = :name LIMIT 1")
    suspend fun getBranchByName(name: String): ConversationBranchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBranch(branch: ConversationBranchEntity): Long

    @Update
    suspend fun updateBranch(branch: ConversationBranchEntity)

    @Query("DELETE FROM conversation_branches WHERE branchName = :name")
    suspend fun deleteBranch(name: String)

    // Turns (Commits)
    @Query("SELECT * FROM conversation_turns WHERE branchName = :branchName ORDER BY turnIndex ASC")
    fun getTurnsForBranch(branchName: String): Flow<List<ConversationTurnEntity>>

    @Query("SELECT * FROM conversation_turns WHERE branchName = :branchName ORDER BY turnIndex ASC")
    suspend fun getTurnsListForBranch(branchName: String): List<ConversationTurnEntity>

    @Query("SELECT * FROM conversation_turns WHERE turnId = :turnId LIMIT 1")
    suspend fun getTurnById(turnId: String): ConversationTurnEntity?

    @Query("SELECT * FROM conversation_turns WHERE branchName = :branchName ORDER BY turnIndex DESC LIMIT 1")
    suspend fun getLastTurnForBranch(branchName: String): ConversationTurnEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurnEntity): Long

    @Query("DELETE FROM conversation_turns WHERE branchName = :branchName")
    suspend fun deleteTurnsForBranch(branchName: String)

    @Query("DELETE FROM conversation_turns WHERE id = :id")
    suspend fun deleteTurnById(id: Long)

    @Query("SELECT COUNT(*) FROM conversation_turns WHERE branchName = :branchName")
    suspend fun countTurnsInBranch(branchName: String): Int
}
