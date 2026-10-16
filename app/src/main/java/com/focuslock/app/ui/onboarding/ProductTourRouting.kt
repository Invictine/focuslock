package com.focuslock.app.ui.onboarding

/** Route each task to the real feature, including steps that cross Settings categories. */
internal fun ProductTourPage.liveDestination(step: Int): ProductTourDestination = when (id) {
    "settings" -> when (step) {
        0 -> ProductTourDestination.ACCOUNT
        1 -> ProductTourDestination.CONNECTIONS
        2 -> ProductTourDestination.ROUTINE
        3 -> ProductTourDestination.PROTECTION
        else -> ProductTourDestination.PREFERENCES
    }
    "credits" -> if (step == 0) ProductTourDestination.FOCUS else ProductTourDestination.PREFERENCES
    "commitments" -> if (step >= 2) ProductTourDestination.BOUNDARIES else ProductTourDestination.PROTECTION
    "first-setup" -> when (step) {
        0 -> ProductTourDestination.BOUNDARIES
        1 -> ProductTourDestination.PROTECTION
        2 -> ProductTourDestination.FOCUS
        else -> ProductTourDestination.PREFERENCES
    }
    else -> destination ?: ProductTourDestination.FOCUS
}
