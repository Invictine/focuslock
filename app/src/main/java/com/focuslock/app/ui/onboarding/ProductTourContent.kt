package com.focuslock.app.ui.onboarding

internal enum class ProductTourDestination { FOCUS, BOUNDARIES, STRICT, SETTINGS, ACCOUNT, ROUTINE, CONNECTIONS, PROTECTION, PREFERENCES }

internal data class ProductTourPage(
    val id: String,
    val section: String,
    val title: String,
    val summary: String,
    val points: List<String>,
    val exampleTitle: String,
    val exampleSteps: List<String>,
    val destination: ProductTourDestination? = null,
    val actionLabel: String? = null,
)

/** Short, feature-led guide content. Examples illustrate the flow without changing any settings. */
internal val productTourPages = listOf(
    ProductTourPage(
        id = "welcome", section = "Getting started", title = "Build a setup that fits you",
        summary = "FocusLock helps you protect attention, then earn planned time for the apps you choose.",
        points = listOf("Take a quick tour, then choose what to set up."),
        exampleTitle = "Your setup, your choice",
        exampleSteps = listOf("Choose boundaries", "Try a focus session", "Add commitments only if useful"),
        destination = ProductTourDestination.FOCUS, actionLabel = "Open Focus",
    ),
    ProductTourPage(
        id = "boundaries", section = "Boundaries", title = "Choose what to protect",
        summary = "Add distracting apps or website domains to Boundaries, then decide when they can open.",
        points = listOf(
            "Website protection works in supported browsers when Accessibility is enabled.",
            "You can add more apps or sites whenever you need a stronger boundary.",
        ),
        exampleTitle = "One way to start",
        exampleSteps = listOf("Pick an app or site", "Set its rules", "Test the block"),
        destination = ProductTourDestination.BOUNDARIES, actionLabel = "Choose boundaries",
    ),
    ProductTourPage(
        id = "groups", section = "Boundaries", title = "Give related distractions one budget",
        summary = "Group apps and websites so they share the same daily allowance.",
        points = listOf("Switching between targets in a group still uses the same budget."),
        exampleTitle = "One social budget",
        exampleSteps = listOf("Instagram app + website", "One shared 20-minute limit", "All members stop at the limit"),
        destination = ProductTourDestination.BOUNDARIES, actionLabel = "Explore groups",
    ),
    ProductTourPage(
        id = "limits", section = "Boundaries", title = "Set a daily limit",
        summary = "Choose how much time a protected app, website or group gets each day.",
        points = listOf(
            "When the allowance runs out, that boundary stays blocked until its daily reset.",
        ),
        exampleTitle = "A deliberate limit",
        exampleSteps = listOf("Choose YouTube", "Allow 20 minutes a day", "See progress in Activity"),
        destination = ProductTourDestination.BOUNDARIES, actionLabel = "Review limits",
    ),
    ProductTourPage(
        id = "schedules", section = "Boundaries", title = "Protect the hours that matter",
        summary = "Schedules block selected distractions during the days and times you choose.",
        points = listOf("A block schedule controls access. A Strict Mode schedule protects your settings."),
        exampleTitle = "Example schedule",
        exampleSteps = listOf("Weekdays", "9:00 AM–3:00 PM", "Selected study distractions"),
        destination = ProductTourDestination.PREFERENCES, actionLabel = "Review block schedules",
    ),
    ProductTourPage(
        id = "location", section = "Blocking location", title = "Choose where everyday rules apply",
        summary = "Home-only blocking pauses everyday boundaries when Android reliably confirms you are away.",
        points = listOf("Unknown location keeps blocking active. Permanent blocks apply everywhere.",
            "Your saved home syncs with your account. Each device needs its own consent and location permission."),
        exampleTitle = "A place, then permission",
        exampleSteps = listOf("Boundaries → Blocking location", "Save home and choose a radius", "Enable location access on this device"),
        destination = ProductTourDestination.BOUNDARIES, actionLabel = "Review blocking location",
    ),
    ProductTourPage(
        id = "commitments", section = "Boundaries", title = "Know the different locks",
        summary = "Boundaries Lock and permanent blocks protect settings in different ways.",
        points = listOf(
            "Boundaries Lock prevents removing protected apps and sites until you turn it off in Settings.",
            "Permanent blocks cannot be removed from inside FocusLock. Check each target before saving it.",
        ),
        exampleTitle = "Two levels of commitment",
        exampleSteps = listOf("Boundaries Lock · reversible in Settings", "Permanent block · stays in policy"),
        destination = ProductTourDestination.PROTECTION, actionLabel = "Review lock options",
    ),
    ProductTourPage(
        id = "credits", section = "Focus and credits", title = "Turn focus time into leisure",
        summary = "Completed focus work earns time for the leisure apps and sites you select.",
        points = listOf(
            "At a 10:1 ratio, 60 minutes of work earns 6 minutes of leisure before bonuses.",
            "Use the built-in timer, log work manually, or connect TickTick. When balance runs out, selected leisure apps and sites lock; schedules and daily limits still apply.",
        ),
        exampleTitle = "A simple exchange",
        exampleSteps = listOf("60 min focused work", "10:1 work ratio", "+6 min leisure balance"),
        destination = ProductTourDestination.FOCUS, actionLabel = "Try Focus",
    ),
    ProductTourPage(
        id = "frog", section = "Eat the Frog", title = "Frog is an optional hard-lock",
        summary = "Use Frog when you want one important task to guide your day.",
        points = listOf(
            "Choose a task and required focus time. FocusLock does not mark the task complete when the timer ends.",
            "If you enable hard-lock, other apps can stay blocked until you finish the task. Essential apps and launcher protection are optional settings.",
            "Frog starts off. This guide will never turn it on for you.",
        ),
        exampleTitle = "A Frog day",
        exampleSteps = listOf("Choose today's task", "Complete required focus", "Mark the task done yourself"),
        destination = ProductTourDestination.ROUTINE, actionLabel = "Review Frog settings",
    ),
    ProductTourPage(
        id = "strict", section = "Strict Mode", title = "Freeze boundary changes",
        summary = "Strict Mode locks boundary settings for a commitment you choose.",
        points = listOf(
            "Choose timer, schedules, PIN, charger, cooldown or approval access. Review your protections before starting.",
            "Set up your way out before you commit. Location rules have their own screen in Boundaries.",
            "Strict Mode starts off. Reading this guide never activates it.",
        ),
        exampleTitle = "Before you start",
        exampleSteps = listOf("Choose a commitment", "Review what will be frozen", "Activate when ready"),
        destination = ProductTourDestination.STRICT, actionLabel = "Review Strict Mode",
    ),
    ProductTourPage(
        id = "permissions", section = "Android protection", title = "Choose Android access",
        summary = "Android permissions help FocusLock detect protected apps and keep monitoring reliably.",
        points = listOf(
            "Accessibility detects protected apps and supported browser activity. Usage Access provides totals; overlay displays lock screens.",
            "Battery settings help background monitoring. Device Admin adds uninstall protection. Notification access can suppress blocked-app alerts; notifications never earn credits.",
            "Grant only the access you want. Nothing is enabled by reading this guide.",
        ),
        exampleTitle = "Set up at your pace",
        exampleSteps = listOf("See what each access does", "Open Android settings", "Come back to verify"),
        destination = ProductTourDestination.PROTECTION, actionLabel = "Review permissions",
    ),
    ProductTourPage(
        id = "nuke", section = "Emergency control", title = "Nuke is a separate reset",
        summary = "Use Nuke when you need a firm interruption beyond your usual focus session.",
        points = listOf(
            "Starting Nuke begins a 10-minute guided breathing countdown.",
            "After the countdown, check in with a plan to unlock. Start when you have time for both steps.",
        ),
        exampleTitle = "The Nuke flow",
        exampleSteps = listOf("Start deliberately", "Complete the breathing countdown", "Check in with your plan"),
        destination = ProductTourDestination.FOCUS, actionLabel = "See Nuke on Focus",
    ),
    ProductTourPage(
        id = "ticktick", section = "Integrations", title = "Import completed TickTick focus",
        summary = "Connect TickTick to count completed Pomodoro and stopwatch sessions as work.",
        points = listOf(
            "Sessions sync while FocusLock protection is running. You can recover missed sessions from the past week without earning twice.",
            "Task notifications do not earn credits. Review your work ratio and any focus-session bonus in Settings.",
        ),
        exampleTitle = "What counts",
        exampleSteps = listOf("Completed Pomodoro", "Completed stopwatch", "Task notification · no credit"),
        destination = ProductTourDestination.CONNECTIONS, actionLabel = "Connect TickTick",
    ),
    ProductTourPage(
        id = "account", section = "Account and devices", title = "Choose account or offline use",
        summary = "Sign in to sync supported settings and records, or keep using this phone offline.",
        points = listOf(
            "Account shows sync status and lets you retry. Offline mode does not require sign-in.",
            "Some active sessions, Frog setup, and local records stay on this device.",
        ),
        exampleTitle = "Where your data lives",
        exampleSteps = listOf("Supported data · syncs to your account", "Local records · stay on this phone"),
        destination = ProductTourDestination.ACCOUNT, actionLabel = "Review Account",
    ),
    ProductTourPage(
        id = "settings", section = "Make it yours", title = "Tune your daily routine",
        summary = "Settings brings together goals, reminders, Focus home style, and work-to-leisure ratio.",
        points = listOf(
            "Set a daily focus goal and reminder, then choose a ratio that feels right for you.",
            "Export boundaries, limits, schedules, and goals as a JSON backup. Import replaces those areas, so review its confirmation carefully.",
        ),
        exampleTitle = "A small first setup",
        exampleSteps = listOf("Set one daily goal", "Pick a reminder time", "Export a backup when ready"),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Personalize Settings",
    ),
    ProductTourPage(
        id = "first-setup", section = "Ready when you are", title = "Start small, then build",
        summary = "Try a few everyday tools first. Add stronger commitments only when their effects are clear.",
        points = listOf(
            "Choose boundaries, review permissions, and test a short focus session.",
            "You can reopen this guide from Settings whenever you need a refresher.",
        ),
        exampleTitle = "Your first steps",
        exampleSteps = listOf("Protect a few distractions", "Try Focus", "Return to this guide any time"),
        destination = ProductTourDestination.SETTINGS, actionLabel = "Start setup",
    ),
)
