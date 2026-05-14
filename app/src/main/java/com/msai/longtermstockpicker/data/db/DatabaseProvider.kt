package com.msai.longtermstockpicker.data.db

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    private const val DATABASE_NAME = "longterm_stock_picker.db"

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME,
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
