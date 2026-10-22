plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.example.qrgarage"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.qrgarage"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.0"

        testInstrumentationRunner = "com.example.qrgarage.HiltTestRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    // DB-1: explicit schema export; JSON files are committed and used by migration tests.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    implementation(libs.play.services.auth)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
