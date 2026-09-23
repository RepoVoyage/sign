package com.repovoyage.sign.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** App 私有目录的文本缓存库（ARCHITECTURE §2.6）；allowBackup=false 已在 Manifest 全局声明 */
@Database(
    entities = [SentenceRecord::class, LanguageResultRecord::class],
    version = 1,
    exportSchema = true,
)
abstract class SentenceDatabase : RoomDatabase() {

    abstract fun sentenceDao(): SentenceDao

    companion object {
        private const val NAME = "sentence-cache.db"

        fun create(context: Context): SentenceDatabase =
            Room.databaseBuilder(context.applicationContext, SentenceDatabase::class.java, NAME).build()
    }
}
