package com.example.honapp.packet

enum class TransportProtocol(private val protocolNum: Int) {
    TCP(6), UDP(17), OTHER(0xFF);

    companion object {
        fun numberToEnum(protocolNum: Int): TransportProtocol {
            return when (protocolNum) {
                6 -> TCP;
                17 -> UDP
                else -> OTHER
            }
        }
    }

}