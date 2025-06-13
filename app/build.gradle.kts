plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gpstracker.msldapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gpstracker.msldapp.speedlimit"  // ⚠️ Different package
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "2.0-SpeedLimit"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
    }

    // JNI libraries
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("libs")
        }
    }
}

dependencies {
    // Local libraries
    implementation(files("libs/usbSerialForAndroid-debug.aar"))
    implementation(files("libs/classes.jar"))

    // Core Android libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    // Compose BOM - manages all compose versions
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Material Icons
    implementation("androidx.compose.material:material-icons-extended:1.5.4")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Location Services - use only one version
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // UI
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Map libraries - updated versions to avoid conflicts
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("org.mapsforge:mapsforge-map-android:0.19.0")
    implementation("org.mapsforge:mapsforge-map:0.19.0")
    implementation("org.mapsforge:mapsforge-map-reader:0.19.0")
    implementation ("org.mapsforge:mapsforge-core:0.20.0")
    implementation ("org.mapsforge:mapsforge-map-reader:0.20.0")


    implementation ("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.firebase.database.ktx)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.foundation.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")



    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    // Compose
    implementation ("androidx.compose.ui:ui")
    implementation ("androidx.compose.material3:material3")
    implementation ("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
}

// Force resolution strategy for conflicting dependencies
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10")
        force("androidx.compose.material:material-icons-core:1.5.4")
    }
}