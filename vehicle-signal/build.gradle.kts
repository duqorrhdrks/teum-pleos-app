plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.teum.vehiclesignal"
    // AAOS 표준 Car API(CarPropertyManager). 런타임에는 차량 시스템이 제공한다.
    useLibrary("android.car")
}

dependencies {
    api(project(":core-dli"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pleos.vehicle) {
        // Vehicle SDK POM은 hilt-android를 선언하지만 SDK 클래스는 쓰지 않는다(Roadio 사례와 동일 판단).
        // 앱 모듈이 Hilt를 직접 쓰므로 여기서는 제외해 버전 충돌을 피한다.
        exclude(group = "com.google.dagger")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 시나리오 JSON 정본(scenarios/)을 단위 테스트 리소스로도 쓴다.
android.sourceSets["test"].resources.srcDir(rootProject.file("scenarios"))
