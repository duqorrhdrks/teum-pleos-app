plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 순수 Kotlin 모듈: 안드로이드 의존성 없음. 코루틴 core(JVM)만 사용한다.
// 설치된 JDK 21로 컴파일하되 바이트코드는 17(안드로이드 모듈과 동일)로 맞춘다.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
