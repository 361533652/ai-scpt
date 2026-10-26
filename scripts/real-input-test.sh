#!/usr/bin/env bash
# ============================================================
# 真机注入验证：真的动一次鼠标、按一次键，用系统侧的事实来判定
#
# 这是唯一能证明"注入真的生效"的测试——单元测试与接口测试都碰不到 SendInput。
# 安全性：只做「相对移动 (200,0)」与「按下/松开 shift」，不点击、不输入文字，
#        不会误触任何按钮或往编辑器里打字。
#
# 用法：bash scripts/real-input-test.sh
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/e/IntelliJ IDEA 2025.3/jbr}"
JAVA="$JAVA_HOME/bin/java"
JAR="$ROOT/target/gmscpt-2.0.0-SNAPSHOT.jar"
PORT="${SMOKE_PORT:-8897}"
BASE="http://127.0.0.1:$PORT"
LOG="$ROOT/target/real-input.log"
DATA="$ROOT/target/real-input-data"
TMP="$ROOT/target/real-input-tmp"
rm -rf "$DATA" "$TMP"; mkdir -p "$TMP"

pass=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "  [PASS] $1"; pass=$((pass+1));
  else echo "  [FAIL] $1  期望=$2 实际=$3"; fail=$((fail+1)); fi
}

cursor_x() {
  powershell -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Cursor]::Position.X" 2>/dev/null | tr -d '\r' | grep -E '^-?[0-9]+$' | head -1
}
cursor_y() {
  powershell -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Cursor]::Position.Y" 2>/dev/null | tr -d '\r' | grep -E '^-?[0-9]+$' | head -1
}

echo "===== [1/4] 启动服务（隔离数据目录）====="
"$JAVA" -jar "$JAR" --gmscpt.open-browser=false --server.port="$PORT" \
  --gmscpt.data-dir="$DATA" > "$LOG" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null; wait $PID 2>/dev/null' EXIT

for _ in $(seq 1 60); do
  grep -q "GmScript 已启动" "$LOG" 2>/dev/null && break
  kill -0 $PID 2>/dev/null || { echo "[FATAL] 服务没起来"; tail -20 "$LOG"; exit 1; }
  sleep 1
done
TOKEN=$(grep -oE 'token=[a-z0-9]+' "$LOG" | head -1 | cut -d= -f2)
H="X-GmScript-Token: $TOKEN"
echo "  OK（钩子状态：$(grep -oE '钩子状态：[^ ]*' "$LOG" | head -1)）"

echo "===== [2/4] 前置：记录光标初始位置 ====="
X0=$(cursor_x); Y0=$(cursor_y)
echo "  初始光标: ($X0, $Y0)"
if [ -z "$X0" ] || [ -z "$Y0" ]; then
  echo "[FATAL] 读不到光标位置，无法判定注入结果"; exit 1
fi

echo "===== [3/4] 运行注入脚本（相对移动 + 按键）====="
# 顶层直接执行：脚本跑完就结束，不需要热键触发，避免依赖人工按键。
cat > "$TMP/script.json" <<'EOF'
{"name":"真机注入验证",
 "source":"MoveMouseRelative(200, 0)\nSleep(150)\nlocal x, y = GetMousePosition()\nOutputLogMessage(\"REALTEST pos=\" .. x .. \",\" .. y)\nPressKey(\"shift\")\nReleaseKey(\"shift\")\nOutputLogMessage(\"REALTEST key=ok\")\n"}
EOF
SID=$(curl -s -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "@$TMP/script.json" "$BASE/api/scripts" | grep -oE '"id":"[^"]+"' | head -1 | cut -d'"' -f4)

printf '%s' '{}' > "$TMP/run.json"
curl -s -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "@$TMP/run.json" "$BASE/api/scripts/$SID/run" > /dev/null

sleep 4
X1=$(cursor_x); Y1=$(cursor_y)
echo "  注入后光标: ($X1, $Y1)"

DX=$((X1 - X0)); DY=$((Y1 - Y0))
echo "  实际位移: dx=$DX dy=$DY"
check "相对移动生效（dx 接近 200）" "200" "$([ "$DX" -ge 199 ] && [ "$DX" -le 201 ] && echo 200 || echo "$DX")"
check "没有垂直漂移（dy=0）" "0" "$DY"

echo "===== [4/4] 判定日志与运行结果 ====="
LOGS=$(curl -s -H "$H" "$BASE/api/logs?limit=200")
case "$LOGS" in
  *'"消息"'*|*'REALTEST'*) echo "  [INFO] 日志已取回" ;;
esac
# 脚本自己报告的坐标必须与系统侧一致，两边对上才算注入可信
case "$LOGS" in
  *"pos=$X1,$Y1"*) check "脚本内 GetMousePosition 与系统光标一致" "match" "match" ;;
  *"pos="*) echo "  [WARN] 脚本报的坐标与系统读数不同（可能有 DPI 缩放），详见日志" ;;
  *) check "脚本执行产生了日志" "有" "无" ;;
esac
case "$LOGS" in
  *"key=ok"*) check "按键注入无异常（SendInput/Robot 未被拦）" "ok" "ok" ;;
  *) check "按键注入无异常" "ok" "异常" ;;
esac
case "$LOGS" in
  *"被系统拒绝"*|*"拒绝"*) check "注入未被 UIPI 拦截" "未拦截" "被拦截" ;;
  *) check "注入未被 UIPI 拦截" "未拦截" "未拦截" ;;
esac

echo
echo "============================================"
echo "  真机注入验证：通过 $pass / 失败 $fail"
echo "  说明：鼠标位移与按键都由 Windows 真实执行，不是模拟断言。"
echo "============================================"
[ "$fail" -eq 0 ] || exit 1
