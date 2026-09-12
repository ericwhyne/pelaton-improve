package com.onepeloton.workoutservices.library.core.dto.metrics

import android.os.Parcel
import android.os.Parcelable

/**
 * Local stub matching Peloton's MetricsUpdateSource wire format so that metrics
 * bundles from MetricsService can be unparceled in this process.
 * Each variant writes a single int(1); CREATOR reads one int.
 */
interface MetricsUpdateSource : Parcelable {

    class App : MetricsUpdateSource {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(1)
        override fun toString() = "App"

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<App> {
                override fun createFromParcel(parcel: Parcel): App {
                    parcel.readInt()
                    return App()
                }

                override fun newArray(size: Int): Array<App?> = arrayOfNulls(size)
            }
        }
    }

    class Hardware : MetricsUpdateSource {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(1)
        override fun toString() = "Hardware"

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<Hardware> {
                override fun createFromParcel(parcel: Parcel): Hardware {
                    parcel.readInt()
                    return Hardware()
                }

                override fun newArray(size: Int): Array<Hardware?> = arrayOfNulls(size)
            }
        }
    }

    class Unknown : MetricsUpdateSource {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(1)
        override fun toString() = "Unknown"

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<Unknown> {
                override fun createFromParcel(parcel: Parcel): Unknown {
                    parcel.readInt()
                    return Unknown()
                }

                override fun newArray(size: Int): Array<Unknown?> = arrayOfNulls(size)
            }
        }
    }
}
