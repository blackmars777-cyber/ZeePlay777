plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

val edition = rootProject.extra["diplayEdition"] as String
@Suppress("UNCHECKED_CAST")
val editionConfig = rootProject.extra["diplayEditionConfig"] as Map<String, String>

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        buildConfigField("boolean", "IS_ZEEKR", (edition == "zeekr").toString())
        buildConfigField("String", "DEFAULT_OEM_LABEL", "\"${editionConfig.getValue("carPlayOemLabel")}\"")
        buildConfigField("String", "EDITION_APPLICATION_LABEL", "\"${editionConfig.getValue("applicationLabel")}\"")
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/$edition/java")
            assets.srcDir("src/$edition/assets")
        }
        getByName("test").kotlin.srcDir("src/test${edition.replaceFirstChar { it.uppercaseChar() }}/java")
        // Build-type overlays can replace default resources; two main res directories cannot.
        getByName("debug").res.srcDir("src/$edition/res")
        getByName("release").res.srcDir("src/$edition/res")
        if (edition == "zeekr") {
            getByName("debug").manifest.srcFile("src/zeekr/AndroidManifest.xml")
            getByName("release").manifest.srcFile("src/zeekr/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes. 1g sat right at the
        // ceiling: adding one more Robolectric class pushed a full run into OutOfMemoryError in
        // unrelated tests, on CI and locally both, which is what BydSettingsDecoyTest measured.
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}
