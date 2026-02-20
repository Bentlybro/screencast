package com.screencast.di

import com.screencast.discovery.DeviceDiscovery
import com.screencast.discovery.SSDPDiscovery
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoveryModule {
    
    @Binds
    @Singleton
    abstract fun bindDeviceDiscovery(
        ssdpDiscovery: SSDPDiscovery
    ): DeviceDiscovery
}
