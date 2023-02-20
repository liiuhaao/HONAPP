package com.example.honapp

import android.net.VpnService
import android.util.Log
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.math.ceil
import kotlin.math.max

class HONFecService(
    val tunnel: VpnService,
    private val inputChannel: Channel<IpV4Packet>,
    private val address: InetAddress,
    private val port: Int,
    private val drop: Int = 10,
    private val maxSendNum: Int = 10,
) {
    companion object {
        private const val TAG = "HONFecService"

        init {
            System.loadLibrary("rs")
        }
    }

    val outputChannel = Channel<IpV4Packet>()

    private val selector = Selector.open()
    var alive = true

    private val maxBlockSize = 1500 - 20 - 8 - 24
    private val maxK = 8
    private val maxSendBufSize = maxBlockSize * maxK

    var channel: DatagramChannel? = null

    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        GlobalScope.launch {
            if (setUp()) {
                fecInit()
                GlobalScope.launch { outputLoop() }
                GlobalScope.launch { readLoop() }
                Log.i(TAG, "Start service")
            }
        }
    }

    private fun setUp(): Boolean {
        channel = DatagramChannel.open()
        tunnel.protect(channel!!.socket())
        channel!!.configureBlocking(false)
        try {
            channel!!.connect(InetSocketAddress(address, port))
            Log.d(TAG, "Channel has established.")
        } catch (e: IOException) {
            Log.e(TAG, "Channel error!!!", e)
            return false
        }
        selector.wakeup()
        channel!!.register(selector, SelectionKey.OP_READ, this)
        return true
    }

    private external fun fecInit()

    private external fun encode(
        dataNum: Int, blockNum: Int, sendBuffer: ByteBuffer, blockSize: Int
    ): Array<ByteArray>

    private external fun decode(
        dataNum: Int, blockNum: Int, encodeData: Array<ByteArray?>, blockSize: Int
    ): Array<ByteArray>

    private suspend fun outputLoop() = coroutineScope {
        val sendBuf = ByteBuffer.allocateDirect(16384)
        var sendNum = 0
        loop@ while (alive) {
            val packet = withTimeoutOrNull(1000L) {
                outputChannel.receive()
            }
            if (packet == null) {
                if (sendBuf.position() > 0) {
                    Log.d(
                        TAG,
                        "TIMEOUT: send sendBuf.position=${sendBuf.position()}, sendNum=$sendNum"
                    )
                    serveOutput(sendBuf)
                    sendBuf.clear()
                    sendNum = 0
                }
            } else {
                if (packet.header!!.totalLength!! <= 0) {
                    continue@loop
                }
//            Log.d(TAG, "${packet.header!!.totalLength}: ${packet.rawDataString()}")

                if (sendBuf.position() + packet.rawData!!.size >= maxSendBufSize) {
                    Log.d(
                        TAG,
                        "FULL: send sendBuf.position=${sendBuf.position()}, sendNum=$sendNum"
                    )
                    serveOutput(sendBuf)
                    sendBuf.clear()
                    sendBuf.put(packet.rawData!!)
                    sendNum = 1
                } else {
                    sendBuf.put(packet.rawData!!)
                    sendNum++
                    if (sendNum >= maxSendNum) {
                        Log.d(
                            TAG,
                            "MAX: send sendBuf.position=${sendBuf.position()}, sendNum=$sendNum"
                        )
                        serveOutput(sendBuf)
                        sendBuf.clear()
                        sendNum = 0
                    }
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
        val dataSize = sendBuf.position()
        val blockSize = if (dataSize > maxBlockSize) maxBlockSize else dataSize
        val dataNum = (dataSize + blockSize - 1) / blockSize
        val blockNum = max(dataNum + 1, ceil(dataNum * 0.2).toInt())

        val dataBlocks = encode(dataNum, blockNum, sendBuf, blockSize)
        val hashCode = dataBlocks.hashCode()
        for (index in 0 until blockNum) {
            if ((1..100).random() <= drop) {
                continue
            }
            sendBlock(dataBlocks[index], dataNum, blockNum, blockSize, dataSize, hashCode, index)
        }
    }


    private suspend fun sendBlock(
        block: ByteArray,
        data_num: Int,
        block_num: Int,
        blockSize: Int,
        dataSize: Int,
        hashCode: Int,
        index: Int,
    ) {
        val buffer = ByteBuffer.allocate(65536)

        buffer.putInt(hashCode)
        buffer.putInt(dataSize)
        buffer.putInt(blockSize)
        buffer.putInt(data_num)
        buffer.putInt(block_num)
        buffer.putInt(index)
        buffer.put(block)

        buffer.flip()

//        Log.d(
//            TAG,
//            "hashCode=$hashCode, dataSize=$dataSize, blockSize=$blockSize, data_num=$data_num, block_num=$block_num, index=$index"
//        )

        while (buffer.hasRemaining()) {
            withContext(Dispatchers.IO) {
                channel!!.write(buffer)
            }
        }
    }

    fun stop() {
        alive = false
        channel?.close()
        Log.i(TAG, "Stop service")
    }

}