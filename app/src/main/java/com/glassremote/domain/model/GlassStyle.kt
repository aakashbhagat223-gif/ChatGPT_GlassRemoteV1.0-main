package com.glassremote.domain.model

enum class BackgroundStyle { Glass, Solid, Oled, Image }

data class GlassStyle(
    val fontScale: Float = 1f,
    val fontWeight: Int = 400,
    val background: BackgroundStyle = BackgroundStyle.Glass,
    val imageUri: String? = null,
    val radius: Float = 1f,
)

/** Discovered Android TV / Google TV endpoint. */
data class TvDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 6466,
)

enum class LinkPhase { Idle, Connecting, Ready, Disconnected }

data class RemoteStatus(
    val phase: LinkPhase = LinkPhase.Idle,
    val poweredOn: Boolean? = null,
    val volume: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean? = null,
    val error: String? = null,
)

enum class KeyDirection { Short, StartLong, EndLong }


/** Known local targets; IPs are defaults and can be replaced by mDNS/user configuration. */
object KnownTargets {
    val Xiaomi = TvDevice(
        id = "xiaomi-tv",
        name = "Living Room TV",
        host = "192.168.31.116",
        port = 6466,
    )
    val Jio = TvDevice(
        id = "jio-stb",
        name = "Jio Box",
        host = "192.168.31.24",
        port = 6466,
    )
    val all = listOf(Xiaomi, Jio)
}
