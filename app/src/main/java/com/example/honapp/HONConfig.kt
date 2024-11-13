package com.example.honapp

import android.os.Parcel
import android.os.Parcelable

data class HONConfig(
    var dropRate: Int,
    var dataNum: Int,
    var parityNum: Int,
    var rxNum: Int,
    var encodeTimeout: Long,
    var decodeTimeout: Long,
    var rxTimeout: Long,
    var ackTimeout: Long,
    var primaryProbability: Int,
    var mode: Int,
    var ipAddress: String?,
    var port: String?,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readString(),
        parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(dropRate)
        parcel.writeInt(dataNum)
        parcel.writeInt(parityNum)
        parcel.writeInt(rxNum)
        parcel.writeLong(encodeTimeout)
        parcel.writeLong(decodeTimeout)
        parcel.writeLong(rxTimeout)
        parcel.writeLong(ackTimeout)
        parcel.writeInt(primaryProbability)
        parcel.writeInt(mode)
        parcel.writeString(ipAddress)
        parcel.writeString(port)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<HONConfig> {
        override fun createFromParcel(parcel: Parcel): HONConfig {
            return HONConfig(parcel)
        }

        override fun newArray(size: Int): Array<HONConfig?> {
            return arrayOfNulls(size)
        }
    }

    override fun toString(): String {
        return "HONConfig={" +
                "dropRate=$dropRate," +
                "dataNum=$dataNum," +
                "parityNum=$parityNum," +
                "rxNum=$rxNum," +
                "encodeTimeout=$encodeTimeout," +
                "decodeTimeout=$decodeTimeout," +
                "rxTimeout=$rxTimeout," +
                "ackTimeout=$ackTimeout," +
                "primaryProbability=$primaryProbability," +
                "mode=$mode," +
                "ipAddress='$ipAddress'," +
                "port='$port'," +
                "}"
    }

    fun toJson(): String {
        return """
            {
                "dropRate": $dropRate,
                "dataNum": $dataNum,
                "parityNum": $parityNum,
                "rxNum": $rxNum,
                "encodeTimeout": $encodeTimeout,
                "decodeTimeout": $decodeTimeout,
                "rxTimeout": $rxTimeout,
                "ackTimeout": $ackTimeout,
                "primaryProbability": $primaryProbability,
                "mode": $mode,
                "ipAddress": "$ipAddress",
                "port": "$port",
            }
        """.trimIndent()
    }
}