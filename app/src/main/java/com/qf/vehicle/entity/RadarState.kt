package com.qf.vehicle.entity

import android.os.Parcel
import android.os.Parcelable

/**
 * The QF firmware car app's parking-sensor state, as it sends it in its
 * "com.qf.vehicle.action.RADAR" broadcast. A Parcelable is read back by its
 * class name, so this copy lives under the car app's own name and reads the
 * fields in the exact order the car app writes them (worked out from its
 * RadarState.writeToParcel). Levels are bytes, -1 for a sensor the car
 * doesn't have; distances are ints in the car app's unit. Kept by name in
 * proguard-rules.pro.
 */
class RadarState private constructor(
    val state: Byte,
    /** Left front 1..3, right front 3..1: left to right. */
    val frontLevels: ByteArray,
    val frontDistances: IntArray,
    /** Left back 1..3, right back 3..1: left to right. */
    val rearLevels: ByteArray,
    val rearDistances: IntArray,
    /** Left side 1..4, then right side 1..4. */
    val sideLevels: ByteArray,
    val sideDistances: IntArray
) : Parcelable {

    private constructor(p: Parcel) : this(
        state = p.readByte(),
        frontLevels = ByteArray(6) { p.readByte() },
        frontDistances = IntArray(6) { p.readInt() },
        rearLevels = ByteArray(6) { p.readByte() },
        rearDistances = IntArray(6) { p.readInt() },
        sideLevels = ByteArray(8) { p.readByte() },
        sideDistances = IntArray(8) { p.readInt() }
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByte(state)
        frontLevels.forEach(dest::writeByte)
        frontDistances.forEach(dest::writeInt)
        rearLevels.forEach(dest::writeByte)
        rearDistances.forEach(dest::writeInt)
        sideLevels.forEach(dest::writeByte)
        sideDistances.forEach(dest::writeInt)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<RadarState> = object : Parcelable.Creator<RadarState> {
            override fun createFromParcel(source: Parcel): RadarState = RadarState(source)
            override fun newArray(size: Int): Array<RadarState?> = arrayOfNulls(size)
        }
    }
}
