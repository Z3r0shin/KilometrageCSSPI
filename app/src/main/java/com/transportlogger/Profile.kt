package com.transportlogger

import android.content.Context

/**
 * Who files the claim: the header of the form. Asked once on first launch and
 * kept on the phone only, so the same app serves any member of the team.
 */
data class Profile(
    val name: String = "",
    val employeeNo: String = "",
    val address: String = "",
    val city: String = "",
    val postalCode: String = "",
    val function: String = DEF_FUNCTION,
    val service: String = DEF_SERVICE
) {
    /** Enough to file a claim: a name and an employee number. */
    val complete: Boolean get() = name.isNotBlank() && employeeNo.isNotBlank()

    companion object {
        const val DEF_FUNCTION = "OCE"
        const val DEF_SERVICE = "SRM"

        private const val PREFS = "profile"

        fun load(ctx: Context): Profile {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Profile(
                p.getString("name", "") ?: "",
                p.getString("employeeNo", "") ?: "",
                p.getString("address", "") ?: "",
                p.getString("city", "") ?: "",
                p.getString("postalCode", "") ?: "",
                p.getString("function", DEF_FUNCTION) ?: DEF_FUNCTION,
                p.getString("service", DEF_SERVICE) ?: DEF_SERVICE
            )
        }

        fun save(ctx: Context, profile: Profile) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("name", profile.name.trim())
                .putString("employeeNo", profile.employeeNo.trim())
                .putString("address", profile.address.trim())
                .putString("city", profile.city.trim())
                .putString("postalCode", profile.postalCode.trim())
                .putString("function", profile.function.trim())
                .putString("service", profile.service.trim())
                .apply()
        }
    }
}
