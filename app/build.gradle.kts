import java.util.zip.ZipFile
import java.util.Properties

val buildGitSha = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
val localBuildProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val verifiedTargetPackage = (
    providers.environmentVariable("RALLY_HELPER_TARGET_PACKAGE").orNull
        ?: localBuildProperties.getProperty("verifiedTargetPackage")
        ?: ""
    ).replace("\\", "\\\\").replace("\"", "\\\"")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.rallyhelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rallyhelper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-radar"
        buildConfigField("String", "GIT_SHA", "\"${buildGitSha.get()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "target"
    productFlavors {
        create("production") {
            dimension = "target"
            buildConfigField("String", "VERIFIED_TARGET_PACKAGE", "\"$verifiedTargetPackage\"")
            buildConfigField("boolean", "ONE_TAP_INTEGRATION", "false")
        }
        create("oneTapIntegration") {
            dimension = "target"
            applicationIdSuffix = ".integration"
            versionNameSuffix = "-integration"
            buildConfigField("String", "VERIFIED_TARGET_PACKAGE", "\"com.rallyhelper.testtarget\"")
            buildConfigField("boolean", "ONE_TAP_INTEGRATION", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.register("verifyNoRawCalibrationAssets") {
    group = "verification"
    description = "Fails if an APK contains raw calibration screenshots."
    dependsOn("assembleProductionDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/production/debug/app-production-debug.apk").get().asFile
        require(apk.isFile) { "Debug APK was not produced" }
        val forbidden = ZipFile(apk).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { name ->
                name.startsWith("assets/") && name.lowercase().matches(Regex(".*\\.(jpg|jpeg|png)$"))
            }.toList()
        }
        require(forbidden.isEmpty()) { "Raw image assets found in APK: $forbidden" }
        println("PASS: APK contains no raw calibration screenshots")
    }
}

tasks.register("verifyProductionIsolation") {
    group = "verification"
    description = "Proves the production APK excludes deterministic integration fixtures."
    dependsOn("assembleProductionDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/production/debug/app-production-debug.apk").get().asFile
        require(apk.isFile) { "Production debug APK was not produced" }
        val bytes = apk.readBytes().toString(Charsets.ISO_8859_1)
        val forbidden = listOf("com.rallyhelper.testtarget", "TEST_EVENT", "FakeFrameAnalysis", "receivedJoinTapCount")
            .filter(bytes::contains)
        require(forbidden.isEmpty()) { "Integration markers found in production APK: $forbidden" }
        println("PASS: production APK excludes integration fixtures")
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", file("$projectDir/schemas").absolutePath)
    }
}

dependencies {
    implementation(project(":vision-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui-android:1.7.6")
    implementation("androidx.compose.foundation:foundation-android:1.7.6")
    implementation("androidx.compose.material3:material3-android:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
