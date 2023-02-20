package com.example.honapp

import android.net.VpnService
import android.util.Log
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

class UdpVpnService(
    val tunnel: VpnService,
    private val inputChannel: Channel<IpV4Packet>,
    private val address: InetAddress,
    private val port: Int,
    private val drop: Int = 0,
    private val maxSendNum: Int = 8,
) {
    companion object {
        private const val TAG = "UdpVpnService"

        init {
            System.loadLibrary("rs")
        }
    }

    val outputChannel = Channel<IpV4Packet>()

    private val selector = Selector.open()
    private val cache = mutableMapOf<Int, Connection?>()
    private val mux = Mutex()
    var alive = true

    private val maxSymbolSize = 1500 - 20 - 8 - 24
    private val maxK = 8
    private val maxSendBufSize = maxSymbolSize * maxK


    @OptIn(DelicateCoroutinesApi::class)
    fun start() {

        GlobalScope.launch { outputLoop() }
        GlobalScope.launch { readLoop() }
        Log.i(TAG, "start service")

    }

    private external fun encode(
        k: Int,
        n: Int,
        sendBuffer: ByteBuffer,
        symbolSize: Int
    ): Array<ByteArray>

    private external fun decode(
        k: Int,
        n: Int,
        encodeData: Array<ByteArray?>,
        symbolSize: Int
    ): Array<ByteArray>


    private suspend fun outputLoop() = coroutineScope {
        val sendBuf = ByteBuffer.allocateDirect(16384)
        var sendNum = 0
        loop@ while (alive) {
            val packet = outputChannel.receive()
            if (packet.header!!.totalLength!! <= 0) {
                continue@loop
            }
            Log.d(TAG, packet.rawDataString())
//            sendPacket(packet)
//            continue

            if (sendBuf.position() + packet.rawData!!.size >= maxSendBufSize) {
//                Log.d(TAG, "send sendBuf.position=${sendBuf.position()}, sendNum=$sendNum")
                serveOutput(sendBuf)
                sendBuf.clear()
                sendBuf.put(packet.rawData!!)
                sendNum = 1
            } else {
                sendBuf.put(packet.rawData!!)
                sendNum++
                if (sendNum >= maxSendNum) {
//                    Log.d(TAG, "send sendBuf.position=${sendBuf.position()}, sendNum=$sendNum")
                    serveOutput(sendBuf)
                    sendBuf.clear()
                    sendNum = 0
                }
            }
        }
    }

    private suspend fun readLoop() {
        loop@ while (alive) {
            val n = withContext(Dispatchers.IO) {
                selector.selectNow()
            }
            if (n <= 0) {
                delay(100)
                continue@loop
            }
            val keys = selector.selectedKeys()
            val it = keys.iterator()
            while (it.hasNext()) {
                val key = it.next()
                if (key.isValid && key.isReadable) {
                    it.remove()
                    val channel = key.channel() as DatagramChannel
                    val buffer = ByteBuffer.allocate(65536)

                    val readBytes = withContext(Dispatchers.IO) {
                        channel.read(buffer)
                    }
                    if (readBytes > 0) {
                        // TODO: Decode
                        // TODO: Aggregate
                        val packet = IpV4Packet(buffer, readBytes)
                        inputChannel.send(packet)
                    }
                }
            }
        }
    }

    private suspend fun serveOutput(sendBuf: ByteBuffer) {
        val k = 8
        val n = 10
        val symbolSize = (sendBuf.position() + k - 1) / k
        val data = encode(k, n, sendBuf, symbolSize)
        val dataSize = sendBuf.position()
        val hashCode = data.hashCode()
        val dropData = Array<ByteArray?>(n) { null }
        var dropNum = 0
        for (index in 0 until n) {
            if ((1..100).random() <= drop) {
                dropNum++
                continue
            }
            dropData[index] = data[index]
            sendData(data[index], k, n, symbolSize, dataSize, hashCode, index)
        }
        if (dropNum <= n - k) {
            Log.d(TAG, "dropNum=$dropNum, try to decode...")
            val decodeData = decode(k, n, dropData, symbolSize)
            for (index in 0 until k) {
                if (!(data[index].contentEquals(decodeData[index]))) {
                    Log.e(TAG, "Not Equal at $index")
                }
            }
            Log.d(TAG, "Decode Finish!!!")
        }

    }

    private suspend fun sendPacket(
        packet: IpV4Packet
    ) {
        val destinationAddress = packet.header!!.destinationAddress
        val sourceAddress = packet.header!!.sourceAddress

        val hashCode = 0
        var conn: Connection? = null
        mux.lock()
        conn = cache.getOrPut(hashCode) {
            val newConn = Connection(hashCode)
            newConn
        }
        if (!conn!!.isOpen && !conn.open()) {
            conn.close()
            cache.remove(hashCode)
            return
        }
        mux.unlock()
        val buffer = ByteBuffer.wrap(packet.rawData!!)

        while (buffer.hasRemaining()) {
            withContext(Dispatchers.IO) {
                conn.channel!!.write(buffer)
            }
        }
    }

    private suspend fun sendData(
        d: ByteArray,
        k: Int,
        n: Int,
        symbolSize: Int,
        dataSize: Int,
        hashCode: Int,
        index: Int,
    ) {
        var conn: Connection? = null
        mux.lock()
        conn = cache.getOrPut(0) {
            val newConn = Connection(0)
            newConn
        }
        if (!conn!!.isOpen && !conn.open()) {
            conn.close()
            cache.remove(0)
            return
        }
        mux.unlock()
        val buffer = ByteBuffer.allocate(65536)

        buffer.putInt(hashCode)
        buffer.putInt(dataSize)
        buffer.putInt(symbolSize)
        buffer.putInt(k)
        buffer.putInt(n)
        buffer.putInt(index)
        buffer.put(d)

        buffer.flip()
//        Log.d(
//            TAG,
//            "hashCode=$hashCode, dataSize=$dataSize, symbolSize=$symbolSize, k=$k, n=$n, index=$index"
//        )

        while (buffer.hasRemaining()) {
            withContext(Dispatchers.IO) {
                conn.channel!!.write(buffer)
            }
        }
    }

    fun stop() {
        alive = false
        val it = cache.iterator()
        while (it.hasNext()) {
            it.next().value!!.close()
            it.remove()
        }
        Log.i(TAG, "stop service")
    }

    inner class Connection(private val hashCode: Int) {
        var channel: DatagramChannel? = null
        var isOpen = false

        fun open(): Boolean {
            if (isOpen) {
                return true
            }
            channel = DatagramChannel.open()
            // protect a socket before connect to the remote server.
            tunnel.protect(channel!!.socket())
            channel!!.configureBlocking(false)
            try {
                channel!!.connect(InetSocketAddress(address, port))
                Log.d(TAG, "A connection has established: $hashCode")
            } catch (e: IOException) {
                Log.e(TAG, "Connection error: $hashCode", e)
                return false
            }

            selector.wakeup()
            channel!!.register(selector, SelectionKey.OP_READ, this)
            isOpen = true
            return true
        }

        fun close() {
            channel?.close()
            isOpen = false
        }

        override fun toString(): String {
            return hashCode.toString()
        }
    }
}