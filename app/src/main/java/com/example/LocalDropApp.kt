package com.example

import android.app.Application
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer

class LocalDropApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // Start TCP server for peer-to-peer control and pairing messages
        container.tcpServer.start(53317)
    }

    override fun onTerminate() {
        super.onTerminate()
        container.tcpServer.stop()
        container.connectionManager.cleanup()
        container.nsdDiscoveryManager.cleanup()
    }
}
