import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// vivo X300 Pro (Dimensity 9500) is arm64 only: keep just that ABI to slim the APK.
// CI's emulator test overrides this with -PabiFilters=x86_64.
val abiList = ((project.findProperty("abiFilters") as String?) ?: "arm64-v8a").split(",")
// OpenCV with only the modules the app uses, when ci/fetch-opencv.sh put it in jniLibs.
val minimalOpenCv = abiList.all { file("src/main/jniLibs/$it/libopencv_java_min.so").exists() }

android {
    namespace = "com.elad.faceblur"
    compileSdk = 36
    // Use the NDK the machine has (CI runners preinstall one), if it names its version.
    (System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_LATEST_HOME"))
        ?.let { File(it).name }
        ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
        ?.let { ndkVersion = it }

    // The beta build (from develop, -Plabs=true): "ONIT Beta", its own app beside the real one,
    // with a BETA mark on its icon.
    val labs = (findProperty("labs") as String?) == "true"
    defaultConfig {
        applicationId = if (labs) "com.elad.faceblur.labs" else "com.elad.faceblur"
        manifestPlaceholders["appLabel"] = if (labs) "ONIT Beta" else "@string/app_name"
        manifestPlaceholders["appIcon"] = if (labs) "@mipmap/ic_launcher_beta" else "@mipmap/ic_launcher"
        minSdk = 29
        targetSdk = 36
        versionCode = buildNumber
        // CI passes the version from the git tags (ci/version.sh); local builds are 0.0.0-dev.
        versionName = (findProperty("versionName") as String?) ?: "0.0.0-dev"
        ndk { abiFilters += abiList }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The app speaks Hebrew and English: other languages' library strings are left out.
        resourceConfigurations += listOf("en", "iw", "he")
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    // Whisper (speech to text) for subtitles; sources fetched by ci/fetch-whisper.sh.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("faceblur.jks")
            storePassword = "faceblur"
            keyAlias = "faceblur"
            keyPassword = "faceblur"
        }
    }

    buildTypes {
        release {
            // R8 drops unused library code (most of the APK's Java code); names are kept.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs {
            useLegacyPackaging = false
            // The minimal OpenCV replaces the package's full library and the shared C++ runtime
            // only that one needed (the minimal one has it built in).
            if (minimalOpenCv) excludes += listOf("**/libopencv_java4.so", "**/libc++_shared.so")
        }
    }

    androidResources {
        // Read straight from the APK (models by OpenCV, sounds by MediaExtractor).
        noCompress += listOf("onnx", "ogg", "tflite")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val media3 = "1.6.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // Face detection (YuNet via OpenCV DNN) + optical-flow tracking, fully on-device.
    implementation("org.opencv:opencv:4.10.0")
    // Face detection on the GPU (LiteRT, formerly TensorFlow Lite, with its GPU delegate).
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.0")

    // Subtitle translation Hebrew <-> English, on the device.
    implementation("com.google.mlkit:translate:17.0.3")

    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
