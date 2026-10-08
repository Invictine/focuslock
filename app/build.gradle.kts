import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
val ticktickClientId: String = (localProps.getProperty("ticktick.clientId") ?: "").trim()
val ticktickClientSecret: String = (localProps.getProperty("ticktick.clientSecret") ?: "").trim()
val clerkPublishableKey: String = (localProps.getProperty("clerk.publishableKey") ?: System.getenv("CLERK_PUBLISHABLE_KEY") ?: "").trim()
val convexUrl: String = (localProps.getProperty("convex.url") ?: System.getenv("CONVEX_URL") ?: "").trim()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.focuslock.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.focuslock.app"
        minSdk = 26
        targetSdk = 36
        // Private distribution builds get a monotonic install/update counter while
        // the visible product version continues to follow major.feature.fix.
        versionCode = providers.gradleProperty("focuslockVersionCode")
            .map { value ->
                value.toInt().also { require(it in 2..2_100_000_000) { "focuslockVersionCode must be between 2 and 2100000000" } }
            }.getOrElse(2)
        versionName = "0.6.17"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // TickTick OAuth baked from local.properties (gitignored). Empty = not configured.
        buildConfigField("String", "TICKTICK_CLIENT_ID", "\"$ticktickClientId\"")
        buildConfigField("String", "TICKTICK_CLIENT_SECRET", "\"$ticktickClientSecret\"")
        // Clerk auth + Convex sync (gitignored local.properties or env). Empty = auth-gated off.
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", "\"$clerkPublishableKey\"")
        buildConfigField("String", "CONVEX_URL", "\"$convexUrl\"")
    }

    buildTypes {
        release {
            // R8 shrinking (perf/build fix, item 12): shrinks the Compose
            // material-icons-extended dependency down to the icons actually used and
            // strips dead code. Required keep rules live in proguard-rules.pro
            // (kotlinx-serialization, Clerk SDK, WorkManager workers).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Locally installable optimized build. Uses the existing development key so
        // it can replace debug without uninstalling or losing settings/account data.
        create("performance") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.work.runtime)
    implementation(libs.clerk.android.ui)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
