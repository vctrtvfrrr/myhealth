package br.etc.victor.myhealthbridge

import androidx.annotation.StringRes

/** The screens the application opens on, in display order. */
enum class MainTab(@param:StringRes val title: Int) {
    PERMISSIONS(R.string.tab_permissions),
    SYNC(R.string.tab_sync),
    DIAGNOSTICS(R.string.tab_diagnostics),
    ;

    companion object {

        /** What a maintenance notification asks the application to open. */
        const val DIAGNOSTICS_ACTION: String = "br.etc.victor.myhealthbridge.action.OPEN_DIAGNOSTICS"

        /**
         * Where an intent opens the application.
         *
         * Only an intent that names the diagnostics by action reaches them, so the launcher icon keeps
         * opening what it always did.
         */
        fun of(action: String?): MainTab = if (action == DIAGNOSTICS_ACTION) DIAGNOSTICS else PERMISSIONS
    }
}
