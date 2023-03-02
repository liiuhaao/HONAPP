package com.example.honapp

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class HONVpnService(
//    address: String = "202.120.87.33",
    address: String = "106.75.227.236",
    private val port: Int = 54345,
    private val drop: Int = 0,
) : VpnService() {
    companion object {
        private const val TAG = "HONVpnService"
    }

    private val inetAddress = InetAddress.getByName(address)
    private val inputCh = Channel<IpV4Packet>()

    private var vpnInterface: ParcelFileDescriptor? = null

    private var alive = true
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null

    private var honFecService: HONFecService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            stopVpn()
        }
        return Service.START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        setupVpn()
        honFecService = HONFecService(this, inputCh, inetAddress, port, drop)
        honFecService!!.start()
        startVpn()
    }

    private fun setupVpn() {
        val builder =
            Builder().addAddress("10.10.0.15", 24).addDnsServer("8.8.8.8").addRoute("0.0.0.0", 0)
                .setSession(TAG)
        vpnInterface = builder.establish()
        Log.d(TAG, "VPN interface has established!")
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startVpn() {
        vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        GlobalScope.launch { outputLoop() }
        GlobalScope.launch { inputLoop() }
    }

    private suspend fun outputLoop() {
        loop@ while (alive) {
            val buffer = ByteBuffer.allocate(131072)
            val readBytes = withContext(Dispatchers.IO) {
                vpnInputStream!!.read(buffer.array())
            }
            if (readBytes <= 0) {
                continue@loop
            }
            val packet = IpV4Packet(buffer, readBytes)
            if (packet.header!!.totalLength!! <= 0) {
                continue@loop
            }
            Log.d(TAG, "REQUEST $readBytes: $packet")
            honFecService!!.outputChannel.send(packet)
        }
    }

    private suspend fun inputLoop() {
        loop@ while (alive) {
            val packet = inputCh.receive()
            Log.d(TAG, "RESPONSE: $packet")
            withContext(Dispatchers.IO) {
                vpnOutputStream!!.write(packet.rawData)
            }
        }
    }

    private fun stopVpn() {
        alive = false
        vpnInterface?.close()
        vpnInputStream!!.close()
        vpnOutputStream!!.close()
        honFecService!!.stop()
        stopSelf()
        Log.i(TAG, "Stopped VPN")
    }
}