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
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer

class HONVpnService() : VpnService() {
    companion object {
        private const val TAG = "HONVpnService"
    }

    private val inputCh = Channel<IpV4Packet>()

    private var vpnInterface: ParcelFileDescriptor? = null

    private var alive = true
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null

    private var honFecService: HONFecService? = null

    private var dropRate: Int = 0
    private var parityRate: Int = 0


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            stopVpn()
        }
        if (intent != null) {
            dropRate = intent.getIntExtra("DROP_RATE", 0)
            parityRate = intent.getIntExtra("PARITY_RATE", 0)

            honFecService?.setDropRate(dropRate)
            honFecService?.setParityRate(parityRate)

            val ipAddress = intent.getStringExtra("IP_ADDRESS")
            val port = intent.getIntExtra("PARITY_RATE", 54345)
            val inetAddress = InetAddress.getByName(ipAddress)
            honFecService!!.stop()
            honFecService!!.start(inetAddress, port)
        }
        return Service.START_STICKY
    }


    override fun onCreate() {
        super.onCreate()
        setupVpn()
        honFecService = HONFecService(this, inputCh)
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
            Log.d(TAG, "REQUEST $readBytes: $packet")
            honFecService!!.outputChannel.send(packet)
        }
    }

    private suspend fun inputLoop() {
        loop@ while (alive) {
            val packet = inputCh.receive()
            Log.d(TAG, "RESPONSE: $packet")
            try {
                withContext(Dispatchers.IO) {
                    vpnOutputStream?.write(packet.rawData)
                }
            } catch (e: IOException) {
                e.printStackTrace();
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