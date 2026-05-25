package com.thirdapp.evacuation.model

import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Coord(val lat: Double, val lon: Double)

data class Shelter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val coord: Coord
)

data class UserLocation(
    val coord: Coord,
    val accuracyMeters: Float,
    val timestamp: Long
)

fun haversineMeters(a: Coord, b: Coord): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}
