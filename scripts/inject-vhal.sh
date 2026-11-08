#!/usr/bin/env bash
# Pleos Connect Emulator에 차량 값을 넣어 틈의 DLI 반영을 확인한다.
#
# 사용: scripts/inject-vhal.sh speed 100        # 차속 km/h
#       scripts/inject-vhal.sh gear D|P|R|N
#       scripts/inject-vhal.sh steer 90         # 운전대 각도(°, -470~500)
#       scripts/inject-vhal.sh brake 60         # 브레이크 페달(%, 0~100)
#       scripts/inject-vhal.sh turn left|right|off
#       scripts/inject-vhal.sh wiper off|low|high|auto
#       scripts/inject-vhal.sh demo             # 정속 → 차선 변경 → 비 → 감속·정차 (약 40초)
#
# 방식(에뮬레이터에서 확인, 2026-11-06):
# - Pleos Connect Emulator v2.1.0은 user 빌드라 AOSP의 `adb shell cmd car_service inject-vhal-event`가
#   "requires non-user build"로 거부된다.
# - 대신 공식 Virtual Console(문서: use-vehicle-emulator)의 HTTP 엔드포인트를 쓴다. 엔드포인트와 값 범위는
#   콘솔이 스스로 제공하는 목록(GET /vehicle/catalog)에서 확인했다.
# - 콘솔은 브라우저 세션 하트비트(POST /browser/session, 3초 만료)가 있어야 값을 받는다. 이 스크립트가 대신 보낸다.
set -euo pipefail
ADB=${ADB:-adb}
PORT=${PORT:-8088}
B="http://127.0.0.1:$PORT"
"$ADB" forward tcp:$PORT tcp:$PORT >/dev/null

heartbeat() { curl -s -m 3 -X POST "$B/browser/session?client=teum-script" -o /dev/null || true; }
# 부팅 직후에는 콘솔 브리지가 아직 준비되지 않아 잠깐 거부될 수 있어 몇 번 다시 시도한다.
put() {
  local i
  for i in 1 2 3 4 5; do
    heartbeat
    curl -s -m 3 -f -X PUT "$B$1" -o /dev/null && return 0
    sleep 1
  done
  echo "실패: $1 (에뮬레이터가 켜져 있는지, 콘솔 http://127.0.0.1:$PORT/ 이 열리는지 확인)" >&2; return 1
}

# controls는 speed·accelerator·brake를 함께 보낸다(콘솔과 같은 방식). 현재 값을 유지하려고 상태를 읽는다.
SPEED=0; BRAKE=0
controls() { put "/vehicle/controls?speed=$SPEED&accelerator=0&brake=$BRAKE${1:+&steering=$1}"; }

case "${1:-}" in
  speed) SPEED=${2:?km/h}; controls ;;
  brake) BRAKE=${2:?%}; controls ;;
  steer) put "/vehicle/controls?steering=${2:?deg}" ;;
  gear)
    case "${2:?D|P|R|N}" in P) g=Park ;; R) g=Reverse ;; N) g=Neutral ;; D) g=Drive ;; *) exit 2 ;; esac
    put "/vehicle/gear?gear=$g" ;;
  turn)
    case "${2:?left|right|off}" in left) m=BlinkLeft ;; right) m=BlinkRight ;; off) m=Off ;; *) exit 2 ;; esac
    put "/vehicle/turn-signal?mode=$m" ;;
  wiper)
    case "${2:?off|low|high|auto}" in off) w=Off ;; low) w=Low ;; high) w=High ;; auto) w=Auto ;; *) exit 2 ;; esac
    put "/vehicle/body?front_wiper=$w" ;;
  demo)
    hold() { local n=$1; shift; for _ in $(seq 1 "$n"); do "$@"; sleep 0.5; done; }
    echo "[1/4] D단, 100km/h 정속 (→ 2초 뒤 틈)"
    put "/vehicle/gear?gear=Drive"; put "/vehicle/turn-signal?mode=Off"; put "/vehicle/body?front_wiper=Off"
    SPEED=100; hold 12 controls 0
    echo "[2/4] 차선 변경: 방향지시등 + 운전대 왕복 ±25° (→ 주의)"
    put "/vehicle/turn-signal?mode=BlinkLeft"
    for a in 0 12 25 12 0 -12 -25 -12 0; do controls $a; sleep 0.25; done
    put "/vehicle/turn-signal?mode=Off"; hold 8 controls 0
    echo "[3/4] 비: 와이퍼 High (날씨 항목 +6)"
    put "/vehicle/body?front_wiper=High"; hold 8 controls 0
    echo "[4/4] 브레이크로 감속 → D단 정차 (→ 정차 즉시 틈)"
    # 5km/h씩 0.25초마다(약 5.6m/s², 급제동 수준)로 고르게 줄여야 감속도 항목이 끊기지 않고 잡힌다
    BRAKE=60; for v in $(seq 95 -5 0); do SPEED=$v; controls 0; sleep 0.25; done
    hold 10 controls 0
    BRAKE=0; put "/vehicle/body?front_wiper=Off"; put "/vehicle/gear?gear=Park"; controls 0
    echo "끝"
    ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
