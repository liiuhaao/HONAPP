package com.example.honapp.packet

import com.example.honapp.packet.header.Header
import com.example.honapp.packet.header.IpV4Header
import java.nio.ByteBuffer

class IpV4Packet() : Packet() {
    private var header: IpV4Header? = null
    private var payload: Packet? = null

    constructor(buffer: ByteBuffer) : this() {
        header = IpV4Header(buffer)
        payload = when (this.header!!.protocol) {
            TransportProtocol.TCP -> TcpPacket(buffer)
            TransportProtocol.UDP -> UdpPacket(buffer)
            else -> null
        }
    }

    override fun getHeader(): Header? {
        return header
    }

    override fun getPayload(): Packet? {
        return payload
    }

    fun getProtocol(): TransportProtocol? {
        return this.header?.protocol
    }

    override fun toString(): String {
        val sb = StringBuilder("IpV4Packet{")
        sb.append("header=").append(header);
        sb.append(", payload").append(payload);
        sb.append('}');
        return sb.toString();
    }
}
