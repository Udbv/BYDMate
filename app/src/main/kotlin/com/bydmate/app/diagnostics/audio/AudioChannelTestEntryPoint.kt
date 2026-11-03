package com.bydmate.app.diagnostics.audio

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hands the @Singleton [AudioChannelTest] to the Settings composable, which is not Hilt-injected. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioChannelTestEntryPoint {
    fun audioChannelTest(): AudioChannelTest
}
