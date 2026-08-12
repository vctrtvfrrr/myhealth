package br.etc.victor.myhealthbridge.samsung

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * The resumed Activity the Samsung Health consent flow needs to run on.
 *
 * It is held weakly and only between onResume and onPause, so an Activity that went away can never
 * be handed to the SDK and never outlives its own destruction.
 */
object ForegroundActivity {

    private var reference: WeakReference<Activity>? = null

    val current: Activity?
        get() = reference?.get()

    fun bind(activity: Activity) {
        reference = WeakReference(activity)
    }

    fun unbind(activity: Activity) {
        if (reference?.get() === activity) reference = null
    }
}
