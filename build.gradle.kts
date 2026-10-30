import java.util.Properties

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Explicit build input: branch names never change the contents of an APK implicitly.
val edition = providers.gradleProperty("diplay.edition").getOrElse("standard")
require(edition in setOf("standard", "zeekr")) {
    "Unknown diplay.edition '$edition'; choose standard or zeekr"
}
val editionProperties = Properties().apply {
    providers.fileContents(layout.projectDirectory.file("gradle/editions/$edition.properties"))
        .asText.get().reader().use { load(it) }
}
extra["diplayEdition"] = edition
extra["diplayEditionConfig"] = editionProperties.stringPropertyNames()
    .associateWith { editionProperties.getProperty(it) }
