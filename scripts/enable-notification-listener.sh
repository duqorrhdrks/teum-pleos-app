#!/usr/bin/env bash
# 틈에 '다른 앱 알림 접근'을 허용한다(개발·시연용. 실사용에서는 설정 화면에서 사용자가 허용).
# Pleos 에뮬레이터는 앱이 운전자 사용자(보통 user 10)에서 돌므로 현재 사용자에게 허용해야 한다.
# (user를 빼면 user 0에만 허용돼 아무 효과가 없다. 2026-11-07 확인)
set -euo pipefail
ADB=${ADB:-adb}
USER_ID=$("$ADB" shell am get-current-user | tr -d '\r')
"$ADB" shell cmd notification allow_listener app.teum/app.teum.notificationqueue.TeumNotificationListener "$USER_ID"
if "$ADB" shell settings --user "$USER_ID" get secure enabled_notification_listeners | grep -q app.teum; then
  echo "알림 접근 허용됨(user $USER_ID). 확인: '틈 연동 예제' 앱의 '시스템 알림 보내기'"
else
  echo "허용 실패(user $USER_ID)" >&2; exit 1
fi
