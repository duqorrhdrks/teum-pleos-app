# Pleos SDK: 바인더·직렬화 모델을 리플렉션/serialization으로 쓰므로 유지한다.
-keep class ai.pleos.playground.** { *; }
-dontwarn ai.pleos.playground.**
# android.car는 런타임에 시스템이 제공
-dontwarn android.car.**
# kotlinx.serialization (시나리오·브리핑 모델)
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *** serializer(...); }
