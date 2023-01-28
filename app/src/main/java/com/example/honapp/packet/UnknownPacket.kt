package com.example.honapp.packet

import com.example.honapp.packet.header.Header
import java.nio.ByteBuffer

class UnknownPacket() : Packet() {

    var rawData: ByteArray? = null

    constructor(buffer: ByteBuffer) : this() {
        this.rawData = buffer.array()
    }

    constructor(rawData: ByteArray) : this() {
        this.rawData = rawData
    }

    override fun getHeader(): Header? {
        return null
    }

    override fun getPayload(): Packet? {
        return null
    }

    override fun toString(): String {
        val sb = StringBuilder("IpV4Packet{")
        sb.append(", rawData=").append(rawData);
        sb.append('}');
        return sb.toString();
    }

}