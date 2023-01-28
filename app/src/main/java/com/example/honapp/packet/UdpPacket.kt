package com.example.honapp.packet

import com.example.honapp.packet.header.Header
import com.example.honapp.packet.header.UdpHeader
import java.nio.ByteBuffer

class UdpPacket() : Packet() {
    private var header: UdpHeader? = null
    private var payload: UnknownPacket? = null

    constructor(buffer: ByteBuffer) : this() {
        header = UdpHeader(buffer)
        payload = UnknownPacket(buffer)
    }

    override fun getHeader(): Header? {
        return header
    }

    override fun getPayload(): Packet? {
        return payload
    }

    override fun toString(): String {
        val sb = StringBuilder("IpV4Packet{")
        sb.append("header=").append(header);
        sb.append(", payload").append(payload);
        sb.append('}');
        return sb.toString();
    }
}