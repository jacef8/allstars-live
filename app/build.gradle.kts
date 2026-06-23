plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.libertyclerk.allstarslive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.libertyclerk.allstarslive"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-m0"
        vectorDrawables { useSupportLibrary = true }

        // M1 SRT ingest (libsrt + NDK). arm64 only for the spike — add
        // "armeabi-v7a" only if 32-bit tablets must be supported.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // libsrt vendored under cpp/third_party/srt (arm64, encryption off).
                arguments += "-DUSE_LIBSRT=ON"
            }
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
    }
    composeOptions {
        // Must match Kotlin 1.9.10.
        kotlinCompilerExtensionVersion = "1.5.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Native SRT ingest. Requires "NDK (Side by side)" + "CMake" from the SDK
    // Manager; install these versions (or update the pins) if Gradle complains.
    ndkVersion = "25.1.8937393"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Bottom-nav tab icons (Scoreboard / Videocam / People / Settings).
    implementation("androidx.compose.material:material-icons-extended")
    // (libVLC removed — its Android build has no SRT module. SRT ingest is the
    //  native libsrt route under app/src/main/cpp.)

    // M3: RTMP push to YouTube Live (RootEncoder's RTMP client, fed by our encoder).
    // The "-1.8.22" build targets Kotlin 1.8.22 (our 1.9.10 compiler reads it) and
    // compileSdk 34 — plain 2.5.9 needs Kotlin 2.1, and 2.6+/2.7+ need compileSdk 35/36.
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.4-1.8.22")

    // M3 (full): Google Sign-In -> OAuth token for the YouTube Live API (auto broadcast + key).
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// M4: bundle the web scorer into the app so the Game tab can show it offline.
// Single source of truth stays reference/web-scoring/; this copies it into assets
// at build time (the dest is git-ignored, never hand-edited).
val syncScorerAssets by tasks.registering(Copy::class) {
    from(rootProject.file("reference/web-scoring")) {
        include("scoring-controller.html", "lib/**", "manifest.webmanifest", "sw.js", "icons/**", "firebase-config.js", "auth.js")
    }
    into(layout.projectDirectory.dir("src/main/assets/scorer"))
}
tasks.named("preBuild") { dependsOn(syncScorerAssets) }
