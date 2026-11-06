package com.jarvis.assistant.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val action: String,
    val status: String,
    val createdAt: Long
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

@Database(entities = [TaskEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "jarvis.db"
            ).build().also { instance = it }
        }
    }
}
