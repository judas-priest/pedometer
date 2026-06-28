package com.pedometer.watchface

import android.util.Log
import com.pedometer.bt.ProtocolHandler
import com.pedometer.proto.XiaomiProto

data class WatchfaceInfo(
    val id: String,
    val name: String,
    val active: Boolean,
    val canDelete: Boolean,
)

class WatchfaceService(
    private val protocolHandler: ProtocolHandler,
    private val onWatchfaceList: (List<WatchfaceInfo>) -> Unit,
) {
    companion object {
        private const val TAG = "WatchfaceService"
        const val COMMAND_TYPE = 4
        const val CMD_SET_ACTIVE = 1
        const val CMD_DELETE = 2
        const val CMD_LIST = 0
    }

    fun requestWatchfaceList() {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_LIST)
            .build()
        protocolHandler.sendCommand(cmd)
    }

    fun setActiveWatchface(watchfaceId: String) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_SET_ACTIVE)
            .setWatchface(XiaomiProto.Watchface.newBuilder().setWatchfaceId(watchfaceId))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Set active watchface: $watchfaceId")
    }

    fun deleteWatchface(watchfaceId: String) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_DELETE)
            .setWatchface(XiaomiProto.Watchface.newBuilder().setWatchfaceId(watchfaceId))
            .build()
        protocolHandler.sendCommand(cmd)
        Log.i(TAG, "Delete watchface: $watchfaceId")
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_LIST -> {
                if (cmd.hasWatchface() && cmd.watchface.hasWatchfaceList()) {
                    val faces = cmd.watchface.watchfaceList.watchfaceList.map {
                        WatchfaceInfo(
                            id = it.id,
                            name = it.name,
                            active = it.active,
                            canDelete = it.canDelete,
                        )
                    }
                    onWatchfaceList(faces)
                    Log.d(TAG, "Got ${faces.size} watchfaces")
                }
            }
            else -> Log.d(TAG, "Unhandled watchface subtype: ${cmd.subtype}")
        }
    }
}
