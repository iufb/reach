package io.github.iufb.reach.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.iufb.reach.fonts.DeviceFonts
import io.github.iufb.reach.fonts.FontStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FontsModule {
    /** Шрифты собираются один раз за процесс: смена выбора применяется после перезапуска. */
    @Provides
    @Singleton
    fun deviceFonts(store: FontStore): DeviceFonts = store.load()
}
