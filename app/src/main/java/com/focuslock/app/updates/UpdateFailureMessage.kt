package com.focuslock.app.updates

import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status

internal fun updateFailureMessage(code: Status?): String = when (code) {
    Status.AUTHENTICATION_FAILURE -> "Sign in with the Google account invited to test FocusLock, then retry."
    Status.AUTHENTICATION_CANCELED -> "Update alerts were not enabled. Tap Check for updates to sign in again."
    Status.NETWORK_FAILURE -> "Could not reach the update service. Check your connection and retry."
    Status.API_DISABLED -> "Firebase tester updates are disabled for this project. Open App Tester to update."
    Status.NOT_IMPLEMENTED -> "This build does not include in-app updates. Open App Tester to update."
    Status.HOST_ACTIVITY_INTERRUPTED -> "Update check interrupted. Keep FocusLock open and retry."
    Status.DOWNLOAD_FAILURE -> "Download failed. Check your connection and retry."
    Status.INSTALLATION_FAILURE -> "Installation failed. Open App Tester to retry."
    Status.INSTALLATION_CANCELED -> "Installation cancelled. Tap Check for updates to retry."
    Status.UPDATE_NOT_AVAILABLE -> "You have the latest build available to your tester account."
    else -> "Update check failed. Retry or open App Tester to update."
}
