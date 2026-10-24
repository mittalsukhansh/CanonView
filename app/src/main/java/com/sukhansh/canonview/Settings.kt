package com.sukhansh.canonview

import kotlin.math.abs
import kotlin.math.round

/** Camera settings we show/change. Code tables are from libgphoto2 (config.c). */
object Settings {
    class Prop(
        val id: Int, val name: String, val mask: Int, val labels: Map<Int, String>,
        val fmt: ((Int) -> String)? = null,
    )

    private val iso = mapOf(
        0x0000 to "Auto", 0x0040 to "50", 0x0048 to "100", 0x004b to "125", 0x004d to "160",
        0x0050 to "200", 0x0053 to "250", 0x0055 to "320", 0x0058 to "400", 0x005b to "500",
        0x005d to "640", 0x0060 to "800", 0x0063 to "1000", 0x0065 to "1250", 0x0068 to "1600",
        0x006b to "2000", 0x006d to "2500", 0x0070 to "3200", 0x0073 to "4000", 0x0075 to "5000",
        0x0078 to "6400", 0x007b to "8000", 0x007d to "10000", 0x0080 to "12800",
    )

    private val aperture = mapOf(
        0x0008 to "1", 0x000b to "1.1", 0x000c to "1.2", 0x0010 to "1.4", 0x0013 to "1.6",
        0x0014 to "1.8", 0x0018 to "2", 0x001b to "2.2", 0x001c to "2.5", 0x0020 to "2.8",
        0x0023 to "3.2", 0x0024 to "3.5", 0x0028 to "4", 0x002b to "4.5", 0x002d to "5",
        0x0030 to "5.6", 0x0033 to "6.3", 0x0034 to "6.7", 0x0035 to "7.1", 0x0038 to "8",
        0x003b to "9", 0x003c to "9.5", 0x003d to "10", 0x0040 to "11", 0x0044 to "13",
        0x0045 to "14", 0x0048 to "16", 0x004b to "18", 0x004c to "19", 0x004d to "20",
        0x0050 to "22", 0x0053 to "25", 0x0054 to "27", 0x0055 to "29", 0x0058 to "32",
    ).mapValues { (_, value) -> "f/$value" }

    private val shutter = mapOf(
        0x0004 to "Bulb", 0x0010 to "30\"", 0x0013 to "25\"", 0x0015 to "20\"", 0x0018 to "15\"",
        0x001b to "13\"", 0x001c to "10\"", 0x0020 to "8\"", 0x0023 to "6\"", 0x0024 to "6\"",
        0x0025 to "5\"", 0x0028 to "4\"", 0x002b to "3.2\"", 0x002c to "3\"", 0x002d to "2.5\"",
        0x0030 to "2\"", 0x0033 to "1.6\"", 0x0034 to "1.5\"", 0x0035 to "1.3\"", 0x0038 to "1\"",
        0x003b to "0.8\"", 0x003c to "0.7\"", 0x003d to "0.6\"", 0x0040 to "0.5\"", 0x0043 to "0.4\"",
        0x0044 to "0.3\"", 0x0048 to "1/4", 0x004b to "1/5", 0x004c to "1/6", 0x0050 to "1/8",
        0x0054 to "1/10", 0x0055 to "1/13", 0x0058 to "1/15", 0x005c to "1/20", 0x005d to "1/25",
        0x0060 to "1/30", 0x0063 to "1/40", 0x0064 to "1/45", 0x0065 to "1/50", 0x0068 to "1/60",
        0x006b to "1/80", 0x006c to "1/90", 0x006d to "1/100", 0x0070 to "1/125", 0x0073 to "1/160",
        0x0074 to "1/180", 0x0075 to "1/200", 0x0078 to "1/250", 0x007b to "1/320", 0x007c to "1/350",
        0x007d to "1/400", 0x0080 to "1/500", 0x0083 to "1/640", 0x0084 to "1/750", 0x0085 to "1/800",
        0x0088 to "1/1000", 0x008b to "1/1250", 0x008c to "1/1500", 0x008d to "1/1600",
        0x0090 to "1/2000", 0x0093 to "1/2500", 0x0094 to "1/3000", 0x0095 to "1/3200",
        0x0098 to "1/4000",
    )

