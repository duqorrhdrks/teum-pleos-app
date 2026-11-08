plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 부하 인지 API 연동 예제: 틈의 단계를 구독해 자기 알림을 미루거나 틈에 전달을 맡긴다.
android {
    namespace = "app.teum.sample"
    defaultConfig {
        applicationId = "app.teum.sample"
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":load-aware-api"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
