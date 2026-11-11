package com.jarvis.assistant.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val action: String,
    val status: String,
    val createdAt: Long
)

@Entity(tableName = "dismissed_suggestions")
data class DismissedSuggestionEntity(
    @PrimaryKey val commandHash: String,
    val commandText: String,
    val dismissedAt: Long
)

@Entity(tableName = "task_contexts")
data class TaskContextRecordEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val category: String,
    val targetPackage: String?,
    val completedStepsJson: String,
    val pendingStepsJson: String,
    val updatedAt: Long
)

@Dao
interface TaskDao {
    /** Returns -1 when the id already exists, which is how duplicates from the PC are dropped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: TaskEntity): Long

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC LIMIT 50")
    fun recent(): Flow<List<TaskEntity>>

    @Query("DELETE FROM tasks WHERE createdAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface SuggestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordDismissed(item: DismissedSuggestionEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM dismissed_suggestions WHERE commandHash = :hash AND dismissedAt > :since)")
    suspend fun isDismissed(hash: String, since: Long): Boolean

    @Query("DELETE FROM dismissed_suggestions WHERE dismissedAt < :before")
    suspend fun pruneDismissed(before: Long)

    @Query("DELETE FROM dismissed_suggestions")
    suspend fun clearAll()
}

@Dao
interface TaskContextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveContext(context: TaskContextRecordEntity)

    @Query("SELECT * FROM task_contexts WHERE id = :id LIMIT 1")
    suspend fun getContext(id: String): TaskContextRecordEntity?

    @Query("DELETE FROM task_contexts")
    suspend fun clearTaskContexts()
}

@Database(
    entities = [TaskEntity::class, DismissedSuggestionEntity::class, TaskContextRecordEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun suggestionDao(): SuggestionDao
    abstract fun taskContextDao(): TaskContextDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "jarvis.db"
            )
            .fallbackToDestructiveMigration()
            .build().also { instance = it }
        }
    }
}
