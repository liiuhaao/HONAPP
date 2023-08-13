package com.example.honapp

import android.net.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.honapp.packet.IpV4Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.lang.Thread.sleep
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HONFecService(
    private val tunnel: VpnService,
    private val inputChannel: Channel<IpV4Packet>,
    private val connectivityManager: ConnectivityManager,
) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    companion object {
        private const val TAG = "HONFecService"

        init {
            System.loadLibrary("rs")
        }
    }

    val outputChannel = Channel<IpV4Packet>()

    var config: HONConfig? = null


    private var txID: UInt = 0u
    private val txMutex = Mutex()

    private var rxID: UInt = 0u
    private var rxNum: Int = 0
    private val rxMutex = Mutex()
    private var rxList: LinkedList<Triple<IpV4Packet, UInt, Long>> = LinkedList()

    private var alive = true

    private var wifiUdpChannel: DatagramChannel? = null
    private var cellularUdpChannel: DatagramChannel? = null
//    private var defaultUdpChannel: DatagramChannel? = null

    private val selector = Selector.open()

    private var wifiDelay: Long = 0
    private var cellularDelay: Long = 0

    private var wifiState: Boolean = true
    private var cellularState: Boolean = true

    private val maxBlockSize = 1200 - 20 - 8 - 24 // 1,448
    private val maxDataNum = 64
    private val maxPacketBuf = maxBlockSize * maxDataNum // 92,672

    private val bufferSize = 131072

    private val cache =
        mutableMapOf<Pair<UInt, Pair<Pair<UInt, UInt>, Pair<UInt, UInt>>>, Channel<ByteBuffer>>()
    private val cacheMutex = Mutex()

    val isWifiAvailable = AtomicBoolean(false)
    val isCellularAvailable = AtomicBoolean(false)

    fun start(inetAddress: InetAddress, port: Int) {
        launch {
            if (setupFec(inetAddress, port)) {
                fecInit()
                alive = true
                launch { outputLoop() }
                launch { inputLoop() }
                launch { monitorRxTimeout() }
                Log.i(TAG, "Start service")
            }
        }
    }

    fun stop() {
        alive = false
        wifiUdpChannel?.close()
        cellularUdpChannel?.close()
        selector.keys().forEach {
            it.cancel()
        }
        Log.i(TAG, "Stop service")
    }

    private suspend fun setupFec(inetAddress: InetAddress, port: Int): Boolean {
//        defaultUdpChannel = withContext(Dispatchers.IO) {
//            DatagramChannel.open().apply {
//                configureBlocking(false)
//            }
//        }
//        tunnel.protect(defaultUdpChannel!!.socket())


        wifiUdpChannel = withContext(Dispatchers.IO) {
            DatagramChannel.open().apply {
                configureBlocking(false)
            }
        }
        tunnel.protect(wifiUdpChannel!!.socket())

        cellularUdpChannel = withContext(Dispatchers.IO) {
            DatagramChannel.open().apply {
                configureBlocking(false)
            }
        }
        tunnel.protect(cellularUdpChannel!!.socket())

        val requestWifi = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.requestNetwork(
            requestWifi,
            object : ConnectivityManager.NetworkCallback() {
                @RequiresApi(Build.VERSION_CODES.M)
                override fun onAvailable(network: Network) {
                    try {
                        network.bindSocket(wifiUdpChannel!!.socket())
                        wifiUdpChannel!!.connect(InetSocketAddress(inetAddress, port))
                        isWifiAvailable.set(true)
                        Log.d(TAG, "WiFi Channel has established.")
                    } catch (e: IOException) {
                        Log.e(TAG, "WiFi Channel error!!!", e)
                    }
                }
            })

        val requestCellular = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        connectivityManager.requestNetwork(
            requestCellular,
            object : ConnectivityManager.NetworkCallback() {
                @RequiresApi(Build.VERSION_CODES.M)
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    try {
                        network.bindSocket(cellularUdpChannel!!.socket())
                        cellularUdpChannel!!.connect(InetSocketAddress(inetAddress, port))
                        isCellularAvailable.set(true)
                        Log.d(TAG, "Cellular Channel has established.")
                    } catch (e: IOException) {
                        Log.e(TAG, "Cellular Channel error!!!", e)
                    }
                }

            })

