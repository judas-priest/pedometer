package com.pedometer.proto

import com.google.protobuf.ByteString

object CommandHelper {

    const val TYPE_AUTH = 1
    const val TYPE_SYSTEM = 2

    const val AUTH_SEND_USERID = 5
    const val AUTH_NONCE = 26
    const val AUTH_STEP3 = 27

    const val SYS_BATTERY = 1
    const val SYS_DEVICE_INFO = 2
    const val SYS_CLOCK = 3

    const val TYPE_HEALTH = 8

    const val HEALTH_ACTIVITY_FETCH_TODAY = 1
    const val HEALTH_ACTIVITY_FETCH_PAST = 2
    const val HEALTH_REALTIME_STATS_START = 45
    const val HEALTH_REALTIME_STATS_STOP = 46
    const val HEALTH_REALTIME_STATS_EVENT = 47

    fun buildNonceCommand(nonce: ByteArray): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_AUTH)
            .setSubtype(AUTH_NONCE)
            .setAuth(
                XiaomiProto.Auth.newBuilder()
                    .setPhoneNonce(
                        XiaomiProto.PhoneNonce.newBuilder()
                            .setNonce(ByteString.copyFrom(nonce))
                    )
            )
            .build()
    }

    fun buildAuthStep3(encryptedNonces: ByteArray, encryptedDeviceInfo: ByteArray): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_AUTH)
            .setSubtype(AUTH_STEP3)
            .setAuth(
                XiaomiProto.Auth.newBuilder()
                    .setAuthStep3(
                        XiaomiProto.AuthStep3.newBuilder()
                            .setEncryptedNonces(ByteString.copyFrom(encryptedNonces))
                            .setEncryptedDeviceInfo(ByteString.copyFrom(encryptedDeviceInfo))
                    )
            )
            .build()
    }

    fun buildDeviceInfoRequest(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_SYSTEM)
            .setSubtype(SYS_DEVICE_INFO)
            .build()
    }

    fun buildBatteryRequest(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_SYSTEM)
            .setSubtype(SYS_BATTERY)
            .build()
    }

    fun buildRealtimeStatsStart(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_HEALTH)
            .setSubtype(HEALTH_REALTIME_STATS_START)
            .build()
    }

    fun buildRealtimeStatsStop(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_HEALTH)
            .setSubtype(HEALTH_REALTIME_STATS_STOP)
            .build()
    }

    fun buildActivityFetchToday(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_HEALTH)
            .setSubtype(HEALTH_ACTIVITY_FETCH_TODAY)
            .setHealth(
                XiaomiProto.Health.newBuilder()
                    .setActivitySyncRequestToday(
                        XiaomiProto.ActivitySyncRequestToday.newBuilder().setUnknown1(0)
                    )
            )
            .build()
    }

    fun buildAuthDeviceInfo(phoneModel: String, apiLevel: Int, region: String): XiaomiProto.AuthDeviceInfo {
        return XiaomiProto.AuthDeviceInfo.newBuilder()
            .setUnknown1(0)
            .setPhoneApiLevel(apiLevel.toFloat())
            .setPhoneName(phoneModel)
            .setUnknown3(224)
            .setRegion(region.uppercase().take(2))
            .build()
    }
}
