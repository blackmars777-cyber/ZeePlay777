plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shihab.diplay.zeekr.vehicleprobe"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.shihab.diplay.zeekr.vehicleprobe"
        minSdk = 24
        targetSdk = 37
        // Permit upgrading the earlier full-app probe (versionCode 41) in place.
        versionCode = 45
        versionName = "1.2.1-probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Platform widgets and car API reflection only. No :common, :shared, Compose, media or native code.
dependencies {
    implementation(libs.androidx.core.ktx) { // FileProvider for explicit report sharing.
        // A foreground diagnostic tool does not need a profile receiver or startup initializer.
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
