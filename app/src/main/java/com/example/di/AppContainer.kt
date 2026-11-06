package com.example.di

import android.content.Context
import com.example.data.discovery.NsdDiscoveryManager
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.DeviceDiscoveryRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.data.repository.TransferRepositoryImpl
import com.example.domain.repository.DeviceDiscoveryRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.TransferRepository

interface AppContainer {
    val settingsDataStore: SettingsDataStore
    val settingsRepository: SettingsRepository
    val nsdDiscoveryManager: NsdDiscoveryManager
    val deviceDiscoveryRepository: DeviceDiscoveryRepository
    val transferRepository: TransferRepository
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    override val settingsDataStore: SettingsDataStore by lazy {
        SettingsDataStore(context)
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(settingsDataStore)
    }

    override val nsdDiscoveryManager: NsdDiscoveryManager by lazy {
        NsdDiscoveryManager(context)
    }

    override val deviceDiscoveryRepository: DeviceDiscoveryRepository by lazy {
        DeviceDiscoveryRepositoryImpl(context, nsdDiscoveryManager)
    }

    override val transferRepository: TransferRepository by lazy {
        TransferRepositoryImpl()
    }
}
