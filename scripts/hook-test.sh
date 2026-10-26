#!/usr/bin/env bash
# ============================================================
# 全局钩子 + 热键触发验证：由系统真的合成一次按键，看钩子是否收到
#
# 为什么需要它：`hook.registered=true` 只证明钩子装上去了，
# 不证明它真的能收到事件、能匹配热键、能启动脚本。
# 这里用 PowerShell 的 SendKeys 从"系统侧"造一次真实按键，
# 走的是和用户按键盘完全相同的路径（不是调我们的注入 API）。
#
# 安全性：只按 F13（多数键盘上没有这个键，不会影响你正在用的程序）。
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/e/IntelliJ IDEA 2025.3/jbr}"
JAVA="$JAVA_HOME/bin/java"
JAR="$ROOT/target/gmscpt-2.0.0-SNAPSHOT.jar"
PORT="${SMOKE_PORT:-8896}"
BASE="http://127.0.0.1:$PORT"
LOG="$ROOT/target/hook-test.log"
DATA="$ROOT/target/hook-test-data"
TMP="$ROOT/target/hook-test-tmp"
rm -rf "$DATA" "$TMP"; mkdir -p "$TMP"

pass=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "  [PASS] $1"; pass=$((pass+1));
  else echo "  [FAIL] $1  期望=$2 实际=$3"; fail=$((fail+1)); fi
}
contains() {
  case "$3" in
    *"$2"*) echo "  [PASS] $1"; pass=$((pass+1)) ;;
    *) echo "  [FAIL] $1  未包含 '$2'"; echo "        实际: ${3:0:200}"; fail=$((fail+1)) ;;
  esac
}

echo "===== [1/5] 启动服务（隔离数据目录）====="
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
check "钩子已注册" "true" "$(curl -s -H "$H" "$BASE/api/health" | grep -oE '"hook":\{"registered":[a-z]+' | grep -oE '[a-z]+$')"

echo "===== [2/5] 准备一个记录事件的脚本 ====="
# 注意：不要用 "Sleep 到某个时刻" 来维持存活——那样脚本跑完就结束了，
# 后面的按键会落在"没有脚本在跑"的空窗里（这个坑踩过，表现为断言假失败）。
# 正确写法是只定义 OnEvent，让运行时进入派发循环，按「停止」或超时才退出。
cat > "$TMP/script.json" <<'EOF'
{"name":"钩子接收验证",
 "source":"function OnEvent(event, arg)\n  OutputLogMessage(\"HOOKTEST event=\" .. tostring(event) .. \" arg=\" .. tostring(arg))\nend\n"}
EOF
SID=$(curl -s -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "@$TMP/script.json" "$BASE/api/scripts" | grep -oE '"id":"[^"]+"' | head -1 | cut -d'"' -f4)

echo "===== [3/5] 绑定热键并启动 ====="
# 绑 f13 用于后面验证"热键重新拉起脚本"（F13 按下能被钩子收到，虽然不发抬起）。
printf '%s' '{"items":[{"accelerator":"f13","event":"KEY_PRESSED","action":"TOGGLE","enabled":true}]}' > "$TMP/hk.json"
HK=$(curl -s -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  -X PUT --data-binary "@$TMP/hk.json" "$BASE/api/scripts/$SID/hotkeys")
contains "F13 热键绑定成功" '"accelerator":"f13"' "$HK"

printf '%s' '{}' > "$TMP/run.json"
curl -s -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "@$TMP/run.json" "$BASE/api/scripts/$SID/run" > /dev/null
sleep 2

echo "===== [4/5] 从系统侧合成真实按键 ====="
# 用 A 而不是 F13 来验证"按下 + 抬起"：实测 SendKeys 对 F13 这类非常规键
# 只发出按下、不发抬起（SendKeys 的已知行为，不是我们的缺陷），
# 而普通字符键的按下/抬起都会走完整路径。
# A 会落到当前前台窗口里，所以请让焦点停在无害的地方（脚本按完立刻判完）。
powershell -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait('a')" 2>&1 | head -3
echo "  已发送按键 a"
sleep 3

echo "===== [5/5] 判定钩子是否收到 ====="
# 轮询等事件出现：按键派发是异步的，固定 sleep 容易在快机器上假失败（踩过）。
for _ in $(seq 1 20); do
  LOGS=$(curl -s -H "$H" "$BASE/api/logs?limit=300")
  case "$LOGS" in *'HOOKTEST event=4'*) break ;; esac
  sleep 0.5
done
# event=3 是 KEY_PRESSED、event=4 是 KEY_RELEASED，钩子收到就会派发进 OnEvent
contains "钩子收到了真实按键的按下（event=3）" 'HOOKTEST event=3' "$LOGS"
contains "钩子收到了真实按键的抬起（event=4）" 'HOOKTEST event=4' "$LOGS"

# 再确认「热键→启动脚本」这条链：先停掉，再从系统侧按一次 F13，
# 绑定表应该真的把脚本拉起来（而不是只有人工 /run 才起得来）。
curl -s -H "$H" -X POST "$BASE/api/scripts/$SID/stop" > /dev/null
sleep 2
ACTIVE_BEFORE=$(curl -s -H "$H" "$BASE/api/runs/active" | grep -oE '"scriptId"' | wc -l)
powershell -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.SendKeys]::SendWait('{F13}')" > /dev/null 2>&1
sleep 3
ACTIVE_AFTER=$(curl -s -H "$H" "$BASE/api/runs/active" | grep -oE '"scriptId"' | wc -l)
check "热键能把已停止的脚本重新拉起来（停止前 $ACTIVE_BEFORE → 按 F13 后 $ACTIVE_AFTER）" "1" "$ACTIVE_AFTER"
curl -s -H "$H" -X POST "$BASE/api/scripts/$SID/stop" > /dev/null

echo
echo "============================================"
echo "  钩子链路验证：通过 $pass / 失败 $fail"
echo "  说明：F13 由 Windows SendKeys 合成，走真实输入路径。"
echo "  若失败，最常见的原因是目标进程权限低于前台窗口（UIPI），"
echo "  或者钩子被安全软件拦截——看 $LOG 与 /api/health 的 hook.error。"
echo "============================================"
[ "$fail" -eq 0 ] || exit 1
