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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class HONFecService(
    private val tunnel: VpnService,
    private val inputChannel: Channel<IpV4Packet>,
    private val address: InetAddress,
    private val port: Int,
    private val drop: Int = 0,
    private val encodeTimeout: Duration = 10.nanoseconds,
    private val decodeTimeout: Duration = 1000.milliseconds,
) {
    companion object {
        private const val TAG = "HONFecService"

        init {
            System.loadLibrary("rs")
        }
    }

    val outputChannel = Channel<IpV4Packet>()

    private val selector = Selector.open()
    private var alive = true

    private val maxBlockSize = 1200 - 20 - 8 - 24 // 1,448
    private val maxDataNum = 64
    private val maxPacketBuf = maxBlockSize * maxDataNum // 92,672

    private val bufferSize = 131072
    private val parityRate = 0
    private val maxPacketNum = 5

    private val cache =
        mutableMapOf<Pair<Int, Pair<Pair<Int, Int>, Pair<Int, Int>>>, Channel<ByteBuffer>>()
    private val mutex = Mutex()

    private var udpChannel: DatagramChannel? = null

    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        GlobalScope.launch {
            if (setUp()) {
                fecInit()
                GlobalScope.launch { outputLoop() }
                GlobalScope.launch { inputLoop() }
                Log.i(TAG, "Start service")
            }
        }
    }

    private fun setUp(): Boolean {
        udpChannel = DatagramChannel.open()
        tunnel.protect(udpChannel!!.socket())
        udpChannel!!.configureBlocking(false)
        try {
            udpChannel!!.connect(InetSocketAddress(address, port))
            Log.d(TAG, "Channel has established.")
        } catch (e: IOException) {
            Log.e(TAG, "Channel error!!!", e)
            return false
        }
        selector.wakeup()
        udpChannel!!.register(selector, SelectionKey.OP_READ, this)
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
        val packetBuf = ByteBuffer.allocateDirect(bufferSize)
        var packetNum = 0
        loop@ while (alive) {

//            val packet = outputChannel.receive()
//            val buffer = ByteBuffer.allocate(bufferSize)
//            buffer.put(packet.rawData)
//            buffer.flip()
//            while (buffer.hasRemaining()) {
//                if (!(udpChannel!!.isOpen)) {
//                    break
//                }
//                withContext(Dispatchers.IO) {
//                    udpChannel!!.write(buffer)
//                }
//            }


            val packet = withTimeoutOrNull(encodeTimeout / (packetNum + 1)) {
                outputChannel.receive()
            }
            if (packet == null) {
                if (packetBuf.position() > 0) {
                    Log.d(TAG, "TIMEOUT: send $packetNum packets")

                    packetBuf.flip()
                    val outputBuf = ByteBuffer.allocateDirect(bufferSize)
                    outputBuf.put(packetBuf)
                    packetBuf.flip()

                    launch { serveOutput(outputBuf) }

                    packetBuf.clear()
                    packetNum = 0

                }
            } else {
                if (packet.header!!.totalLength!! <= 0) {
                    continue@loop
                }
                if (packetBuf.position() + packet.rawData!!.size >= maxPacketBuf) {
                    Log.d(TAG, "FULL: send $packetNum packets")

                    packetBuf.flip()
                    val outputBuffer = ByteBuffer.allocateDirect(bufferSize)
                    outputBuffer.put(packetBuf)
                    packetBuf.flip()

                    launch { serveOutput(outputBuffer) }

                    packetBuf.clear()
                    packetNum = 0
                }
                packetBuf.put(packet.rawData!!)
                packetNum++
                if (packetNum >= maxPacketNum) {
                    Log.d(TAG, "TIMEOUT: send $packetNum packets")

                    packetBuf.flip()
                    val outputBuf = ByteBuffer.allocateDirect(bufferSize)
                    outputBuf.put(packetBuf)
                    packetBuf.flip()

                    launch { serveOutput(outputBuf) }

                    packetBuf.clear()
                    packetNum = 0

                }
            }
        }
    }


    private suspend fun inputLoop() = coroutineScope {
        loop@ while (alive) {
            val n = withContext(Dispatchers.IO) {
                selector.selectNow()
            }
            if (n <= 0) {
                continue@loop
            }
            val keys = selector.selectedKeys()
            val it = keys.iterator()
            while (it.hasNext()) {
                val key = it.next()
                if (key.isValid && key.isReadable) {
                    it.remove()
                    val channel = key.channel() as DatagramChannel
                    val readBuf = ByteBuffer.allocate(bufferSize)
                    val readBytes = withContext(Dispatchers.IO) {
                        channel.read(readBuf)
                    }
                    if (readBytes > 0) {
                        readBuf.flip()
                        val inputBuf = ByteBuffer.allocate(readBytes)
                        inputBuf.put(readBuf)
                        inputBuf.flip()
                        readBuf.flip()
                        readBuf.clear()
                        launch { serveInput(inputBuf) }
//                        val packet = IpV4Packet(inputBuf, readBytes)
//                        inputChannel.send(packet)
                    }
                }
            }
        }
    }

    private suspend fun serveInput(inputBuf: ByteBuffer) = coroutineScope {
        val hashCode = inputBuf.int
        val dataSize = inputBuf.int
        val blockSize = inputBuf.int
        val dataNum = inputBuf.int
        val blockNum = inputBuf.int
        mutex.lock()
        val channel = cache.getOrPut(
            Pair(
                hashCode,
                Pair(Pair(dataSize, blockSize), Pair(dataNum, blockNum))
            )
        ) {
            val newChannel = Channel<ByteBuffer>()
            launch {
                handleDecode(
                    hashCode, dataSize, blockSize, dataNum, blockNum, newChannel
                )
            }
            newChannel
        }
        mutex.unlock()
        channel.send(inputBuf)
    }

    private suspend fun handleDecode(
        hashCode: Int,
        dataSize: Int,
        blockSize: Int,
        dataNum: Int,
        blockNum: Int,
        channel: Channel<ByteBuffer>
    ) {
        val dataBlocks = Array<ByteArray?>(blockNum) { null }
        val marks = Array(blockNum) { 1 }
        var receiveNum = 0
        while (true) {
            val inputBuf = withTimeoutOrNull(decodeTimeout / (receiveNum + 1)) {
                channel.receive()
            }
            if (inputBuf == null) {
                mutex.lock()
                cache.remove(
                    Pair(
                        hashCode,
                        Pair(Pair(dataSize, blockSize), Pair(dataNum, blockNum))
                    )
                )
                mutex.unlock()
                break
            } else {
                val index = inputBuf.int
                if (marks[index] == 0) {
                    continue
                }
                receiveNum += 1
                marks[index] = 0
                dataBlocks[index] = ByteArray(blockSize)
                inputBuf.get(dataBlocks[index]!!)
                if (receiveNum == dataNum) {
                    val decodeBlock = decode(dataNum, blockNum, dataBlocks, blockSize)
                    val decodeBuf = ByteBuffer.allocate(dataNum * blockSize)
                    for (block in decodeBlock) {
                        decodeBuf.put(block)
                    }
                    decodeBuf.flip()
                    while (decodeBuf.position() < dataSize) {
                        val packet = IpV4Packet(decodeBuf)
                        inputChannel.send(packet)
                    }
                }
            }
        }
    }

    private suspend fun serveOutput(outputBuffer: ByteBuffer) = coroutineScope {


        val dataSize = outputBuffer.position()
        val blockSize = if (dataSize > maxBlockSize) maxBlockSize else dataSize
        val dataNum = (dataSize + blockSize - 1) / blockSize
//        val blockNum = max(dataNum + 1, dataNum + floor(dataNum * 0.2).toInt())
        var blockNum = dataNum
        for (i in 0 until dataNum) {
            if ((1..100).random() <= parityRate) {
                blockNum++
            }
        }
        val dataBlocks = encode(dataNum, blockNum, outputBuffer, blockSize)
        launch { sendDataBlocks(dataBlocks, blockNum, dataNum, blockSize, dataSize) }
    }

    private suspend fun sendDataBlocks(
        dataBlocks: Array<ByteArray>, blockNum: Int, dataNum: Int, blockSize: Int, dataSize: Int
    ) = coroutineScope {
        val hashCode = dataBlocks.hashCode()

        Log.d(
            TAG,
            "TIMEOUT: hashCode=$hashCode, dataSize=$dataSize, block_num=$blockNum, blockSize=$blockSize, dataNum=$dataNum, blockNum=$blockNum"
        )
        for (index in 0 until blockNum) {
            if ((1..100).random() <= drop) {
                continue
            }
            launch {
                sendBlock(
                    dataBlocks[index], dataNum, blockNum, blockSize, dataSize, hashCode, index
                )
            }

        }
    }

    private suspend fun sendBlock(
        block: ByteArray,
        dataNum: Int,
        blockNum: Int,
        blockSize: Int,
        dataSize: Int,
        hashCode: Int,
        index: Int,
    ) {
        val buffer = ByteBuffer.allocate(bufferSize)

        buffer.putInt(hashCode)
        buffer.putInt(dataSize)
        buffer.putInt(blockSize)
        buffer.putInt(dataNum)
        buffer.putInt(blockNum)
        buffer.putInt(index)
        buffer.put(block)

        buffer.flip()

        while (buffer.hasRemaining()) {
            if (!(udpChannel!!.isOpen)) {
                break
            }
            withContext(Dispatchers.IO) {
                udpChannel!!.write(buffer)
            }
        }
    }

    fun stop() {
        alive = false
        udpChannel?.close()
        Log.i(TAG, "Stop service")
    }

}