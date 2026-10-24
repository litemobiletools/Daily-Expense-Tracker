plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.litemobiletools.dailyexpensetracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.litemobiletools.dailyexpensetracker"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Export Room schemas so future migrations can be written and tested against them.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
                arguments["room.incremental"] = "true"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        // BuildConfig.DEBUG gates the Phase 10 release guard: a release build that still carries
        // Google's official test ad IDs disables ad requests entirely instead of shipping them.
        buildConfig = true
    }
    sourceSets {
        // Exported Room schemas are used by migration tests.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.room.runtime)
    // Google Identity authorization (AuthorizationClient) for the drive.appdata scope.
    implementation(libs.play.services.auth)
    annotationProcessor(libs.room.compiler)
    // Phase 8: reliable background scheduling for automatic backups.
    implementation(libs.work.runtime)
    // Phase 10: official Google Mobile Ads SDK for the single restrained banner, plus the UMP SDK
    // for the required consent flow. AdMob is the only ad network used in this app.
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    testImplementation(libs.junit)
    // The Android org.json classes are stubs in JVM unit tests; this test-only artifact lets the
    // backup-file tests run on the JVM. It is not part of the APK.
    testImplementation(libs.json)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.room.testing)
    // Phase 8: WorkManager test facilities for scheduling tests on a device/emulator.
    androidTestImplementation(libs.work.testing)
}