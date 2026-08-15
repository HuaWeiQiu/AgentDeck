plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.agentdeck.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agentdeck.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.2.0-beta.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Secure channel defaults (ADR-0012); lab flavor overrides.
        buildConfigField("boolean", "HOST_LAB", "false")
        buildConfigField("int", "HOST_MAX_LEVEL", "1")
        buildConfigField("boolean", "EXTENSION_LAB", "false")
        buildConfigField("int", "EXTENSION_MAX_LEVEL", "2")
    }

    flavorDimensions += "channel"
    productFlavors {
        create("secure") {
            dimension = "channel"
            isDefault = true
            buildConfigField("boolean", "HOST_LAB", "false")
            buildConfigField("int", "HOST_MAX_LEVEL", "1")
            buildConfigField("boolean", "EXTENSION_LAB", "false")
            buildConfigField("int", "EXTENSION_MAX_LEVEL", "2")
        }
        create("lab") {
            dimension = "channel"
            applicationIdSuffix = ".lab"
            versionNameSuffix = "-lab"
            buildConfigField("boolean", "HOST_LAB", "true")
            buildConfigField("int", "HOST_MAX_LEVEL", "4")
            buildConfigField("boolean", "EXTENSION_LAB", "true")
            buildConfigField("int", "EXTENSION_MAX_LEVEL", "4")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("beta") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.33.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Offline on-device STT for devices without usable system Google/Vivo speech services.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
