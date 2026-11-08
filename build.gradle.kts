import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

/**
 * 모든 안드로이드 모듈 공통 설정.
 * - minSdk 28: Pleos Connect 문서의 요구사항("minSdk API 28 이상", Getting Started / 에뮬레이터 설치 문서).
 * - compileSdk/targetSdk 35: Pleos 샘플 앱 기준선(compileSdk/targetSdk 35).
 * - 단위 테스트는 JUnit5 플랫폼으로 돌린다.
 */
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            compileSdk = 35
            defaultConfig { minSdk = 28 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions { unitTests.all { it.useJUnitPlatform() } }
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.gradle.internal.dsl.BaseAppModuleExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 28
                targetSdk = 35
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions { unitTests.all { it.useJUnitPlatform() } }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
}
