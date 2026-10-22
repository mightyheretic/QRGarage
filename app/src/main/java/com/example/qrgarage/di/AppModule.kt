package com.example.qrgarage.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.data.db.DatabaseFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = DatabaseFactory.open(context)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsStore

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
}
