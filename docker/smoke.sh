#!/usr/bin/env bash
# Smoke test against the running stack (docker compose up --build -d).
# Prints one line per check and exits 1 if any expectation fails.
set -u

BASE=${BASE:-http://localhost:8080}
RUN=${RUN:-$(date +%s%N)}
PASS=0
FAIL=0

call() {
  local method="$1" path="$2" expected="$3" data="${4:-}" key="${5:-}" ctype="${6:-application/json}"
  local args=(-s -X "$method" -w $'\n%{http_code}' "$BASE$path")
  [ -n "$data" ] && args+=(-H "Content-Type: $ctype" -d "$data")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
  local out code body
  out=$(curl "${args[@]}")
  code=${out##*$'\n'}
  body=${out%$'\n'*}
  local tag="PASS"
  if [ "$code" != "$expected" ]; then tag="FAIL"; FAIL=$((FAIL + 1)); else PASS=$((PASS + 1)); fi
  printf '%-4s %-6s %-38s got=%s want=%s\n' "$tag" "$method" "$path" "$code" "$expected"
  printf '     %s\n' "$(echo "$body" | tr -d '\n' | cut -c1-200)"
}

new_event() {
  curl -s -X POST -H 'Content-Type: application/json' \
    -d "{\"name\":\"$1\",\"capacity\":$2}" "$BASE/events" |
    sed -n 's/.*"id":\([0-9]*\).*/\1/p'
}

echo "== 1. health =="
call GET /actuator/health 200

echo
echo "== 2. POST /events =="
EVENT_ID=$(new_event "Smoke Rock" 5)
echo "     -> event id: $EVENT_ID"
call POST /events 201 '{"name":"Smoke Rock","capacity":5}'
call GET "/events/$EVENT_ID" 200

echo
echo "== 3. POST /events/:id/reservations =="
call POST "/events/$EVENT_ID/reservations" 201 '{"quantity":2}' "smoke-$RUN-1"
call POST "/events/$EVENT_ID/reservations" 200 '{"quantity":2}' "smoke-$RUN-1"
call POST "/events/$EVENT_ID/reservations" 400 '{"quantity":2}'
call POST "/events/$EVENT_ID/reservations" 422 '{"quantity":0}' "smoke-$RUN-2"
call POST "/events/$EVENT_ID/reservations" 422 '{"quantity":11}' "smoke-$RUN-3"
call POST "/events/$EVENT_ID/reservations" 415 '{"quantity":2}' "smoke-$RUN-4" "text/plain"
call GET "/events/$EVENT_ID/reservations" 405
call GET "/events/$EVENT_ID" 200

echo
echo "== 4. GET /reservations/:id =="
RES_ID=$(curl -s -H "Idempotency-Key: smoke-$RUN-1" -H 'Content-Type: application/json' \
  -d '{"quantity":2}' "$BASE/events/$EVENT_ID/reservations" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
echo "     -> reservation id: $RES_ID"
call GET "/reservations/$RES_ID" 200
call GET /reservations/abc 400
call GET /reservations/999999 404

echo
echo "== 5. capacity esgotada =="
FULL_ID=$(new_event "Sold Out" 3)
call POST "/events/$FULL_ID/reservations" 201 '{"quantity":1}' "full-$RUN-1"
call POST "/events/$FULL_ID/reservations" 201 '{"quantity":1}' "full-$RUN-2"
call POST "/events/$FULL_ID/reservations" 201 '{"quantity":1}' "full-$RUN-3"
call POST "/events/$FULL_ID/reservations" 409 '{"quantity":1}' "full-$RUN-4"
call GET "/events/$FULL_ID" 200

echo
echo "== 6. DELETE /reservations/:id (devolve capacity) =="
call DELETE "/reservations/$RES_ID" 200
call DELETE "/reservations/$RES_ID" 200
call GET "/events/$EVENT_ID" 200

echo
echo "== 7. erros de rota/método/tipo =="
call GET /events/999999 404
call DELETE "/events/$EVENT_ID" 405
call POST /nope 404 '{"name":"x","capacity":5}'
call POST /events 400 '{"name":"No capacity"}'

echo
echo "==================================================="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
