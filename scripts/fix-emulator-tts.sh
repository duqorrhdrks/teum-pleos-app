#!/usr/bin/env bash
# Pleos 에뮬레이터의 기기 내 음성(Gleo TTS)이 소리 없이 ON_DEVICE_ENGINE_ERROR를 내면 실행한다.
#
# 증상(2026-11-07 확인): logcat에 "Failed to load MetaSpace byte pair encoder" 또는
#   "TextToSpeechConfig.dat ... ErrorNotExists". 틈 화면에는 '음성 엔진 없음 · 자막으로 표시'.
# 원인: 음성 서비스(ai.pleos.playground.caas)에 깨진 모델이 남아 있고, 모델 제공 앱이 주는 1.17.1 모델을
#   "version downgrade detected. skip model update"로 거부한다.
# 조치: 음성 서비스의 에뮬레이터 내부 데이터만 지우면 다음 발화 요청 때 모델 제공 앱에서 1.17.1을 다시 받아 푼다.
#   (에뮬레이터 안의 데이터만 바뀐다. 되돌리려면 AVD wipe-data)
set -euo pipefail
ADB=${ADB:-adb}
USER_ID=$("$ADB" shell am get-current-user | tr -d '\r')
for u in 0 "$USER_ID"; do "$ADB" shell pm clear --user "$u" ai.pleos.playground.caas >/dev/null; done
"$ADB" shell am force-stop --user "$USER_ID" app.teum
echo "음성 서비스 데이터를 지웠어요. 틈을 다시 열고 '시나리오 재생'을 누르면 첫 문장 때 모델을 다시 받습니다(10~20초)."
echo "확인: adb logcat | grep -E 'extractZipFile|speaker=haeun'"
