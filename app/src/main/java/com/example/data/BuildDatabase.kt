package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object BuildStatus {
    const val WAITING = "WAITING"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "user_accounts",
    indices = [Index(value = ["email"], unique = true)]
)
data class UserAccountEntity(
    @PrimaryKey val userId: String,
    val name: String,
    val email: String,
    val passwordHash: String,
    val createdAtMillis: Long
)

@Entity(
    tableName = "user_repositories",
    indices = [Index(value = ["userId", "repoSlug"], unique = true)]
)
data class UserRepositoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val displayName: String,
    val repoSlug: String,
    val repoFullName: String = "",
    val branch: String = "main",
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(tableName = "build_records")
data class BuildRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String = "",
    val repositoryId: Long = 0,
    val appName: String,
    val zipFileName: String,
    val localZipPath: String,
    val packageName: String,
    val repoFullName: String,
    val branch: String,
    val status: String, // WAITING, IN_PROGRESS, SUCCESS, FAILED
    val progress: Float,
    val statusStepMessage: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val workflowRunId: Long? = null,
    val workflowHtmlUrl: String? = null,
    val apkDownloadUrl: String? = null,
    val logsText: String = "",
    val errorSummary: String? = null,
    val apkLocalPath: String? = null,
    val apkFileName: String? = null,
    val apkSizeBytes: Long = 0L
)

@Dao
interface UserAccountDao {
    @Query("SELECT * FROM user_accounts WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getAccountByEmail(email: String): UserAccountEntity?

    @Query("SELECT * FROM user_accounts WHERE userId = :userId LIMIT 1")
    suspend fun getAccountById(userId: String): UserAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAccount(account: UserAccountEntity)

    @Query("UPDATE user_accounts SET passwordHash = :newPasswordHash WHERE LOWER(email) = LOWER(:email)")
    suspend fun updatePasswordByEmail(email: String, newPasswordHash: String): Int
}

@Dao
interface UserRepositoryDao {
    @Query("SELECT * FROM user_repositories WHERE userId = :userId ORDER BY updatedAtMillis DESC")
    fun observeRepositoriesForUser(userId: String): Flow<List<UserRepositoryEntity>>

    @Query("SELECT * FROM user_repositories WHERE id = :id LIMIT 1")
    suspend fun getRepositoryById(id: Long): UserRepositoryEntity?

    @Query("SELECT * FROM user_repositories WHERE userId = :userId AND repoSlug = :repoSlug LIMIT 1")
    suspend fun getRepositoryBySlug(userId: String, repoSlug: String): UserRepositoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepository(repository: UserRepositoryEntity): Long

    @Update
    suspend fun updateRepository(repository: UserRepositoryEntity)

    @Query("DELETE FROM user_repositories WHERE id = :id")
    suspend fun deleteRepositoryById(id: Long)
}

@Dao
interface BuildRecordDao {
    @Query("SELECT * FROM build_records WHERE userId = :userId ORDER BY createdAtMillis DESC")
    fun observeRecordsForUser(userId: String): Flow<List<BuildRecordEntity>>

    @Query("SELECT * FROM build_records WHERE userId = :userId AND repositoryId = :repositoryId ORDER BY createdAtMillis DESC")
    fun observeRecordsForRepository(userId: String, repositoryId: Long): Flow<List<BuildRecordEntity>>

    @Query("SELECT * FROM build_records ORDER BY createdAtMillis DESC")
    fun observeAllRecords(): Flow<List<BuildRecordEntity>>

    @Query("SELECT * FROM build_records WHERE id = :id LIMIT 1")
    fun observeRecordById(id: Long): Flow<BuildRecordEntity?>

    @Query("SELECT * FROM build_records WHERE id = :id LIMIT 1")
    suspend fun getRecordById(id: Long): BuildRecordEntity?

    @Query("SELECT * FROM build_records WHERE status IN ('WAITING', 'IN_PROGRESS') ORDER BY createdAtMillis ASC")
    suspend fun getActiveBuilds(): List<BuildRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: BuildRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<BuildRecordEntity>)

    @Update
    suspend fun updateRecord(record: BuildRecordEntity)

    @Query("DELETE FROM build_records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)

    @Query("DELETE FROM build_records WHERE repositoryId = :repositoryId")
    suspend fun deleteRecordsByRepositoryId(repositoryId: Long)
}

@Database(
    entities = [
        BuildRecordEntity::class,
        UserAccountEntity::class,
        UserRepositoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun buildRecordDao(): BuildRecordDao
    abstract fun userAccountDao(): UserAccountDao
    abstract fun userRepositoryDao(): UserRepositoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gera_apk_builds.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
