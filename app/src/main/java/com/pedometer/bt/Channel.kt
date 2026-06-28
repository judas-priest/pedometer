package com.pedometer.bt

enum class Channel {
    Unknown,
    Version,
    ProtobufCommand,
    Activity,
    Data,
    Authentication,
}

fun interface ChannelHandler {
    fun handle(payload: ByteArray)
}
