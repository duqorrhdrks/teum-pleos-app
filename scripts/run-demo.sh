#!/usr/bin/env bash
# 에뮬레이터 시연: 빌드 → 설치 → 권한 → 고속도로 시나리오 재생.
#   scripts/run-demo.sh          # 틈 ON
#   scripts/run-demo.sh --off    # 틈 OFF(기준 비교) 같은 시나리오
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=${ADB:-adb}
OFF=false; [[ "${1:-}" == "--off" ]] && OFF=true

./gradlew -q :app:assembleDebug :sample-client:assembleDebug
"$ADB" install -r -g app/build/outputs/apk/debug/app-debug.apk
"$ADB" install -r -g sample-client/build/outputs/apk/debug/sample-client-debug.apk
"$ADB" shell pm grant app.teum android.car.permission.CAR_SPEED 2>/dev/null || true
"$ADB" shell am start -n app.teum/.ui.DrivingActivity --ez teum_off $OFF --ez play_scenario true
echo "시나리오 재생 중(100초). 로그: $ADB logcat -s LoadEngine VoiceGate GleoTtsEngine"
