package com.example.honapp.packet

import com.example.honapp.packet.header.Header
import com.example.honapp.packet.header.TcpHeader
import java.nio.ByteBuffer

class TcpPacket() : Packet() {
    private var header: TcpHeader? = null
    private var payload: Packet? = null

    constructor(buffer: ByteBuffer) : this() {
        header = TcpHeader(buffer)
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