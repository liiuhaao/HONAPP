package com.example.honapp

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer

class HONVpnService() : VpnService(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    companion object {
        private const val TAG = "HONVpnService"
        const val ACTION_STOP_VPN = "com.example.honapp.STOP_VPN"
        const val ACTION_REQUEST_DATA = "com.example.honapp.GET_DATA"
        const val ACTION_DATA_AVAILABLE = "com.example.ACTION_DATA_AVAILABLE"
    }

    private val inputCh = Channel<IpV4Packet>()

    private var vpnInterface: ParcelFileDescriptor? = null

    private var alive = true
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null

    private var honFecService: HONFecService? = null


    override fun onCreate() {
        super.onCreate()
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        honFecService = HONFecService(this, inputCh, connectivityManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP_VPN && alive -> stopVpn()
            intent?.action == ACTION_REQUEST_DATA -> sendData()
            else -> startVpn(intent)
        }
//        Log.d(TAG, "alive=$alive")
        return START_NOT_STICKY
    }

    private fun sendData() {
        if (honFecService == null) {
            return
        }
        val dataIntent = Intent("com.example.ACTION_DATA_AVAILABLE")
        val bundle = Bundle()
        for ((key, value) in honFecService!!.getInfo()) {
            bundle.putString(key, value.toString())
        }
        dataIntent.putExtra("honVpnData", bundle)
        sendBroadcast(dataIntent)
    }

    private fun startVpn(intent: Intent?) {
        if (intent != null) {

            val config: HONConfig? = intent.getParcelableExtra("CONFIG")
            val primaryChannel: String = intent.getStringExtra("PRIMARY_CHANNEL")!!
            val dropMode: String = intent.getStringExtra("DROP_MODE")!!
            val appPackageName: String? = intent.getStringExtra("APP_PACKAGE_NAME")
            Log.d(TAG, "select APP: $appPackageName")

            setupVpn(appPackageName)
            honFecService?.config = config
            honFecService?.primaryChannel = primaryChannel
            honFecService?.dropMode = dropMode
            honFecService!!.stop()

            val ipAddress = config!!.ipAddress
            val port = config.port!!.toInt()
            val inetAddress = InetAddress.getByName(ipAddress)

            honFecService!!.stop()
            honFecService!!.start(inetAddress, port)
            alive = true
        }
    }

    private fun stopVpn() {
        try {
            alive = false
            vpnInterface?.close()
            vpnInputStream?.close()
            vpnOutputStream?.close()
            honFecService?.stop()
            stopSelf()
            Log.i(TAG, "Stopped VPN service")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to stop VPN service", e)
        }
    }

    private fun setupVpn(appPackageName: String?) {
        val builder = Builder()
            .addAddress("10.10.0.15", 24)
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setSession(TAG)
            .setMtu(2000)
        if (appPackageName != null) {
            builder.addAllowedApplication(appPackageName)
        }
        vpnInterface = builder.establish()
        Log.d(TAG, "VPN interface has established!")

        vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        launch { outputLoop() }
        launch { inputLoop() }
    }


    private suspend fun outputLoop() {
        loop@ while (alive) {
            val buffer = ByteBuffer.allocate(131072)
            var readBytes = 0
            try {
                readBytes = withContext(Dispatchers.IO) {
                    vpnInputStream!!.read(buffer.array())
                }
            } catch (e: IOException) {
                continue@loop
            }
            if (readBytes <= 0) {
                continue@loop
            }
            val packet = IpV4Packet(buffer, readBytes)
            if (packet.header!!.totalLength!! <= 0) {
                continue@loop
            }
            Log.d(TAG, "REQUEST ${packet.header!!.protocol}: $packet")
            honFecService!!.outputChannel.send(packet)
        }
    }

    private suspend fun inputLoop() {
        loop@ while (alive) {
            val packet = inputCh.receive()
            Log.d(TAG, "RESPONSE ${packet.header!!.protocol}: $packet")
            try {
                withContext(Dispatchers.IO) {
                    vpnOutputStream?.write(packet.rawData) ?: run {
                        Log.d(TAG, "vpnOutputStream is null")
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace();
            }
        }
    }

}