    private val wb = mapOf(
        0 to "Auto", 1 to "Daylight", 2 to "Cloudy", 3 to "Tungsten", 4 to "Fluorescent",
        5 to "Flash", 6 to "Manual", 8 to "Shadow", 9 to "Color temp",
    )

    val ISO = Prop(0xD103, "ISO", 0xFFFF, iso)
    val APERTURE = Prop(0xD101, "Aperture", 0xFFFF, aperture)
    val SHUTTER = Prop(0xD102, "Shutter", 0xFFFF, shutter)
    val WB = Prop(0xD109, "WB", 0xFF, wb)

    // ---- read-only info shown on the HUD ----
    val AE_MODE = Prop(
        0xD105, "Mode", 0xFFFF, mapOf(
            0 to "P", 1 to "Tv", 2 to "Av", 3 to "M", 4 to "Bulb", 5 to "A-DEP", 6 to "DEP", 7 to "C",
            9 to "Auto", 10 to "Night Portrait", 11 to "Sports", 12 to "Portrait", 13 to "Landscape",
            14 to "Close-up", 15 to "Flash Off", 19 to "CA", 20 to "Movie",
        )
    )
    val EXP_COMP = Prop(0xD104, "Exp comp", 0xFF, emptyMap()) { v ->
        // signed, 1/8 EV units; shown to the nearest 1/3 stop
        val t = round((v.toByte().toInt() / 8f) * 3f) / 3f
        if (abs(t) < 0.05f) "0" else "%+.1f".format(t)
    }
    val DRIVE = Prop(0xD106, "Drive", 0xFFFF, mapOf(
        0 to "Single", 1 to "Continuous", 2 to "Video", 4 to "Cont. high", 5 to "Cont. low",
        6 to "Single silent", 7 to "Cont. timer", 0x10 to "Timer 10s", 0x11 to "Timer 2s",
    ))
    val METERING = Prop(0xD107, "Metering", 0xFF, mapOf(
        0 to "Center", 1 to "Spot", 2 to "Average", 3 to "Evaluative", 4 to "Partial",
        5 to "Center avg", 6 to "Spot (AF)", 7 to "Multi spot",
    ))
    val FOCUS_MODE = Prop(0xD108, "Focus", -1, mapOf(0 to "One-Shot", 1 to "AI Servo", 2 to "AI Focus", 3 to "MF"))
    val STYLE = Prop(0xD110, "Style", 0xFF, mapOf(
        0x81 to "Standard", 0x82 to "Portrait", 0x83 to "Landscape", 0x84 to "Neutral",
        0x85 to "Faithful", 0x86 to "Monochrome", 0x87 to "Auto", 0x88 to "Fine detail",
        0x21 to "User 1", 0x22 to "User 2", 0x23 to "User 3",
    ))
    val BATTERY = Prop(0xD111, "Battery", 0xFFFF, mapOf(0 to "Low", 1 to "50%", 2 to "100%", 4 to "75%", 5 to "25%"))
    val SHOTS = Prop(0xD11B, "Shots", -1, emptyMap()) { v -> v.toString() }
    val CAPTURE_DEST = Prop(0xD11C, "Save to", -1, mapOf(2 to "Card", 4 to "PC"))

    /** Editable settings (bottom buttons): ISO, Aperture, Shutter, WB, Drive (method/timer), Metering. */
    val all = listOf(ISO, APERTURE, SHUTTER, WB, DRIVE, METERING)
    val info = listOf(AE_MODE, EXP_COMP, DRIVE, METERING, FOCUS_MODE, STYLE, BATTERY, SHOTS, CAPTURE_DEST)
    /** Everything we read from the camera's event stream. */
    val tracked = (all + info).distinctBy { it.id }

    fun label(p: Prop, v: Int) = p.fmt?.invoke(v) ?: p.labels[v] ?: "0x%X".format(v)
}