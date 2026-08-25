// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.style

import android.content.res.Resources
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting.ListOption
import androidx.wear.watchface.style.WatchFaceLayer
import com.avdesign.mfd24.R
import com.avdesign.mfd24.data.Alerts
import com.avdesign.mfd24.data.SensorSlots
import com.avdesign.mfd24.render.AmbientAuto

/**
 * The watch face's settings. They live in the [UserStyleSchema] rather than in our own store so the
 * system persists them per watch-face instance and the editor gets live preview for free.
 */
object StyleSchema {

    val PALETTE: UserStyleSetting.Id = UserStyleSetting.Id("lume_palette")
    val TEMP_UNIT: UserStyleSetting.Id = UserStyleSetting.Id("temp_unit")
    val PRESSURE_UNIT: UserStyleSetting.Id = UserStyleSetting.Id("pressure_unit")
    val MIDNIGHT_LABEL: UserStyleSetting.Id = UserStyleSetting.Id("midnight_label")
    val DIAL_TOP: UserStyleSetting.Id = UserStyleSetting.Id("dial_top")
    val NADIR: UserStyleSetting.Id = UserStyleSetting.Id("nadir")
    val SOLAR_MARK: UserStyleSetting.Id = UserStyleSetting.Id("solar_mark")
    val LUNAR_MARK: UserStyleSetting.Id = UserStyleSetting.Id("lunar_mark")
    val WEATHER: UserStyleSetting.Id = UserStyleSetting.Id("weather")
    val VIGILANCE: UserStyleSetting.Id = UserStyleSetting.Id("vigilance")
    val VIGILANCE_INTERVAL: UserStyleSetting.Id = UserStyleSetting.Id("vigil_interval")
    val VIBE_STRENGTH: UserStyleSetting.Id = UserStyleSetting.Id("vibe_strength")
    val SOS_SOUND: UserStyleSetting.Id = UserStyleSetting.Id("sos_sound")
    val LOG_HEART_RATE: UserStyleSetting.Id = UserStyleSetting.Id("log_heart_rate")
    val AMBIENT_DENSITY: UserStyleSetting.Id = UserStyleSetting.Id("ambient_density")
    val SENSOR_LEFT: UserStyleSetting.Id = UserStyleSetting.Id("sensor_left")
    val SENSOR_RIGHT: UserStyleSetting.Id = UserStyleSetting.Id("sensor_right")
    val ALARM_MARK: UserStyleSetting.Id = UserStyleSetting.Id("alarm_mark")
    val CALENDAR_MARKS: UserStyleSetting.Id = UserStyleSetting.Id("calendar_marks")
    val RECORD: UserStyleSetting.Id = UserStyleSetting.Id("vital_record")
    val RECORD_INTERVAL: UserStyleSetting.Id = UserStyleSetting.Id("vital_interval")

    /** Whether sleep may be read from a watch that spent the night off the wrist. */
    val SLEEP_OFFBODY: UserStyleSetting.Id = UserStyleSetting.Id("vital_sleep_offbody")

    const val TEMP_CELSIUS: String = "c"
    const val TEMP_FAHRENHEIT: String = "f"
    const val PRESSURE_HPA: String = "hpa"
    const val PRESSURE_MMHG: String = "mmhg"

    const val MIDNIGHT_AS_00: String = "00"
    const val MIDNIGHT_AS_24: String = "24"

    const val DIAL_TOP_NOON: String = "noon"
    const val DIAL_TOP_MIDNIGHT: String = "midnight"

    const val NADIR_OFF: String = "off"
    const val NADIR_ON: String = "on"

    const val SOLAR_OFF: String = "off"
    const val SOLAR_ON: String = "on"

    const val LUNAR_OFF: String = "off"
    const val LUNAR_ON: String = "on"

    const val WEATHER_OFF: String = "off"
    const val WEATHER_ON: String = "on"

    const val VIGILANCE_OFF: String = "off"
    const val VIGILANCE_ON: String = "on"

    /** Vigilance interval option ids are the number of minutes. */
    const val VIGILANCE_INTERVAL_DEFAULT: String = "10"

    fun vigilanceIntervalMillis(optionId: String): Long =
        (optionId.toLongOrNull() ?: 10L) * 60_000L

