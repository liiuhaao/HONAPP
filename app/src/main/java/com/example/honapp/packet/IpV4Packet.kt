package com.example.honapp.packet

import com.example.honapp.packet.header.IpV4Header
import java.nio.ByteBuffer

class IpV4Packet() {
    var rawData: ByteArray? = null

    var header: IpV4Header? = null
    var payload: ByteArray? = null

    constructor(buffer: ByteBuffer, length: Int) : this() {
        header = IpV4Header(buffer)

        val headerLength = header!!.headerLength!!

        rawData = ByteArray(length)
        System.arraycopy(buffer.array(), 0, rawData!!, 0, length)

        payload = ByteArray(length - headerLength)
        buffer.get(payload!!, 0, length - headerLength)
    }

    constructor(buffer: ByteBuffer) : this() {
        val pos = buffer.position()
        header = IpV4Header(buffer)

        val totalLength = header!!.totalLength!!
        val headerLength = header!!.headerLength!!

        rawData = ByteArray(totalLength)
        System.arraycopy(buffer.array(), pos, rawData!!, 0, totalLength)

        payload = ByteArray(totalLength - headerLength)
        buffer.get(payload!!, 0, totalLength - headerLength)
    }

    override fun toString(): String {
        val sb = StringBuilder("IpV4Packet{")
        sb.append("header=").append(header);
        sb.append(", payload").append(payload);
        sb.append('}');
        return sb.toString();
    }

    fun rawDataString(): String {
        val sb = StringBuilder("rawData{")
        for (i in rawData!!) {
            sb.append(i)
            sb.append(",")
        }
        sb.append('}');
        return sb.toString();
    }
}
