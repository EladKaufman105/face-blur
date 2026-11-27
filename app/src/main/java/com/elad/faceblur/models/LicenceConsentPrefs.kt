package com.elad.faceblur.models

import android.content.Context

/** [AcceptanceStorage] in its own preferences file ("model_licences"), apart from the user's settings. */
class LicencePrefs(context: Context) : AcceptanceStorage {
    private val sp = context.applicationContext.getSharedPreferences("model_licences", Context.MODE_PRIVATE)

    override fun get(pack: String): Int = sp.getInt("accepted.$pack", 0)

    override fun put(pack: String, version: Int) {
        sp.edit().putInt("accepted.$pack", version).apply()
    }

    override fun remove(pack: String) {
        sp.edit().remove("accepted.$pack").apply()
    }
}
