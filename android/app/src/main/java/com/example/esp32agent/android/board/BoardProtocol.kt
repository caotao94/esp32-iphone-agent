package com.example.esp32agent.android.board

import com.example.esp32agent.android.agent.JsonParse
import com.example.esp32agent.android.agent.JValue
import java.util.UUID

/** The GATT layout and message formats of docs/ble-protocol.md. Port of Board/BoardProtocol.swift. */
object BoardUuid {
    private const val BASE = "6e400001-a9e8-4b1b-9c39-6d6f8a3c"
    val service: UUID = uuid("0000")
    val cmd: UUID = uuid("0001")
    val reply: UUID = uuid("0002")
    val status: UUID = uuid("0003")
    val video: UUID = uuid("0004")
    val net: UUID = uuid("0005")

    /** The client-configuration descriptor every notify needs switched on. */
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun uuid(suffix: String): UUID = UUID.fromString("$BASE$suffix")

    /** The advertised name of a board: "ESP32 Agent 1A2B" (the last two Bluetooth MAC bytes). */
    const val NAME_PREFIX = "ESP32 Agent"
}

/**
 * The 1 s JSON heartbeat on `status`. A small MTU makes the board notify a reduced object, so new fields win
 * and missing ones keep their last value ([merged]).
 */
data class BoardStatus(
    val up: Long? = null,
    val rst: String? = null,
    val heap: Long? = null,
    val psram: Long? = null,
    val usb: String? = null,     // ok | asleep | off
    val net: String? = null,     // ap | sta | none (none in an Android build)
    val ip: String? = null,
    val sta: Int? = null,
    val ap: String? = null,      // idle | connected | streaming (always idle in an Android build)
    val w: Int? = null,
    val h: Int? = null,
    val kbps: Double? = null,
    val fps: Double? = null,
    val idr: Long? = null,
    val gop: Long? = null,
    val ble: Ble? = null,
) {
    data class Ble(val mtu: Int? = null, val phy: Int? = null, val kbps: Double? = null, val drop: Long? = null)

    fun merged(n: BoardStatus) = BoardStatus(
        up = n.up ?: up, rst = n.rst ?: rst, heap = n.heap ?: heap, psram = n.psram ?: psram,
        usb = n.usb ?: usb, net = n.net ?: net, ip = n.ip ?: ip, sta = n.sta ?: sta, ap = n.ap ?: ap,
        w = n.w ?: w, h = n.h ?: h, kbps = n.kbps ?: kbps, fps = n.fps ?: fps, idr = n.idr ?: idr,
        gop = n.gop ?: gop,
        ble = Ble(
            mtu = n.ble?.mtu ?: ble?.mtu, phy = n.ble?.phy ?: ble?.phy,
            kbps = n.ble?.kbps ?: ble?.kbps, drop = n.ble?.drop ?: ble?.drop,
        ),
    )

    companion object {
        fun decode(text: String): BoardStatus? = try {
            from(JsonParse.parse(text))
        } catch (e: RuntimeException) {
            null
        }

        fun from(v: JValue?): BoardStatus? {
            val o = (v as? JValue.ObjV)?.v ?: return null
            fun n(k: String) = o[k]?.int
            fun d(k: String) = o[k]?.double
            fun s(k: String) = o[k]?.str
            val ble = (o["ble"] as? JValue.ObjV)?.v
            return BoardStatus(
                up = n("up"), rst = s("rst"), heap = n("heap"), psram = n("psram"),
                usb = s("usb"), net = s("net"), ip = s("ip"), sta = n("sta")?.toInt(), ap = s("ap"),
                w = n("w")?.toInt(), h = n("h")?.toInt(), kbps = d("kbps"), fps = d("fps"),
                idr = n("idr"), gop = n("gop"),
                ble = ble?.let {
                    Ble(
                        mtu = it["mtu"]?.int?.toInt(), phy = it["phy"]?.int?.toInt(),
                        kbps = it["kbps"]?.double, drop = it["drop"]?.int,
                    )
                },
            )
        }
    }
}

/** The `net` characteristic: SoftAP credentials (read) or the Wi-Fi mode (write). */
data class NetConfig(val mode: String, val ssid: String?, val pass: String?, val ip: String?) {
    companion object {
        fun decode(text: String): NetConfig? {
            return try {
                val o = JsonParse.parseObject(text) ?: return null
                val mode = o["mode"]?.str ?: return null
                NetConfig(
                    mode = mode,
                    ssid = o["ssid"]?.str, pass = o["pass"]?.str, ip = o["ip"]?.str,
                )
            } catch (e: RuntimeException) {
                null
            }
        }
    }
}

/** A `reply` notify: `<id> ok [extra]` or `<id> err <reason>`. */
data class BoardReply(val id: Int, val ok: Boolean, val text: String) {
    companion object {
        fun parse(line: String): BoardReply? {
            val parts = line.trim().split(' ', limit = 3)
            if (parts.size < 2) return null
            val id = parts[0].toIntOrNull() ?: return null
            val ok = when (parts[1]) {
                "ok" -> true
                "err" -> false
                else -> return null
            }
            return BoardReply(id, ok, if (parts.size > 2) parts[2] else "")
        }
    }
}
