package com.tatav.voiceassist.di

import android.content.Context
import com.tatav.voiceassist.speech.SpeechManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSpeechManager(
        @ApplicationContext context: Context
    ): SpeechManager = SpeechManager(context)
}
