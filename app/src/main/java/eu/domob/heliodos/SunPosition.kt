package eu.domob.heliodos

import android.hardware.GeomagneticField
import io.github.cosinekitty.astronomy.*

class SunPosition(
    private val latitude: Double,
    private val longitude: Double,
    private val altitude: Double = 0.0
) {
    data class AzimuthAltitude(val azimuth: Double, val altitude: Double)
    data class RiseSet(val sunrise: Long, val sunset: Long)
    data class Solstices(val june: Long, val december: Long)

    private val observer = Observer(latitude, longitude, altitude)

    fun getSunPosition(timeMillis: Long): AzimuthAltitude {
        val time = Time.fromMillisecondsSince1970(timeMillis)
        val equ = equator(Body.Sun, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        val hor = horizon(time, observer, equ.ra, equ.dec, Refraction.Normal)
        
        return AzimuthAltitude(
            hor.azimuth.degreesToRadians(),
            hor.altitude.degreesToRadians()
        )
    }

    fun getSunPositionMagnetic(timeMillis: Long): AzimuthAltitude {
        val truePos = getSunPosition(timeMillis)
        
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

    fun getSunriseSunset(timeMillis: Long): RiseSet? {
        val searchStart = Time.fromMillisecondsSince1970(timeMillis - 24 * 3600 * 1000)
        
        // Search for sunrise within one day from searchStart (which covers [time - 24h, time])
        // We want the *previous* sunrise relative to 'time', and then
        // the following (matching) sunset.
        
        val sunrise = searchRiseSet(Body.Sun, observer, Direction.Rise, searchStart, 1.0) ?: return null
        val sunset = searchRiseSet(Body.Sun, observer, Direction.Set, sunrise, 1.0) ?: return null
        
        return RiseSet(
            sunrise.toMillisecondsSince1970(),
            sunset.toMillisecondsSince1970()
        )
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
