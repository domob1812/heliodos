package eu.domob.heliodos

import android.hardware.GeomagneticField
import io.github.cosinekitty.astronomy.*
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val POLAR_HOUR_RANGE_HOURS = 10

class BodyPosition(
    private val latitude: Double,
    private val longitude: Double,
    private val altitude: Double,
    private val body: Body
) {
    data class AzimuthAltitude(val azimuth: Double, val altitude: Double)
    data class RiseSet(val rise: Long, val set: Long)
    data class Solstices(val june: Long, val december: Long)
    data class HourTickInfo(val millis: Long, val localHour: Int)

    val observer = Observer(latitude, longitude, altitude)

    fun getPosition(timeMillis: Long): AzimuthAltitude {
        val time = Time.fromMillisecondsSince1970(timeMillis)
        val equ = equator(body, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        val hor = horizon(time, observer, equ.ra, equ.dec, Refraction.Normal)

        return AzimuthAltitude(
            hor.azimuth.degreesToRadians(),
            hor.altitude.degreesToRadians()
        )
    }

    fun getPositionMagnetic(timeMillis: Long): AzimuthAltitude {
        val truePos = getPosition(timeMillis)

        val geoField = GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            altitude.toFloat(),
            timeMillis
        )
        val declination = geoField.declination // in degrees
        val declinationRad = Math.toRadians(declination.toDouble())

        return AzimuthAltitude(
            truePos.azimuth - declinationRad,
            truePos.altitude
        )
    }

    fun getRiseSet(timeMillis: Long): RiseSet? {
        val time = Time.fromMillisecondsSince1970(timeMillis)

        /*
         * Multi-pass backward search for the most recent rise event.
         *
         * We search in expanding 12-hour slices going backward from `time`,
         * stopping at the first (i.e., most recent) rise found.
         *
         * This avoids the problem of a naive wide window (e.g. 36 hours)
         * which could return a stale rise from the *previous* cycle when
         * the current cycle's rise is more recent.  For instance, if the
         * Moon rose 5 hours ago but also 30 hours ago (the previous lunar
         * day's rise), pass 1 (time-12h .. time) finds the 5h-ago one
         * and we stop there, never seeing the stale one.
         *
         * Three passes × 12 h = 36 h total covers the lunar day
         * (~24 h 50 min on average) even at its longest outliers, and is
         * more than enough for the solar day (24 h).  If no rise is found
         * in 36 hours the body is effectively in a polar state (continuous
         * day or night), which is handled by the caller's fallback.
         */
        val rise = (0..2).map { i ->
            searchRiseSet(body, observer, Direction.Rise,
                time.addDays(-0.5 * (i + 1)), 0.5)
        }.firstOrNull { it != null } ?: return null

        /*
         * Multi-pass forward search for the next set after the found rise.
         *
         * Same principle as above, searching in expanding 12-hour slices
         * forward from the rise.  This handles the case where the body
         * stays above the horizon for more than 12 hours (common for the
         * Moon around full moon near the winter solstice in high latitudes).
         */
        val set = (0..2).map { i ->
            searchRiseSet(body, observer, Direction.Set,
                rise.addDays(0.5 * i), 0.5)
        }.firstOrNull { it != null } ?: return null

        return RiseSet(
            rise.toMillisecondsSince1970(),
            set.toMillisecondsSince1970()
        )
    }

    fun getFullHourTimestamps(timeMillis: Long): List<HourTickInfo> {
        val riseSet = getRiseSet(timeMillis)
        if (riseSet != null) {
            return getFullHoursInRange(riseSet.rise, riseSet.set)
        }
        val pos = getPosition(timeMillis)
        if (pos.altitude < 0) {
            return emptyList()
        }
        val halfRangeMs = POLAR_HOUR_RANGE_HOURS * 3600_000L
        return getFullHoursInRange(timeMillis - halfRangeMs, timeMillis + halfRangeMs)
    }

    private fun getFullHoursInRange(startMs: Long, endMs: Long): List<HourTickInfo> {
        val zone = ZoneId.systemDefault()
        val startDt = Instant.ofEpochMilli(startMs).atZone(zone)
        val firstHour = if (startDt.minute == 0 && startDt.second == 0 && startDt.nano == 0) {
            startDt
        } else {
            startDt.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        }
        val result = mutableListOf<HourTickInfo>()
        var current = firstHour
        while (current.toInstant().toEpochMilli() <= endMs) {
            result.add(HourTickInfo(
                current.toInstant().toEpochMilli(),
                current.hour
            ))
            current = current.plusHours(1)
        }
        return result
    }

    fun getSolstices(timeMillis: Long): Solstices {
        val time = Time.fromMillisecondsSince1970(timeMillis)
        val year = time.toDateTime().year
        val seasons = seasons(year)

        return Solstices(
            seasons.juneSolstice.toMillisecondsSince1970(),
            seasons.decemberSolstice.toMillisecondsSince1970()
        )
    }

    fun getMarchEquinox(timeMillis: Long): Long {
        val time = Time.fromMillisecondsSince1970(timeMillis)
        val year = time.toDateTime().year
        val seasons = seasons(year)

        return seasons.marchEquinox.toMillisecondsSince1970()
    }
}
