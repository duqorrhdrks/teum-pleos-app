#!/usr/bin/env bash
# Pleos Connect Emulator(TEUM_Pleos AVD) 실행 → 부팅 대기 → 틈 앱 설치·실행.
# 문서 요구: Boot는 Cold(퀵부트면 VHAL 사용 불가) → -no-snapshot-load 로 매번 콜드 부팅.
set -euo pipefail
SDK=${ANDROID_SDK_ROOT:-$HOME/.local/android-sdk}
export ANDROID_SDK_ROOT=$SDK ANDROID_HOME=$SDK
ADB=$SDK/platform-tools/adb
cd "$(dirname "$0")/.."

# GPU: 기본은 호스트 GPU. 소프트웨어 렌더러(swiftshader_indirect·lavapipe)는 주행 화면이 그려질 때
# 에뮬레이터 RenderThread가 SIGSEGV로 죽었다(2026-11-07, gdb로 확인). 호스트 GPU가 없는 PC에서만
# TEUM_GPU=swiftshader_indirect 로 바꾼다.
GPU=${TEUM_GPU:-host}
EMU_CMD=("$SDK/emulator/emulator" -avd TEUM_Pleos -no-snapshot-load -no-boot-anim -gpu "$GPU")
if ! "$ADB" devices | grep -q emulator-; then
  # 이 스크립트를 띄운 터미널을 닫아도 에뮬레이터가 살아 있도록 systemd 사용자 서비스로 분리해 실행한다.
  if command -v systemd-run >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
    systemctl --user reset-failed teum-emulator >/dev/null 2>&1 || true
    systemd-run --user --quiet --collect --unit=teum-emulator \
      --setenv=ANDROID_SDK_ROOT="$SDK" --setenv=ANDROID_HOME="$SDK" \
      bash -c "exec \"\$@\" > /tmp/teum-emulator.log 2>&1" _ "${EMU_CMD[@]}"
  else
    setsid nohup "${EMU_CMD[@]}" > /tmp/teum-emulator.log 2>&1 < /dev/null &
  fi
  echo "에뮬레이터 시작(GPU: $GPU, 로그: /tmp/teum-emulator.log). 부팅 대기 중…"
fi
"$ADB" wait-for-device
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 3; done
echo "부팅 완료"

# 앱 설치(빌드 결과가 있으면 그대로, 없으면 빌드)
[[ -f app/build/outputs/apk/debug/app-debug.apk ]] || ./gradlew -q :app:assembleDebug :sample-client:assembleDebug
"$ADB" install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r -g sample-client/build/outputs/apk/debug/sample-client-debug.apk >/dev/null
"$ADB" shell pm grant app.teum android.car.permission.CAR_SPEED 2>/dev/null || true
"$ADB" shell am start -n app.teum/.ui.DrivingActivity >/dev/null
# Virtual Console(차량 값 직접 조작): 브라우저에서 http://127.0.0.1:8088/
"$ADB" forward tcp:8088 tcp:8088 >/dev/null || true
# 콘솔이 차량 값을 받을 준비가 될 때까지 기다린다(부팅 직후 몇 초는 값을 거부한다)
for _ in $(seq 1 30); do
  curl -s -m 3 -X POST "http://127.0.0.1:8088/browser/session?client=teum-start" -o /dev/null || true
  curl -s -m 3 http://127.0.0.1:8088/vehicle/state 2>/dev/null | grep -q '"emulator_active":true' && break
  sleep 2
done
echo "틈 실행됨. 차량 값 조작: http://127.0.0.1:8088/  · 시나리오: 앱의 '시나리오 재생' 버튼"
# 바탕화면 아이콘에서 실행할 때: 차량 값 조작 콘솔을 브라우저로 연다
if [[ "${OPEN_CONSOLE:-0}" == "1" ]]; then
  "$ADB" shell am start -n app.teum/.ui.DrivingActivity --ez use_vehicle true >/dev/null
  xdg-open "http://127.0.0.1:8088/" >/dev/null 2>&1 || true
fi
