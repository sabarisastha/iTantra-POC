plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace  = "com.itantra"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.itantra"
        minSdk        = 23
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
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

    packagingOptions {
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts.add("**/libonnxruntime.so")
        jniLibs.pickFirsts.add("**/libsherpa-onnx-core.so")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

afterEvaluate {
    tasks.register<JavaExec>("runBinaryTest") {
        dependsOn("compileDebugUnitTestKotlin")
        mainClass.set("org.junit.runner.JUnitCore")
        val kotlinTestClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
        val appClasses = layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
        val kotlinAppClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug")
        classpath = files(
            kotlinTestClasses,
            appClasses,
            kotlinAppClasses,
            configurations.getByName("debugUnitTestRuntimeClasspath")
        )
        args("com.itantra.transport.binary.BinaryProtocolTest")
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(libs.onnxruntime.android)
    // ── Jetpack Compose BOM ──────────────────────────────────────────────────
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // ── AndroidX & Lifecycle ─────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // ── Coroutines ───────────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // ── kotlinx.serialization — Payload marshalling over TCP socket ──────────
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation("junit:junit:4.13.2")
}