    /** How loud the SOS calls out, for whoever has to find the wearer. */
    const val SOS_SOUND_OFF: String = "off"
    const val SOS_SOUND_LOW: String = "low"
    const val SOS_SOUND_MED: String = "med"
    const val SOS_SOUND_HIGH: String = "high"

    fun sosVolume(optionId: String): Int = when (optionId) {
        SOS_SOUND_OFF -> Alerts.SOS_VOLUME_OFF
        SOS_SOUND_LOW -> Alerts.SOS_VOLUME_LOW
        SOS_SOUND_HIGH -> Alerts.SOS_VOLUME_HIGH
        else -> Alerts.SOS_VOLUME_MED
    }

    const val LOG_HR_OFF: String = "off"
    const val LOG_HR_ON: String = "on"

    const val ALARM_OFF: String = "off"
    const val ALARM_ON: String = "on"

    const val CALENDAR_OFF: String = "off"
    const val CALENDAR_ON: String = "on"

    const val RECORD_OFF: String = "off"
    const val RECORD_ON: String = "on"

    /** Pulse-sampling interval option ids are the number of minutes, as vigilance's are. */
    const val RECORD_INTERVAL_DEFAULT: String = "10"

    const val SLEEP_OFFBODY_OFF: String = "off"
    const val SLEEP_OFFBODY_ON: String = "on"

    fun recordIntervalMillis(optionId: String): Long = (optionId.toLongOrNull() ?: 10L) * 60_000L

    /** How hard the nudge and the SOS buzz. */
    const val VIBE_LOW: String = "low"
    const val VIBE_MED: String = "med"
    const val VIBE_HIGH: String = "high"

    fun vibeAmplitude(optionId: String): Int = when (optionId) {
        VIBE_LOW -> Alerts.AMPLITUDE_LOW
        VIBE_HIGH -> Alerts.AMPLITUDE_HIGH
        else -> Alerts.AMPLITUDE_MED
    }



    /**
     * Always-on is drawn solid, thinned to every other pixel, or thinned only after dark.
     *
     * AUTO exists because HALVED is a different trade at noon and at midnight: too dim to read in
     * daylight, entirely sufficient after sunset. It never thins during an active watch — see
     * [AmbientAuto][com.avdesign.mfd24.render.AmbientAuto] for the rule and its reasons.
     */
    const val AMBIENT_FULL: String = "full"
    const val AMBIENT_HALF: String = "half"
    const val AMBIENT_AUTO: String = "auto"

    /** Resolves the option id to an [AmbientAuto] mode; unknown ids fall back to solid. */
    fun ambientDensityMode(optionId: String): Int = when (optionId) {
        AMBIENT_HALF -> AmbientAuto.MODE_HALF
        AMBIENT_AUTO -> AmbientAuto.MODE_AUTO
        else -> AmbientAuto.MODE_FULL
    }


    /**
     * The world the schema is built for. Today both worlds carry the same list; the vital
     * split (recorder settings in, units and sensor slots out) lands with the editor's own
     * regating so the two can never disagree about what a section shows.
     */
    /**
     * The settings this world offers, as pure data — [create] builds from exactly this list, so
     * the split is testable on the JVM where a Resources cannot be had. A schema change resets
     * every stored style on update, which makes an accidental drift in either list a
     * user-visible incident rather than a refactor.
     *
     * The wellness face trades the two unit rows — they serve a weather row it does not print —
     * for the recorder's switch and its sampling interval. The sensor slots stay: the rings are
     * an estimate of a day, and an estimate is exactly when a wearer wants the actual reading
     * beside it.
     */
    fun settingIds(world: String): List<UserStyleSetting.Id> {
        val vital = world == WORLD_VITAL
        return listOfNotNull(
            PALETTE,
            TEMP_UNIT.takeUnless { vital },
            PRESSURE_UNIT.takeUnless { vital },
            MIDNIGHT_LABEL,
            DIAL_TOP,
            NADIR,
            SOLAR_MARK,
            LUNAR_MARK,
            WEATHER,
            ALARM_MARK.takeIf { vital },
            CALENDAR_MARKS.takeIf { vital },
            RECORD.takeIf { vital },
            RECORD_INTERVAL.takeIf { vital },
            SLEEP_OFFBODY.takeIf { vital },
            VIGILANCE,
            VIGILANCE_INTERVAL,
            VIBE_STRENGTH,
            SOS_SOUND,
            LOG_HEART_RATE,
            SENSOR_LEFT,
            SENSOR_RIGHT,
            AMBIENT_DENSITY,
        )
    }

