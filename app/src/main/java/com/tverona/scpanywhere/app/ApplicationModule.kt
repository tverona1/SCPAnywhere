package com.tverona.scpanywhere.app

import android.content.Context
import androidx.room.Room
import com.tverona.scpanywhere.database.AppDatabase
import com.tverona.scpanywhere.database.BookmarksDao
import com.tverona.scpanywhere.database.ScpDatabaseEntriesDao
import com.tverona.scpanywhere.database.StatsDao
import com.tverona.scpanywhere.downloader.GithubReleaseDownloader
import com.tverona.scpanywhere.onlinedatasource.RatingsDownloader
import com.tverona.scpanywhere.onlinedatasource.ScpListDownloader
import com.tverona.scpanywhere.onlinedatasource.TaleListDownloader
import com.tverona.scpanywhere.repositories.OfflineDataRepository
import com.tverona.scpanywhere.repositories.OfflineDataRepositoryImpl
import com.tverona.scpanywhere.repositories.OfflineModeRepository
import com.tverona.scpanywhere.utils.TextToSpeechProvider
import com.tverona.scpanywhere.repositories.SpeechContent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ApplicationComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Application module used to provide injected instances
 */
@Module
@InstallIn(ApplicationComponent::class)
class ApplicationModule {

    @Singleton
    @Provides
    fun provideOfflineDataRepository(@ApplicationContext appContext: Context, githubReleaseDownloader: GithubReleaseDownloader): OfflineDataRepository {
        return OfflineDataRepositoryImpl(appContext, githubReleaseDownloader)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient().newBuilder().build()
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext appContext: Context): AppDatabase {
        return Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideBookmarksDao(appDatabase: AppDatabase): BookmarksDao {
        return appDatabase.bookmarksDao()
    }

    @Provides
    @Singleton
    fun provideScpDatabaseEntriesDao(appDatabase: AppDatabase): ScpDatabaseEntriesDao {
        return appDatabase.scpDatabaseEntriesDao()
    }

    @Provides
    @Singleton
    fun provideStatsDao(appDatabase: AppDatabase): StatsDao {
        return appDatabase.statsDao()
    }

    @Provides
    @Singleton
    fun providesOfflineModeRepository(@ApplicationContext appContext: Context): OfflineModeRepository {
        return OfflineModeRepository(appContext)
    }

    @Provides
    @Singleton
    fun providesScpListDownloader(
        @ApplicationContext appContext: Context,
        okHttpClient: OkHttpClient
    ): ScpListDownloader {
        return ScpListDownloader(appContext)
    }

    @Provides
    @Singleton
    fun providesTaleListDownloader(
        @ApplicationContext appContext: Context,
        okHttpClient: OkHttpClient
    ): TaleListDownloader {
        return TaleListDownloader(appContext, okHttpClient)
    }

    @Provides
    @Singleton
    fun providesRatingsDownloader(
        @ApplicationContext appContext: Context,
        okHttpClient: OkHttpClient
    ): RatingsDownloader {
        return RatingsDownloader(appContext, okHttpClient)
    }

    @Provides
    @Singleton
    fun providesGithubReleaseDownloader(
        @ApplicationContext appContext: Context,
        okHttpClient: OkHttpClient
    ): GithubReleaseDownloader {
        return GithubReleaseDownloader(appContext, okHttpClient)
    }

    @Provides
    @Singleton
    fun providesTextToSpeechProvider(
        @ApplicationContext appContext: Context
    ): TextToSpeechProvider {
        return TextToSpeechProvider(appContext)
    }

    @Provides
    @Singleton
    fun providesSpeechContent(): SpeechContent {
        return SpeechContent()
    }
}