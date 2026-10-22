package com.example.qrgarage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.qrgarage.data.db.AppDatabase
import com.example.qrgarage.di.AppModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import javax.inject.Singleton

/** Fresh in-memory database and settings for every test. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
object TestAppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            File(context.cacheDir, "test-settings-${UUID.randomUUID()}.preferences_pb")
        }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient()
}