    fun create(resources: Resources, world: String): UserStyleSchema =
        UserStyleSchema(settingIds(world).map { settingFor(it, resources) })

    private fun settingFor(id: UserStyleSetting.Id, res: Resources): UserStyleSetting = when (id) {
        PALETTE -> paletteSetting(res)
        TEMP_UNIT -> tempSetting(res)
        PRESSURE_UNIT -> pressureSetting(res)
        MIDNIGHT_LABEL -> midnightLabelSetting(res)
        DIAL_TOP -> dialTopSetting(res)
        NADIR -> nadirSetting(res)
        SOLAR_MARK -> solarMarkSetting(res)
        LUNAR_MARK -> lunarMarkSetting(res)
        WEATHER -> weatherSetting(res)
        ALARM_MARK -> alarmMarkSetting(res)
        CALENDAR_MARKS -> calendarMarksSetting(res)
        RECORD -> recordSetting(res)
        RECORD_INTERVAL -> recordIntervalSetting(res)
        SLEEP_OFFBODY -> sleepOffBodySetting(res)
        VIGILANCE -> vigilanceSetting(res)
        VIGILANCE_INTERVAL -> vigilanceIntervalSetting(res)
        VIBE_STRENGTH -> vibeStrengthSetting(res)
        SOS_SOUND -> sosSoundSetting(res)
        LOG_HEART_RATE -> logHeartRateSetting(res)
        SENSOR_LEFT -> sensorSlotSetting(res, SENSOR_LEFT, R.string.style_sensor_left_name)
        SENSOR_RIGHT -> sensorSlotSetting(res, SENSOR_RIGHT, R.string.style_sensor_right_name)
        AMBIENT_DENSITY -> ambientDensitySetting(res)
        else -> throw IllegalArgumentException("no factory for setting " + id)
    }

    /**
     * Duty arc stroke width, as a fraction of the dial radius.
     *
     * Fixed rather than a setting. Thin and thick were two more menu items that changed nothing
     * visible unless a shift happened to be running, and this is the width the arc was designed at.
     */
    const val DUTY_ARC_WIDTH_FRACTION: Float = 0.028f

    /** The wellness flavor's `BuildConfig.WORLD`. */
    const val WORLD_VITAL: String = "vital"

    /** Reads an option id out of a [UserStyle], falling back to [fallback] when unset. */
    fun optionId(style: UserStyle, settingId: UserStyleSetting.Id, fallback: String): String =
        style[settingId]?.id?.toString() ?: fallback

    private fun paletteSetting(res: Resources) = ListUserStyleSetting(
        PALETTE,
        res,
        R.string.style_palette_name,
        R.string.style_palette_desc,
        null,
        listOf(
            // Three hues, none containing blue: always-on wears the same one, dimmed, and blue
            // is the emitter that ages a panel fastest.
            listOption(res, Palette.ID_AMBER, R.string.palette_amber, R.string.palette_amber_sr),
            listOption(res, Palette.ID_GREEN, R.string.palette_green, R.string.palette_green_sr),
            listOption(res, Palette.ID_RED, R.string.palette_red, R.string.palette_red_sr),
        ),
        listOf(WatchFaceLayer.BASE, WatchFaceLayer.COMPLICATIONS_OVERLAY),
    )

    private fun tempSetting(res: Resources) = ListUserStyleSetting(
        TEMP_UNIT,
        res,
        R.string.style_temp_name,
        R.string.style_temp_desc,
        null,
        listOf(
            listOption(res, TEMP_CELSIUS, R.string.temp_celsius, R.string.temp_celsius_sr),
            listOption(res, TEMP_FAHRENHEIT, R.string.temp_fahrenheit, R.string.temp_fahrenheit_sr),
        ),
        listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
    )

    private fun pressureSetting(res: Resources) = ListUserStyleSetting(
        PRESSURE_UNIT,
        res,
        R.string.style_pressure_name,
        R.string.style_pressure_desc,
        null,
        listOf(
            listOption(res, PRESSURE_HPA, R.string.pressure_hpa, R.string.pressure_hpa_sr),
            listOption(res, PRESSURE_MMHG, R.string.pressure_mmhg, R.string.pressure_mmhg_sr),
        ),
        listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
    )

