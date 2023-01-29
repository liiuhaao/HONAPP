package com.example.honapp.packet.header

import java.nio.ByteBuffer
import kotlin.experimental.and

class TcpHeader() {
    companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        const val URG = 0x20
        const val HEADER_SIZE = 20
    }

    var sourcePort: Int? = null
    var destinationPort: Int? = null

    var sequenceNumber: Long? = null
    var acknowledgementNumber: Long? = null

    var dataOffsetAndReserved: Byte? = null

    var headerLength: Int? = null
    var flags: Byte? = null
    var window: Int? = null

    var checksum: Int? = null
    var urgentPointer: Int? = null

    var optionsAndPadding: ByteArray? = null

    constructor(buffer: ByteBuffer) : this() {
        sourcePort = BitUtils.getUnsignedShort(buffer.short)
        destinationPort = BitUtils.getUnsignedShort(buffer.short)

        sequenceNumber = BitUtils.getUnsignedInt(buffer.int)
        acknowledgementNumber = BitUtils.getUnsignedInt(buffer.int);

        dataOffsetAndReserved = buffer.get();

        headerLength = (dataOffsetAndReserved!!.and((0xF0).toByte())).toInt() shr 2
        flags = buffer.get()
        window = BitUtils.getUnsignedShort(buffer.short)

        checksum = BitUtils.getUnsignedShort(buffer.short)
        urgentPointer = BitUtils.getUnsignedShort(buffer.short)

        val optionsLength: Int = headerLength!! - HEADER_SIZE
        if (optionsLength > 0) {
            optionsAndPadding = ByteArray(optionsLength)
            buffer[optionsAndPadding!!, 0, optionsLength]
        } else {
            optionsAndPadding = null
        }
    }

    private fun isFIN(): Boolean {
        return (flags!!.toInt().and(FIN)) == FIN;
    }

    private fun isSYN(): Boolean {
        return (flags!!.toInt().and(SYN)) == SYN;
    }

    private fun isRST(): Boolean {
        return (flags!!.toInt().and(RST)) == RST;
    }

    private fun isPSH(): Boolean {
        return (flags!!.toInt().and(PSH)) == PSH;
    }

    private fun isACK(): Boolean {
        return (flags!!.toInt().and(ACK)) == ACK;
    }

    private fun isURG(): Boolean {
        return (flags!!.toInt().and(URG)) == URG;
    }

    override fun toString(): String {
        val sb = StringBuilder("TCPHeader{")
        sb.append("sourcePort=").append(sourcePort);
        sb.append(", destinationPort=").append(destinationPort);
        sb.append(", sequenceNumber=").append(sequenceNumber);
        sb.append(", acknowledgementNumber=").append(acknowledgementNumber);
        sb.append(", headerLength=").append(headerLength);
        sb.append(", window=").append(window);
        sb.append(", checksum=").append(checksum);
        sb.append(", flags=");
        if (isFIN()) sb.append(" FIN");
        if (isSYN()) sb.append(" SYN");
        if (isRST()) sb.append(" RST");
        if (isPSH()) sb.append(" PSH");
        if (isACK()) sb.append(" ACK");
        if (isURG()) sb.append(" URG");
        sb.append('}');
        return sb.toString();
    }

}