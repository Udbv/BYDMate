package com.bydmate.app.hma

/**
 * The HMA journal: one line per change of the HMA state, the high beam lamp, the gray flag, the
 * cluster fault, the headlight knob or the sensor error set — never a line for a repeat. Every
 * line starts with [PREFIX] and is logged under [TAG], so `grep HmaWatch` finds them in logcat,
 * in the trip log and in the files the car sends to Telegram. No position is ever part of it.
 */
object HmaJournal {

    const val TAG = "HmaWatch"
    const val PREFIX = "HMA"

    /** The changes between two readings, as short event texts (`state PASSIVE->ACTIVE`). */
    fun changes(old: HmaReading, new: HmaReading): List<String> {
        val out = mutableListOf<String>()
        if (old.effectiveState != new.effectiveState) {
            out += "state ${HmaMode.label(old.effectiveState)}->${HmaMode.label(new.effectiveState)}"
        }
        if (old.highBeam != new.highBeam) out += "beam ${v(old.highBeam)}->${v(new.highBeam)}"
        if (old.gray != new.gray) out += "gray ${v(old.gray)}->${v(new.gray)}"
        if (old.clusterFault != new.clusterFault) out += "cluster-fault ${v(old.clusterFault)}->${v(new.clusterFault)}"
        if (old.knob != new.knob) out += "knob ${v(old.knob)}->${v(new.knob)}"
        if (old.present != new.present) out += "present ${v(old.present)}->${v(new.present)}"
        val wasErr = old.sensorErrors
        val nowErr = new.sensorErrors
        if (wasErr != nowErr) out += "sensor-error ${errs(wasErr)}->${errs(nowErr)}"
        return out
    }

    /**
     * One journal line: the event, then the whole context it happened in — both copies of the HMA
     * state, the lamp, the light and rain sensors, the wipers and the speed. Fixed key order, so
     * two lines compare by eye.
     */
    fun line(event: String, r: HmaReading): String = buildString {
        append(PREFIX).append(' ').append(event).append(" |")
        append(" hma=").append(HmaMode.label(r.effectiveState))
        append(" hal=").append(v(r.hmaStateHal))
        append(" plain=").append(v(r.hmaState))
        append(" gray=").append(v(r.gray))
        append(" beam=").append(v(r.highBeam))
        append(" ind=").append(v(r.indicator))
        append(" knob=").append(v(r.knob))
        append(" light=").append(v(r.lightLevel))
        append(" lux=").append(HmaReading.fmt(r.lightIntensity))
        append(" rain=").append(HmaReading.fmt(r.rainFall))
        append(" wiper=").append(v(r.wiperRelay)).append('/').append(v(r.wiperLevel))
        append(" auto=").append(v(r.autoWipers))
        append(" glass=").append(HmaReading.fmt(r.glassHumidity)).append('/').append(HmaReading.fmt(r.glassTemp))
        append(" err=").append(errs(r.sensorErrors))
        append(" v=").append(v(r.speedKph))
    }

    private fun v(x: Int?): String = x?.toString() ?: "-"

    private fun errs(list: List<String>): String = if (list.isEmpty()) "-" else list.joinToString(",")
}
