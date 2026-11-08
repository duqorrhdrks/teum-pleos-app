plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.teum"
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.teum"
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 공모전 제출용 서명 설정은 signing.properties로 분리(저장소에 넣지 않음)
        }
    }
    buildFeatures { compose = true }
    // 시나리오 정본(scenarios/)을 앱 assets로 그대로 넣는다(시뮬레이터와 같은 파일).
    sourceSets["main"].assets.srcDir(rootProject.file("scenarios"))
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core-dli"))
    implementation(project(":vehicle-signal"))
    implementation(project(":navi-signal"))
    implementation(project(":voice-gate"))
    implementation(project(":notification-queue"))
    implementation(project(":passenger-cue"))
    implementation(project(":load-aware-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