//        try {
//            Log.d(TAG, "Set up $inetAddress, $port")
//            withContext(Dispatchers.IO) {
//                defaultUdpChannel!!.connect(InetSocketAddress(inetAddress, port))
//            }
//            Log.d(TAG, "Default Channel has established.")
//        } catch (e: IOException) {
//            Log.e(TAG, "Default Channel error!!!", e)
//        }


        selector.wakeup()
        withContext(Dispatchers.IO) {
            wifiUdpChannel!!.register(selector, SelectionKey.OP_READ, "wifi")
            cellularUdpChannel!!.register(selector, SelectionKey.OP_READ, "cellular")
//            defaultUdpChannel!!.register(selector, SelectionKey.OP_READ, "default")
        }
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
        val outputBuffer = ByteBuffer.allocateDirect(bufferSize)
        var packetNum = 0
        var startTime: Long? = null
        val outputMutex = Mutex()

        suspend fun sendBufferedData() {
            if (packetNum > 0) {
                Log.d(TAG, "send packet $packetNum")
                serveOutput(outputBuffer, packetNum)
                outputBuffer.clear()
                packetNum = 0
            }
//            if(packetNum>0){
//                outputBuffer.flip()
//                if(defaultUdpChannel?.isConnected==true){
//                    defaultUdpChannel?.write(outputBuffer)
//                }
//                outputBuffer.flip()
//                outputBuffer.clear()
//                packetNum = 0
//            }
        }
        launch {
            while (true) {
                delay(config!!.encodeTimeout.microseconds / 10)
                if (startTime != null) {
                    outputMutex.lock()
                    if (packetNum > 0) {
                        val timedelta = System.nanoTime() - startTime!!
                        if (timedelta > config!!.encodeTimeout * 1000) {
                            Log.d(TAG, "timeout=$timedelta, ${config!!.encodeTimeout * 1000}")
                            sendBufferedData()
                            startTime = null
                        } else {
                            Log.d(TAG, "timedelta=$timedelta")
                        }
                    }
                    outputMutex.unlock()
                }
            }
        }
        loop@ while (true) {
            val packet = outputChannel.receive()
            outputMutex.lock()
            if (startTime == null) {
                startTime = System.nanoTime()
            }

            if (outputBuffer.position() + packet.rawData!!.size >= maxPacketBuf) {
                sendBufferedData()
                startTime = System.nanoTime()
            }

            outputBuffer.put(packet.rawData!!)
            packetNum++

            if (packetNum >= config!!.maxTXNum) {
                sendBufferedData()
                startTime = null
            }
            outputMutex.unlock()

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
                    val channelType = key.attachment() as String
                    val channel = key.channel() as DatagramChannel
                    val readBuf = ByteBuffer.allocate(bufferSize)
                    val readBytes = withContext(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.IO) {
                                channel.read(readBuf)
                            }
                        } catch (e: IOException) {
                            e.printStackTrace();
                            -1
                        }
                    }
                    if (readBytes > 0) {
                        readBuf.flip()
                        val inputBuf = ByteBuffer.allocate(readBytes)
                        inputBuf.put(readBuf)
                        inputBuf.flip()
                        readBuf.flip()
//                        val packet = IpV4Packet(readBuf)
//                        inputChannel.send(packet)
                        launch { serveInput(readBuf, channelType) }
                    }
                }
            }
        }
    }

    private suspend fun serveOutput(outputBuffer: ByteBuffer, packetNum: Int) {


        val dataSize = outputBuffer.position()

        val dataNum = 10
        val blockSize = (dataSize + dataNum - 1) / dataNum

//        val blockSize = if (dataSize > maxBlockSize) maxBlockSize else dataSize
//        val dataNum = (dataSize + blockSize - 1) / blockSize
//        val blockNum = max(dataNum + 1, dataNum + floor(dataNum * 0.2).toInt())

        var blockNum = dataNum
        for (i in 0 until dataNum) {
            if ((1..100).random() <= config!!.parityRate) {
                blockNum++
            }
        }
        val dataBlocks = encode(dataNum, blockNum, outputBuffer, blockSize)

//        outputBuffer.flip()
//        if (cellularUdpChannel?.isConnected == true) {
//            cellularUdpChannel?.write(outputBuffer)
//        }
//        outputBuffer.flip()
        launch { sendDataBlocks(dataBlocks, blockNum, dataNum, blockSize, dataSize, outputBuffer) }
    }

    private suspend fun sendDataBlocks(
        dataBlocks: Array<ByteArray>,
        blockNum: Int,
        dataNum: Int,
        blockSize: Int,
        dataSize: Int,
        outputBuffer: ByteBuffer
    ) {
        txMutex.lock()
        val groupId = txID
        txID += 1u
        txMutex.unlock()

//

        for (index in 0 until blockNum) {
            if ((1..100).random() <= config!!.dropRate) {
                continue
            }
//            launch {
            var udpChannel: DatagramChannel? = null
//            var udpChannel
            val randomValue = (1..100).random()

            if (index < dataNum) {
                udpChannel = if (isCellularAvailable.get()) {
                    Log.d(
                        TAG,
                        "cellularUdpChannel!!!!!!  wifiDelay=$wifiDelay   cellularDelay=$cellularDelay   [$index/$dataNum/$blockNum]"
                    )
                    cellularUdpChannel
                } else {
                    wifiUdpChannel
                }
            } else {
                udpChannel = if (isWifiAvailable.get()) {
                    Log.d(
                        TAG,
                        "wifiUdpChannel!!!!!!  wifiDelay=$wifiDelay   cellularDelay=$cellularDelay   [$index/$dataNum/$blockNum]"
                    )
                    wifiUdpChannel
                } else {
                    cellularUdpChannel
                }
            }

//            if (wifiDelay < cellularDelay) {
//                if (isWifiAvailable.get() && randomValue <= config!!.primaryProbability) {
//                    Log.d(
//                        TAG,
//                        "wifiUdpChannel!!!!!!  wifiDelay=$wifiDelay   cellularDelay=$cellularDelay"
//                    )
//                    udpChannel = wifiUdpChannel
//                } else if (isCellularAvailable.get()) {
//                    Log.d(
//                        TAG,
//                        "cellularUdpChannel!!!!!!  wifiDelay=$wifiDelay   cellularDelay=$cellularDelay"
//                    )
//                    udpChannel = cellularUdpChannel
//                } else {
//                    Log.d(
//                        TAG,
//                        "wifiUdpChannel!!!!!!  wifiDelay=$wifiDelay   cellularDelay=$cellularDelay"
//                    )
//                    udpChannel = wifiUdpChannel
//                }
//            } else {
//                if (isCellularAvailable.get() && randomValue <= config!!.primaryProbability) {
//                    Log.d(
//                        TAG,
//                        "cellularUdpChannel!!!!!!  wifiDelay=$wifiDelay   cellularDelay=$cellularDelay"
//                    )
//                    udpChannel = cellularUdpChannel
//                } else if (isWifiAvailable.get()) {
//                    Log.d(
//                        TAG,
//                        "wifiUdpChannel!!!!!!  wifiDelay=$wifiDelay   cellularDelay=$cellularDelay"
//                    )
//                    udpChannel = wifiUdpChannel
//                } else {
//                    Log.d(
//                        TAG,
//                        "cellularUdpChannel!!!!!!  wifiDelay=$wifiDelay   cellularDelay=$cellularDelay"
//                    )
//                    udpChannel = cellularUdpChannel
//                }
//            }

            if (udpChannel != null) {
                sendBlock(
                    dataBlocks[index],
                    dataNum,
                    blockNum,
                    blockSize,
                    dataSize,
                    groupId,
                    index,
                    udpChannel,
                    1,
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
        groupID: UInt,
        index: Int,
        udpChannel: DatagramChannel,
        sendTime: Int,
    ) {
        val buffer = ByteBuffer.allocate(bufferSize)

        val packetSendTime = System.currentTimeMillis()
        buffer.putInt(groupID.toInt())
        buffer.putInt(dataSize)
        buffer.putInt(blockSize)
        buffer.putInt(dataNum)
        buffer.putInt(blockNum)
        buffer.putInt(index)
        buffer.putInt(sendTime)
        if (sendTime > 0) {
            buffer.putLong(packetSendTime)
        }
        buffer.put(block)

        buffer.flip()
        if (udpChannel.isConnected) {
            withContext(Dispatchers.IO) {
                try {
                    udpChannel.write(buffer)
                } catch (e: PortUnreachableException) {
                    Log.e("Error", "PortUnreachableException")
                } catch (e: IOException) {
                    Log.e("Error", "IOException")
                }
            }
        }
        buffer.flip()


    }

    private suspend fun serveInput(inputBuf: ByteBuffer, channelType: String) = coroutineScope {
        val groupId = inputBuf.int.toUInt()
        val dataSize = inputBuf.int.toUInt()
        val blockSize = inputBuf.int.toUInt()
        val dataNum = inputBuf.int.toUInt()
        val blockNum = inputBuf.int.toUInt()
        val sendTime = inputBuf.int.toUInt()
        if (sendTime > 0u) {
            val packetSendTime = inputBuf.long
            val packetReceiveTime = System.currentTimeMillis()
            if (channelType == "wifi") {
                wifiDelay = packetReceiveTime - packetSendTime
            } else {
                cellularDelay = packetReceiveTime - packetSendTime
            }
        }
        cacheMutex.lock()
        val channel = cache.getOrPut(
            Pair(
                groupId,
                Pair(Pair(dataSize, blockSize), Pair(dataNum, blockNum))
            )
        ) {
            val newChannel = Channel<ByteBuffer>()
            launch {
                handleDecode(
                    groupId, dataSize, blockSize, dataNum, blockNum, newChannel
                )
            }
            newChannel
        }
        cacheMutex.unlock()
        if ((1..100).random() > config!!.dropRate) {
            channel.send(inputBuf)
        }
    }

    private suspend fun handleDecode(
        groupId: UInt,
        dataSize: UInt,
        blockSize: UInt,
        dataNum: UInt,
        blockNum: UInt,
        channel: Channel<ByteBuffer>
    ) {
        val dataBlocks = Array<ByteArray?>(blockNum.toInt()) { null }
        val marks = Array(blockNum.toInt()) { 1 }
        var receiveNum = 0
        while (true) {
            val inputBuf =
                withTimeoutOrNull(config!!.decodeTimeout.microseconds) {
                    channel.receive()
                }
            if (inputBuf == null) {
                cacheMutex.lock()
                cache.remove(
                    Pair(
                        groupId,
                        Pair(Pair(dataSize, blockSize), Pair(dataNum, blockNum))
                    )
                )
                cacheMutex.unlock()
                break
            } else {
                val index = inputBuf.int
                if (marks[index] == 0) {
                    continue
                }

                receiveNum += 1
                marks[index] = 0
                dataBlocks[index] = ByteArray(blockSize.toInt())
                inputBuf.get(dataBlocks[index]!!)
                if (receiveNum.toUInt() == dataNum) {
                    val decodeBlock =
                        decode(dataNum.toInt(), blockNum.toInt(), dataBlocks, blockSize.toInt())
                    val decodeBuf = ByteBuffer.allocate((dataNum * blockSize).toInt())
                    for (block in decodeBlock) {
                        decodeBuf.put(block)
                    }
                    decodeBuf.flip()

                    rxMutex.lock()
                    while (decodeBuf.position() < dataSize.toInt()) {
                        val packet = IpV4Packet(decodeBuf)
                        rxInsert(packet, groupId)
                    }
                    rxSend()
                    rxMutex.unlock()


                }
            }
        }
    }

    private suspend fun rxSend(timeout: Boolean = false) = coroutineScope {
//        if (rxList.isNotEmpty()) {
//            var rxLog = ""
//            rxLog += "Left $rxNum in rxList: rxId=$rxID. leftId={"
//            var leftID = rxID
//            var leftNum = 0
//            for (rx_iter in rxList) {
//                if (rx_iter.second != leftID) {
//                    if (leftID != rxID) {
//                        rxLog += "$leftID*$leftNum,"
//                    }
//                    leftID = rx_iter.second
//                    leftNum = 1
//                } else {
//                    leftNum += 1
//                }
//            }
//            rxLog += "$leftID*$leftNum}"
//            Log.d(TAG, rxLog)
//        }

        while ((rxList.isNotEmpty()) || (rxNum > config!!.maxRXNum)) {
            val now = System.nanoTime()
            val isTimeout = (now - rxList.first.third > config!!.rxTimeout * 1000)
            val rx = rxList.first()
            if (!isTimeout && rxNum < config!!.maxRXNum && rxID != rx.second && rxID < rx.second - 1u) {
                break
            }
            inputChannel.send(rx.first)
            rxID = rx.second
            rxList.removeFirst()
            rxNum--
        }
    }

    private suspend fun rxInsert(packet: IpV4Packet, groupID: UInt) = coroutineScope {
        val rx: Triple<IpV4Packet, UInt, Long> = Triple(packet, groupID, System.nanoTime())

        val rxIter = rxList.listIterator()
        while (rxIter.hasNext()) {
            if (rxIter.next().second > rx.second) {
                rxIter.previous()
                break
            }
        }
        rxNum += 1
        rxIter.add(rx)
    }

    private suspend fun monitorRxTimeout() = coroutineScope {
        while (true) {
            delay(1)
            rxMutex.lock()
            val now = System.nanoTime()
            if (rxList.isNotEmpty() && now - rxList.first.third > config!!.rxTimeout * 1000) {
                rxSend()
            }
            rxMutex.unlock()
        }
    }


}


