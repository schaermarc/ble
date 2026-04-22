package com.example.beaconscanner

data class EddystoneUidBeacon(
    val namespace: String,
    val instanceId: String,
    val txPower: Int,
    val rssi: Int,
    val deviceAddress: String,
    val lastSeenMillis: Long,
) {
    companion object {
        private const val FRAME_TYPE_UID: Byte = 0x00

        fun parse(data: ByteArray, address: String, rssi: Int, now: Long): EddystoneUidBeacon? {
            if (data.size < 18 || data[0] != FRAME_TYPE_UID) return null
            val txPower = data[1].toInt()
            val namespace = data.copyOfRange(2, 12).toHex()
            val instanceId = data.copyOfRange(12, 18).toHex()
            return EddystoneUidBeacon(
                namespace = namespace,
                instanceId = instanceId,
                txPower = txPower,
                rssi = rssi,
                deviceAddress = address,
                lastSeenMillis = now,
            )
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }
}