    // Watch length is a free choice of hours and minutes rather than a menu of presets, so it can
    // match a real rota. Two range settings rather than one total-minutes value, because the editor
    // steps hours and minutes separately.


    private fun midnightLabelSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, MIDNIGHT_AS_00, R.string.midnight_00, R.string.midnight_00_sr),
            listOption(res, MIDNIGHT_AS_24, R.string.midnight_24, R.string.midnight_24_sr),
        )
        return ListUserStyleSetting(
            MIDNIGHT_LABEL,
            res,
            R.string.style_midnight_name,
            R.string.style_midnight_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            // The default option must be an element of `options` — the library resolves it with
            // indexOf, and Option does not define equality by id.
            defaultOption = options[0],
        )
    }

    private fun dialTopSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, DIAL_TOP_NOON, R.string.dial_top_noon, R.string.dial_top_noon_sr),
            listOption(
                res, DIAL_TOP_MIDNIGHT, R.string.dial_top_midnight, R.string.dial_top_midnight_sr
            ),
        )
        return ListUserStyleSetting(
            DIAL_TOP,
            res,
            R.string.style_dial_top_name,
            R.string.style_dial_top_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            defaultOption = options[0],
        )
    }

    private fun nadirSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, NADIR_OFF, R.string.nadir_off, R.string.nadir_off_sr),
            listOption(res, NADIR_ON, R.string.nadir_on, R.string.nadir_on_sr),
        )
        return ListUserStyleSetting(
            NADIR,
            res,
            R.string.style_nadir_name,
            R.string.style_nadir_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            defaultOption = options[0],
        )
    }

    /**
     * The sun's true position drawn on the Nadir band — a solar compass for whoever knows how to
     * hold one: turn the watch until the mark points at the real sun and the dial is oriented,
     * noon towards the equator. Off by default and gated on Nadir in the editor, because without
     * the band there is nothing to read the mark against, and without a position both are lies.
     */
    private fun solarMarkSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, SOLAR_OFF, R.string.solar_off, R.string.solar_off_sr),
            listOption(res, SOLAR_ON, R.string.solar_on, R.string.solar_on_sr),
        )
        return ListUserStyleSetting(
            SOLAR_MARK,
            res,
            R.string.style_solar_name,
            R.string.style_solar_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            defaultOption = options[0],
        )
    }

    /**
     * The moon on the same sky ring, with the same gating and the same compass idea: hour angle
     * mapped to dial angle, lit side facing the solar mark, phase drawn honestly. Grey, because
     * the moon is grey.
     */
    private fun lunarMarkSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, LUNAR_OFF, R.string.lunar_off, R.string.lunar_off_sr),
            listOption(res, LUNAR_ON, R.string.lunar_on, R.string.lunar_on_sr),
        )
        return ListUserStyleSetting(
            LUNAR_MARK,
            res,
            R.string.style_lunar_name,
            R.string.style_lunar_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            defaultOption = options[0],
        )
    }

    private fun weatherSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, WEATHER_ON, R.string.weather_on, R.string.weather_on_sr),
            listOption(res, WEATHER_OFF, R.string.weather_off, R.string.weather_off_sr),
        )
        return ListUserStyleSetting(
            WEATHER,
            res,
            R.string.style_weather_name,
            R.string.style_weather_desc,
            null,
            options,
            listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
            defaultOption = options[0],
        )
    }

    private fun vigilanceSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, VIGILANCE_OFF, R.string.vigilance_off, R.string.vigilance_off_sr),
            listOption(res, VIGILANCE_ON, R.string.vigilance_on, R.string.vigilance_on_sr),
        )
        return ListUserStyleSetting(
            VIGILANCE,
            res,
            R.string.style_vigilance_name,
            R.string.style_vigilance_desc,
            null,
            options,
            listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
            // Off by default: it holds a wake lock and runs the accelerometer, and that is not a
            // cost to impose on anyone who did not ask for it.
            defaultOption = options[0],
        )
    }

    private fun vigilanceIntervalSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, "5", R.string.vigilance_5, R.string.vigilance_5_sr),
            listOption(res, "10", R.string.vigilance_10, R.string.vigilance_10_sr),
            listOption(res, "15", R.string.vigilance_15, R.string.vigilance_15_sr),
        )
        return ListUserStyleSetting(
            VIGILANCE_INTERVAL,
            res,
            R.string.style_vigilance_interval_name,
            R.string.style_vigilance_interval_desc,
            null,
            options,
            listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
            defaultOption = options[1],
        )
    }


    private fun vibeStrengthSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, VIBE_LOW, R.string.vibe_low, R.string.vibe_low_sr),
            listOption(res, VIBE_MED, R.string.vibe_med, R.string.vibe_med_sr),
            listOption(res, VIBE_HIGH, R.string.vibe_high, R.string.vibe_high_sr),
        )
        return ListUserStyleSetting(
            VIBE_STRENGTH,
            res,
            R.string.style_vibe_name,
            R.string.style_vibe_desc,
            null,
            options,
            listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
            // Medium: a nudge meant to be noticed through a sleeve without being an alarm in itself.
            defaultOption = options[1],
        )
    }

    private fun sosSoundSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, SOS_SOUND_OFF, R.string.sos_off, R.string.sos_off_sr),
            listOption(res, SOS_SOUND_LOW, R.string.sos_low, R.string.sos_low_sr),
            listOption(res, SOS_SOUND_MED, R.string.sos_med, R.string.sos_med_sr),
            listOption(res, SOS_SOUND_HIGH, R.string.sos_high, R.string.sos_high_sr),
        )
        return ListUserStyleSetting(
            SOS_SOUND,
            res,
            R.string.style_sos_name,
            R.string.style_sos_desc,
            null,
            options,
            listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
            // High, not medium: the sound is not for the wearer — it is for whoever is in the room
            // and has to find them, and a distress signal that has to be strained for is one that
            // is missed. Anyone who needs the watch quiet has OFF one tap away.
            defaultOption = options[3],
        )
    }

    /**
     * The next alarm, drawn on the daylight band. One mark, because the platform offers exactly
     * one: the soonest alarm for the user, with the rest kept inside whichever clock app set
     * them and exposed to nobody.
     */
    private fun alarmMarkSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, ALARM_OFF, R.string.alarm_off, R.string.alarm_off_sr),
            listOption(res, ALARM_ON, R.string.alarm_on, R.string.alarm_on_sr),
        )
        return ListUserStyleSetting(
            ALARM_MARK,
            res,
            R.string.style_alarm_name,
            R.string.style_alarm_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            defaultOption = options[0],
        )
    }

    /**
     * Whether the hours the calendar has claimed are marked on the band. An indicator and not an
     * agenda: overlapping events are one mark, because to a dial three meetings at ten are still
     * "ten is busy", and whoever needs to know which opens the calendar.
     */
    private fun calendarMarksSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, CALENDAR_OFF, R.string.calendar_off, R.string.calendar_off_sr),
            listOption(res, CALENDAR_ON, R.string.calendar_on, R.string.calendar_on_sr),
        )
        return ListUserStyleSetting(
            CALENDAR_MARKS,
            res,
            R.string.style_calendar_name,
            R.string.style_calendar_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            defaultOption = options[0],
        )
    }

    /**
     * Whether the day is kept at all. Off by default, and it must stay that way: it runs a
     * foreground service for the life of the day and lights an optical LED against the skin
     * every few minutes. Both are things to be asked for, never assumed.
     */
    /**
     * Sleep from a watch that was not being worn — off by default, and asked for by name.
     *
     * A watch on the bedside charger can only say that nothing moved, which is a much weaker
     * thing than a wrist that is still with a pulse near its floor: no phases, no wakings worth
     * the name, and a drawer looks exactly like a good night. Offered anyway, because a wearer
     * who takes the watch off to charge overnight currently gets nothing at all, and a rough
     * answer they have switched on knowingly beats a blank.
     */
    private fun sleepOffBodySetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, SLEEP_OFFBODY_OFF, R.string.offbody_off, R.string.offbody_off_sr),
            listOption(res, SLEEP_OFFBODY_ON, R.string.offbody_on, R.string.offbody_on_sr),
        )
        return ListUserStyleSetting(
            SLEEP_OFFBODY,
            res,
            R.string.style_offbody_name,
            R.string.style_offbody_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE, WatchFaceLayer.COMPLICATIONS_OVERLAY),
            defaultOption = options[0],
        )
    }

    private fun recordSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, RECORD_OFF, R.string.record_off, R.string.record_off_sr),
            listOption(res, RECORD_ON, R.string.record_on, R.string.record_on_sr),
        )
        return ListUserStyleSetting(
            RECORD,
            res,
            R.string.style_record_name,
            R.string.style_record_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE, WatchFaceLayer.COMPLICATIONS_OVERLAY),
            defaultOption = options[0],
        )
    }

    /**
     * How often the pulse is sampled — the LED's whole cost, and the trail's resolution. Ten
     * minutes by default: two samples in every quarter-hour bin, which survives an alarm the
     * platform serves late without leaving the bin empty.
     */
    private fun recordIntervalSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, "5", R.string.record_5, R.string.record_5_sr),
            listOption(res, "10", R.string.record_10, R.string.record_10_sr),
            listOption(res, "15", R.string.record_15, R.string.record_15_sr),
        )
        return ListUserStyleSetting(
            RECORD_INTERVAL,
            res,
            R.string.style_record_interval_name,
            R.string.style_record_interval_desc,
            null,
            options,
            listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
            defaultOption = options[1],
        )
    }

    private fun logHeartRateSetting(res: Resources): ListUserStyleSetting {
        val options = listOf(
            listOption(res, LOG_HR_OFF, R.string.log_hr_off, R.string.log_hr_off_sr),
            listOption(res, LOG_HR_ON, R.string.log_hr_on, R.string.log_hr_on_sr),
        )
        return ListUserStyleSetting(
            LOG_HEART_RATE,
            res,
            R.string.style_log_hr_name,
            R.string.style_log_hr_desc,
            null,
            options,
            listOf(WatchFaceLayer.COMPLICATIONS_OVERLAY),
            // Off by default: it runs an LED against the wearer's skin and it writes a physiological
            // reading into a record. Both are things to be asked for, never assumed.
            defaultOption = options[0],
        )
    }

    private fun ambientDensitySetting(res: Resources): ListUserStyleSetting {
        // Adding AUTO changed the schema, which invalidates every stored style on update — the
        // known trap, worth the warning in the release notes.
        val options = listOf(
            listOption(res, AMBIENT_FULL, R.string.ambient_full, R.string.ambient_full_sr),
            listOption(res, AMBIENT_HALF, R.string.ambient_half, R.string.ambient_half_sr),
            listOption(res, AMBIENT_AUTO, R.string.ambient_auto, R.string.ambient_auto_sr),
        )
        return ListUserStyleSetting(
            AMBIENT_DENSITY,
            res,
            R.string.style_ambient_density_name,
            R.string.style_ambient_density_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            defaultOption = options[0],
        )
    }

    /**
     * One of the two optional readouts either side of the hub.
     *
     * Both slots share this factory and therefore share an option list. The editor greys out the
     * reading the other slot has taken: two copies of the same number is not harmful, but it is
     * never what was meant, and a pair of slots showing one thing has thrown away the pair.
     *
     * Off by default. Two of the three cost a runtime permission and the heart rate costs an LED
     * against the wrist, and neither is a price to charge somebody who did not ask.
     */
    private fun sensorSlotSetting(
        res: Resources,
        id: UserStyleSetting.Id,
        nameRes: Int,
    ): ListUserStyleSetting {
        val options = listOf(
            listOption(res, SensorSlots.Kind.OFF.id, R.string.sensor_off, R.string.sensor_off_sr),
            listOption(res, SensorSlots.Kind.HEART_RATE.id, R.string.sensor_hr, R.string.sensor_hr_sr),
            listOption(res, SensorSlots.Kind.STEPS.id, R.string.sensor_steps, R.string.sensor_steps_sr),
            listOption(res, SensorSlots.Kind.PRESSURE.id, R.string.sensor_qfe, R.string.sensor_qfe_sr),
        )
        return ListUserStyleSetting(
            id,
            res,
            nameRes,
            R.string.style_sensor_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
            defaultOption = options[0],
        )
    }

    private fun listOption(res: Resources, id: String, nameRes: Int, screenReaderRes: Int) =
        ListOption(UserStyleSetting.Option.Id(id), res, nameRes, screenReaderRes, null)
}
