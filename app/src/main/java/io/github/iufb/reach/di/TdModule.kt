package io.github.iufb.reach.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.iufb.reach.BuildConfig
import io.github.iufb.reach.core.td.TdConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TdModule {
    @Provides
    @Singleton
    fun tdConfig(@ApplicationContext context: Context) = TdConfig(
        apiId = BuildConfig.TD_API_ID,
        apiHash = BuildConfig.TD_API_HASH,
        databaseDirectory = context.filesDir.resolve("tdlib").absolutePath,
        filesDirectory = context.filesDir.resolve("tdlib-files").absolutePath,
        applicationVersion = BuildConfig.VERSION_NAME,
        logUpdates = BuildConfig.DEBUG,
        useTestDc = BuildConfig.USE_TEST_DC,
    )
}
