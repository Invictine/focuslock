package com.focuslock.app.ui.onboarding

internal enum class ProductTourDestination { FOCUS, BOUNDARIES, STRICT, SETTINGS, ACCOUNT }

internal data class ProductTourPage(
    val id: String,
    val section: String,
    val title: String,
    val summary: String,
    val points: List<String>,
    val destination: ProductTourDestination? = null,
    val actionLabel: String? = null,
)

internal val productTourPages = listOf(
    ProductTourPage(
        id = "welcome", section = "Start here", title = "A calmer way to use your devices",
        summary = "FocusLock turns focused work into planned leisure time while helping you protect distracting apps and websites.",
        points = listOf("Choose the controls that fit your goal.", "Start with a short session and adjust later."),
        destination = ProductTourDestination.FOCUS, actionLabel = "Open Focus",
    ),
    ProductTourPage(
        id = "navigation", section = "Start here", title = "Find your way around",
        summary = "The bottom navigation keeps the three daily workspaces together.",
        points = listOf("Focus shows sessions, balance, goals, history, and emergency controls.", "Boundaries manages apps, websites, permanent blocks, and where blocking applies.", "Strict freezes boundary changes; the gear opens Settings and the avatar opens Account."),
    ),
    ProductTourPage(
        id = "account", section = "Account and devices", title = "Account, offline use, and sync",
        summary = "Sign in when you want supported settings, usage, and commitments available across devices.",
        points = listOf("Offline mode keeps this phone usable without an account.", "Some active sessions, Frog setup, and local records remain device-specific.", "Account shows sync status and lets you retry."),
        destination = ProductTourDestination.ACCOUNT, actionLabel = "Review Account",
    ),
    ProductTourPage(
        id = "permissions", section = "Reliable protection", title = "Give Android the access it needs",
        summary = "Protection depends on the Android permissions you choose to grant.",
        points = listOf("Accessibility detects protected apps and supported browser activity; re-enable it if monitoring stops.", "Usage Access powers totals; overlay shows lockouts; battery settings help background monitoring.", "Device Admin adds uninstall protection. Notification Listener can suppress blocked-app notifications, but notifications never earn credits."),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Check permissions",
    ),
    ProductTourPage(
        id = "boundaries", section = "Boundaries", title = "Choose what to protect",
        summary = "Add distracting applications and website domains, then decide where those rules apply.",
        points = listOf("Regular boundaries can be changed later. Website protection needs Accessibility and a supported browser.", "Select and merge apps or websites to share one group budget and daily limit.", "Home-only blocking is configured here and is separate from Strict Mode's location trigger."),
        destination = ProductTourDestination.BOUNDARIES, actionLabel = "Set boundaries",
    ),
    ProductTourPage(
        id = "boundaries-lock", section = "Settings", title = "Keep boundaries from being removed",
        summary = "Boundaries Lock adds a simple removal lock in Settings.",
        points = listOf("While it is on, protected apps and websites cannot be removed from Boundaries.", "This is separate from a timed Strict commitment and from irreversible permanent blocks."),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Review Boundaries Lock",
    ),
    ProductTourPage(
        id = "home-location", section = "Boundaries", title = "Home-only blocking fails safe",
        summary = "Home-only rules pause only after Android confirms that you are reliably away.",
        points = listOf("A confirmed away reading can pause ordinary location-bound rules.", "Missing, stale, uncertain, or inaccessible location keeps blocking active.", "Permanent blocks bypass location and continue everywhere."),
        destination = ProductTourDestination.BOUNDARIES, actionLabel = "Set blocking location",
    ),
    ProductTourPage(
        id = "credits", section = "Focus and credits", title = "Make focused work count",
        summary = "Completed focus time earns leisure according to your work-to-leisure ratio.",
        points = listOf("At 10:1, 60 minutes of work earns 6 minutes of leisure before any configured bonus.", "Use the built-in timer or log work manually; TickTick is optional.", "Selected leisure apps and websites spend your balance. When it runs out, they lock; schedules and daily caps can also restrict access."),
        destination = ProductTourDestination.FOCUS, actionLabel = "Try Focus",
    ),
    ProductTourPage(
        id = "limits", section = "Boundaries", title = "Limits and schedules",
        summary = "Time limits and recurring schedules control when selected boundaries apply.",
        points = listOf("Set daily limits for selected apps or sites.", "Create weekly block windows and edit them when boundaries are not frozen.", "Strict Mode can freeze these settings until its commitment ends."),
        destination = ProductTourDestination.BOUNDARIES, actionLabel = "Open Boundaries",
    ),
    ProductTourPage(
        id = "frog", section = "Eat the Frog", title = "An optional task-based commitment",
        summary = "Frog is an opt-in hard-lock routine for completing one important task.",
        points = listOf("Choose required focus minutes and an explicit task; the timer does not complete the task for you.", "At your wake hour, a new daily task cycle starts. Turning on Frog can block other apps until your task is finished.", "Confirm task tools in Focus and choose Essential apps in Settings. Permanent blocks and website boundaries still take precedence."),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Configure Frog",
    ),
    ProductTourPage(
        id = "launcher", section = "Eat the Frog", title = "Optional launcher protection",
        summary = "Frog can make FocusLock your Home app during a hard-lock.",
        points = listOf("Prevent launcher escape is optional and only applies when enabled.", "To restore your normal launcher, change Android's default Home app in system settings."),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Review Frog",
    ),
    ProductTourPage(
        id = "strict", section = "Strict Mode", title = "Freeze boundary changes",
        summary = "Strict Mode is a commitment that locks boundary configuration.",
        points = listOf("Choose a duration, a schedule, or a location trigger.", "Strict freezes boundary edits, including groups, limits, and schedules; normal blocking rules still decide access.", "A configured trusted person can approve an early exit. Review the end time before activating."),
        destination = ProductTourDestination.STRICT, actionLabel = "Review Strict Mode",
    ),
    ProductTourPage(
        id = "permanent", section = "Boundaries", title = "Permanent blocks",
        summary = "Permanent blocks are commitments you do not remove from inside the app.",
        points = listOf("Apps or websites marked permanent cannot be removed in the app.", "Review the target carefully before saving it.", "They remain part of your blocking policy while other settings change."),
        destination = ProductTourDestination.BOUNDARIES, actionLabel = "Review permanent blocks",
    ),
    ProductTourPage(
        id = "nuke", section = "Emergency control", title = "Nuke is an opt-in reset",
        summary = "Nuke is a separate emergency lockdown, not a normal focus timer.",
        points = listOf("Once active, wait for its 10-minute guided breathing countdown to finish.", "Leaving the screen does not pause the countdown.", "Use it deliberately from Focus when you need a hard interruption."),
        destination = ProductTourDestination.FOCUS, actionLabel = "See Nuke",
    ),
    ProductTourPage(
        id = "ticktick", section = "Integrations", title = "TickTick focus imports",
        summary = "TickTick can import completed Pomodoro and stopwatch focus sessions as work records.",
        points = listOf("Connect from Settings using browser login or the available token fallback.", "Completed sessions sync automatically while protection is running; missed sessions from the past week can be recovered without earning twice.", "Task notifications alone do not earn credits. Review the work ratio and focus-session bonus in Settings."),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Connect TickTick",
    ),
    ProductTourPage(
        id = "personalize", section = "Settings", title = "Tune the routine",
        summary = "Settings holds the controls that shape your day.",
        points = listOf("Choose a Focus home style and set daily focus and task goals.", "Turn on a daily reminder and choose its time.", "Adjust the work ratio after deciding how much leisure each hour should earn."),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Personalize",
    ),
    ProductTourPage(
        id = "recovery", section = "Settings", title = "Back up and recover configuration",
        summary = "Keep a portable copy of the configuration that matters to you.",
        points = listOf("Export boundaries, limits, schedules, and goals as JSON.", "Importing a file replaces those configuration areas, so review the confirmation carefully.", "Use Account sync for supported cross-device data and backups for a local recovery copy."),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Open backup tools",
    ),
    ProductTourPage(
        id = "first-setup", section = "Finish setup", title = "A practical first setup",
        summary = "Start small, verify protection, and then add stronger commitments.",
        points = listOf("Grant the permissions you want, add a few boundaries, and test a short focus session.", "Check your balance and history, then enable schedules, Frog, Strict Mode, or permanent blocks only when their consequences are clear.", "Reopen this guide from Settings whenever you need it."),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Finish in Settings",
    ),
)
