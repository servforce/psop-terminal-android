plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rokid.cxrmsamples"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rokid.cxrmsamples"
        minSdk = 31
        targetSdk = 36
        versionCode = 10004
        versionName = "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    // 不压缩 onnx 模型文件（避免 assets 中大文件被压缩导致读取失败）
    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    // Sherpa-ONNX jniLibs 目录
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation ("com.squareup.okhttp3:okhttp:4.9.3")
    implementation ("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation ("com.squareup.okio:okio:2.8.0")
    implementation ("com.google.code.gson:gson:2.10.1")
    implementation ("com.squareup.okhttp3:logging-interceptor:4.9.1")
    implementation ("io.coil-kt:coil-compose:2.6.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.36.0-b02")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    implementation("com.rokid.cxr:client-m:1.0.9") {
        exclude(group = "com.rokid.cxr", module = "client-m-sources")
    }

    // 统一使用这一份 Sherpa-ONNX：同时提供离线 ASR 与手机端离线 TTS。
    // 不使用 fileTree，避免开发机遗留的旧版本 AAR 被一并引入而产生重复类。
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))
}
