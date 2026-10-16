package com.focuslock.app.ui.onboarding

/** Concrete, optional actions that let a person inspect each feature in the app. */
internal fun ProductTourPage.liveTasks(): List<String> = when (id) {
    "welcome" -> listOf(
        "Open Focus and look over the available focus tools.",
        "Choose a tour section to explore next.",
    )
    "boundaries" -> listOf(
        "Open Boundaries and browse the Apps list.",
        "Open the Websites list.",
        "Open a target's options to review its access rules.",
    )
    "groups" -> listOf(
        "Open Applications or Websites in Boundaries, then choose Merge.",
        "Select the apps or sites you want to share a budget, then save the merged group.",
    )
    "limits" -> listOf(
        "Browse the Boundaries list to compare the daily allowances on your targets.",
        "Choose a daily allowance that fits one of your selected boundaries.",
    )
    "schedules" -> listOf(
        "Open Settings and choose Preferences.",
        "Add a block schedule for hours you want protected.",
        "Choose the days for your schedule.",
        "Set the schedule start and end times, then save it.",
    )
    "location" -> listOf(
        "Open Boundaries and choose Blocking location.",
        "Review the home location controls before deciding whether to save a place.",
        "Choose a radius only if you want to use home-only blocking.",
        "Review the location permission prompt before deciding whether to grant access.",
    )
    "commitments" -> listOf(
        "Open Settings and choose Permissions & protection.",
        "Review the Boundaries Lock switch and what it prevents before deciding whether to turn it on.",
        "In Boundaries, open a target's options and review the permanent block choice.",
        "Leave the permanent block choice off.",
    )
    "credits" -> listOf(
        "Open Focus and start a focus session with the timer.",
        "Open Settings and choose Preferences.",
        "Set the work-to-leisure ratio to a value that suits your routine.",
        "Set the focus-session bonus to a value you want to earn.",
    )
    "frog" -> listOf(
        "Open Settings and choose Daily priority routine.",
        "Review the Frog switch without enabling Frog.",
        "If you choose to enable Frog, set its required focus minutes.",
        "If you choose to enable Frog, set its wake hour.",
        "Open Essential apps and choose which apps should stay available.",
    )
    "strict" -> listOf(
        "Open Strict Mode and review the available commitment and protection controls.",
        "Review available unlock and recovery methods, including timer, schedules, PIN, charger, cooldown, and approval.",
        "Leave Strict Mode off while you inspect its settings.",
    )
    "permissions" -> listOf(
        "Open Settings and choose Permissions & protection.",
        "Review Accessibility and Usage Access and what each permission enables.",
        "Review overlay, battery optimization, and notification access controls.",
        "Review Device admin protection and its deactivation warning without changing it.",
    )
    "nuke" -> listOf(
        "Long-press Nuke on Focus to read its explanation.",
        "Dismiss the explanation without starting Nuke.",
    )
    "ticktick" -> listOf(
        "Open Settings and choose Connections.",
        "Review TickTick connection status.",
        "Choose Connect with TickTick only if you want to link that account.",
        "If already connected, choose Sync focus now to refresh completed sessions.",
        "Open Advanced provider options to inspect alternatives.",
        "Close advanced options without entering credentials.",
    )
    "account" -> listOf(
        "Open Account & devices from Settings.",
        "Review the signed-in account details.",
        "Review the sync status and retry control.",
        "Review the signed-in device list.",
    )
    "settings" -> listOf(
        "Open Settings and choose Account & devices.",
        "Return to Settings and choose Connections.",
        "Return to Settings and choose Daily priority routine.",
        "Return to Settings and choose Permissions & protection.",
        "Return to Settings and choose Preferences.",
        "Set the work-to-leisure ratio to a value that suits your routine.",
        "Set the focus-session bonus to a value you want to earn.",
        "Set your daily focus goal.",
        "Set your daily tasks goal.",
        "Choose your preferred Focus home style.",
        "Enable the daily reminder if a daily nudge would help.",
        "Choose the reminder time if you enabled the reminder.",
        "Export a backup of your current configuration.",
        "Open Import config to read its replacement warning.",
        "Cancel the import without replacing your configuration.",
    )
    "first-setup" -> listOf(
        "Add one or two apps or sites you want to protect in Boundaries.",
        "Open Permissions & protection and grant the access needed for the protection you chose.",
        "Start a short focus session.",
        "Return to Settings to resume this guide whenever you need it.",
    )
    else -> emptyList()
}
