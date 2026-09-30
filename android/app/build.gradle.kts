import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Secrets live outside the checkout. Do not pass passwords as command-line arguments.
val signingPropertiesPath = providers.environmentVariable("SK_SIGNING_PROPERTIES").orNull
val signingPropertiesFile = signingPropertiesPath?.let { file(it) }
val releaseSigning = signingPropertiesFile?.let { source ->
    require(!gradle.startParameter.isConfigurationCacheRequested) {
        "Use --no-configuration-cache with signing credentials so they are not cached on disk."
    }
    require(source.isFile && !source.canonicalFile.toPath().startsWith(rootProject.projectDir.parentFile.canonicalFile.toPath())) {
        "SK_SIGNING_PROPERTIES must point to an existing file outside the repository."
    }
    Properties().apply { source.inputStream().use { load(it) } }.also { properties ->
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword", "storeType").forEach { key ->
            require(!properties.getProperty(key).isNullOrBlank()) { "Signing configuration is missing $key." }
        }
    }
}

android {
    namespace = "com.example.soul_knight_save_editor"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.soul_knight_save_editor"
        minSdk = 24
        targetSdk = 36
        versionCode = 16
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) create("official") {
            val configuredFile = File(releaseSigning.getProperty("storeFile"))
            val keyFile = if (configuredFile.isAbsolute) configuredFile
                else File(signingPropertiesFile!!.parentFile, releaseSigning.getProperty("storeFile"))
            require(keyFile.isFile && !keyFile.canonicalFile.toPath().startsWith(rootProject.projectDir.parentFile.canonicalFile.toPath())) {
                "Release keystore must exist outside the repository."
            }
            storeFile = keyFile
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
            storeType = releaseSigning.getProperty("storeType")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("official")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions.unitTests.all {
        it.systemProperty("unlock.baseline", providers.gradleProperty("unlockBaseline").orNull ?: "")
        it.systemProperty("item.baseline", providers.gradleProperty("itemBaseline").orNull ?: "")
        it.systemProperty("statistic.baseline", providers.gradleProperty("statisticBaseline").orNull ?: "")
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(releaseSigning != null) { "Set SK_SIGNING_PROPERTIES to the external signing configuration before building Release." }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
