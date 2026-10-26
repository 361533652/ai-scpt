#!/usr/bin/env bash
# ============================================================
# 端到端冒烟：启动服务 -> 打接口 -> 断言 -> 关停
#
# 用法：bash scripts/smoke.sh          （默认端口 8899，避开开发期占用的 8790）
#      SMOKE_PORT=9001 bash scripts/smoke.sh
#
# 注意：所有带中文的请求体都写进临时文件再用 --data-binary @file 发，
# 直接在命令行里塞中文会被 bash/curl 按本地代码页转码，服务端会报
# "Invalid UTF-8 middle byte" —— 那是测试脚本的问题，不是服务端的问题。
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/e/IntelliJ IDEA 2025.3/jbr}"
JAVA="$JAVA_HOME/bin/java"
MVN="${MVN:-/e/IntelliJ IDEA 2025.3/plugins/maven/lib/maven3/bin/mvn}"
JAR="$ROOT/target/gmscpt-2.0.0-SNAPSHOT.jar"
LOG="$ROOT/target/smoke-server.log"
PORT="${SMOKE_PORT:-8899}"
BASE="http://127.0.0.1:$PORT"
TMP="$ROOT/target/smoke-tmp"
mkdir -p "$TMP"

pass=0
fail=0
check() {
  if [ "$2" = "$3" ]; then
    echo "  [PASS] $1"; pass=$((pass + 1))
  else
    echo "  [FAIL] $1  期望=$2 实际=$3"; fail=$((fail + 1))
  fi
}
check_contains() {
  case "$3" in
    *"$2"*) echo "  [PASS] $1"; pass=$((pass + 1)) ;;
    *) echo "  [FAIL] $1  未包含 '$2'"; echo "        实际: ${3:0:300}"; fail=$((fail + 1)) ;;
  esac
}

json() { # json <文件名> <内容>
  printf '%s' "$2" > "$TMP/$1"
  echo "$TMP/$1"
}
post_json() { # post_json <url> <file>
  curl -s -H "$H" -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$2" "$1"
}

echo "===== [1/6] 构建 ====="
( cd "$ROOT" && "$MVN" -B -q -DskipTests package ) || { echo "[FATAL] 构建失败"; exit 1; }
[ -f "$JAR" ] || { echo "[FATAL] 没找到 $JAR"; exit 1; }
echo "  OK: $JAR"

echo "===== [2/6] 启动服务（端口 $PORT）====="
rm -f "$LOG"
# 关键：用隔离的数据目录。不隔离的话冒烟测试会往用户真实的 ~/.gmscpt 里
# 写一堆测试脚本、热键和日志（实测跑了几轮留下 18 个脚本），
# 而且第二次跑会因为上次留下的热键绑定判冲突。
SMOKE_DATA="$ROOT/target/smoke-data"
rm -rf "$SMOKE_DATA"
mkdir -p "$SMOKE_DATA"
"$JAVA" -jar "$JAR" --gmscpt.open-browser=false --server.port="$PORT" \
  --gmscpt.data-dir="$SMOKE_DATA" > "$LOG" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null; wait $SERVER_PID 2>/dev/null' EXIT

READY=0
for _ in $(seq 1 60); do
  if grep -q "GmScript 已启动" "$LOG" 2>/dev/null; then READY=1; break; fi
  if ! kill -0 $SERVER_PID 2>/dev/null; then break; fi
  sleep 1
done
if [ "$READY" != "1" ]; then
  echo "[FATAL] 服务没起来，日志尾部："; tail -40 "$LOG"; exit 1
fi
TOKEN=$(grep -oE 'token=[a-z0-9]+' "$LOG" | head -1 | cut -d= -f2)
[ -n "$TOKEN" ] || { echo "[FATAL] 没从日志里取到 token"; exit 1; }
H="X-GmScript-Token: $TOKEN"
echo "  OK: 服务已就绪（token=${TOKEN:0:4}****）"

echo "===== [3/6] 鉴权与健康检查 ====="
check "无 token 必须 401" "401" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health")"
health=$(curl -s -H "$H" "$BASE/api/health")
check_contains "带 token 能拿到 health" '"version"' "$health"
check_contains "health 里有钩子状态" '"hook"' "$health"
check_contains "health 里有 ollama 状态" '"ollama"' "$health"

listening=$(netstat -ano 2>/dev/null | grep LISTENING | grep ":$PORT " | head -1)
case "$listening" in
  *"127.0.0.1:$PORT"*) check "只监听 127.0.0.1" "loopback" "loopback" ;;
  *"0.0.0.0:$PORT"*) check "只监听 127.0.0.1" "loopback" "wildcard(危险)" ;;
  *) echo "  [SKIP] netstat 没抓到端口" ;;
esac

# 静态资源（前端产物）
check "首页返回 200（前端已打进 jar）" "200" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/?token=$TOKEN")"

echo "===== [4/6] 脚本库 CRUD ====="
f=$(json create1.json '{"name":"冒烟脚本","description":"自动化测试","source":"function OnEvent(event, arg)\n  OutputLogMessage(\"hi\")\nend\n"}')
created=$(post_json "$BASE/api/scripts" "$f")
check_contains "新建脚本返回 id" '"id"' "$created"
SID=$(printf '%s' "$created" | grep -oE '"id":"[^"]+"' | head -1 | cut -d'"' -f4)
[ -n "$SID" ] || { echo "[FATAL] 取不到脚本 id：$created"; exit 1; }

exported=$(curl -s -H "$H" "$BASE/api/scripts/$SID/export")
check_contains "导出正文与提交一致" "OnEvent" "$exported"

f=$(json create2.json '{"name":"第二个脚本","source":"function OnEvent(e,a) end"}')
post_json "$BASE/api/scripts" "$f" > /dev/null
count=$(curl -s -H "$H" "$BASE/api/scripts" | grep -o '"id"' | wc -l)
if [ "$count" -ge 2 ]; then check "脚本库里至少两条（多脚本）" "yes" "yes"; else check "脚本库里至少两条" "yes" "no($count)"; fi

# 乐观锁：提交过期的 rev 必须 409
f=$(json update_old.json '{"name":"冒烟脚本","source":"function OnEvent(e,a) end","rev":999}')
code=$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  -X PUT --data-binary "@$f" "$BASE/api/scripts/$SID")
check "过期 rev 提交返回 409" "409" "$code"

echo "===== [5/6] 编译校验 ====="
f=$(json bad.json '{"source":"function OnEvent(e,a)\n  if true then\nend\n"}')
bad=$(post_json "$BASE/api/scripts/$SID/validate" "$f")
check_contains "语法错误被识别" '"ok":false' "$bad"
check_contains "错误带行号" '"line"' "$bad"

f=$(json good.json '{"source":"function OnEvent(e,a) end\n"}')
good=$(post_json "$BASE/api/scripts/$SID/validate" "$f")
check_contains "合法脚本通过" '"ok":true' "$good"

echo "===== [6/6] 运行、日志、文档、知识库 ====="
f=$(json run.json '{}')
run=$(post_json "$BASE/api/scripts/$SID/run" "$f")
check_contains "启动返回 runId" '"runId"' "$run"

sleep 2
check_contains "运行态可查" '"items"' "$(curl -s -H "$H" "$BASE/api/runs/active")"
check_contains "停止有响应" '"stopped"' "$(curl -s -H "$H" -X POST "$BASE/api/scripts/$SID/stop")"

logs=$(curl -s -H "$H" "$BASE/api/logs?limit=200")
check_contains "日志里有运行记录" '运行开始' "$logs"
check_contains "日志是 SSE 可续传的（带 lastSeq）" '"lastSeq"' "$logs"

luaapi=$(curl -s -H "$H" "$BASE/api/lua/api")
check_contains "Lua API 文档可用" 'PressAndReleaseKey' "$luaapi"
check_contains "文档里有 ResetMouseAccumulator" 'ResetMouseAccumulator' "$luaapi"
check_contains "文档声明了沙箱" '沙箱' "$luaapi"

check_contains "知识库种子已导入" '"items"' "$(curl -s -H "$H" "$BASE/api/kb")"
check_contains "AI 通道列表可用" '"items"' "$(curl -s -H "$H" "$BASE/api/ai/providers")"

# 知识库新建：曾经因为 KnowledgeEntry 构造器要求 id 非空而稳定 500
f=$(json kb_new.json '{"game":"冒烟","title":"冒烟条目","description":"desc","script":"function OnEvent(e,a) end","tags":["smoke"]}')
kbcode=$(curl -s -o "$TMP/kb_out.json" -w '%{http_code}' -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "@$f" "$BASE/api/kb")
check "新建知识条目返回 201" "201" "$kbcode"
check_contains "新建条目默认启用" '"enabled":true' "$(cat "$TMP/kb_out.json")"
KBID=$(grep -oE '"id":"[^"]+"' "$TMP/kb_out.json" | head -1 | cut -d'"' -f4)
check "删除知识条目返回 204" "204" "$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -X DELETE "$BASE/api/kb/$KBID")"

# 设置读写（apiKey 只进不出）
f=$(json settings.json '{"ollamaBaseUrl":"http://127.0.0.1:11434","ollamaModel":"qwen2.5-coder:7b","emergencyHotkey":"ctrl+shift+f12"}')
putcode=$(curl -s -o "$TMP/settings_out.json" -w '%{http_code}' -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  -X PUT --data-binary "@$f" "$BASE/api/settings")
check "保存设置返回 200" "200" "$putcode"
if grep -q '"apiKey"' "$TMP/settings_out.json"; then
  check "设置响应不能泄露 apiKey" "no-leak" "LEAKED"
else
  check "设置响应不能泄露 apiKey" "no-leak" "no-leak"
fi

# 默认模型必须能真的出字（think:true 会给不支持的模型 400）
f=$(json chat.json '{"message":"说一个字","providerId":"ollama","model":"qwen2.5-coder:7b"}')
chatout=$(curl -s -N --max-time 90 -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "@$f" "$BASE/api/ai/chat" | head -c 4000)
check_contains "AI 流式返回 started 帧" 'event:started' "$chatout"
check_contains "AI 真的有输出（不是 400 thinking 错误）" '"channel":"content"' "$chatout"

# 热键：绑定 + 规范化 + 冲突检测
# 数据目录已隔离，但热键仍用 F13-F24 里的随机一个：
# 万一有人改了路径复用真实库，也不会和用户自己的绑定撞车。
HKFN=$(( RANDOM % 12 + 13 ))
HKKEY="ctrl+alt+shift+f$HKFN"
HKRAW="shift+ctrl+alt+f$HKFN"     # 故意打乱修饰键顺序
echo "  （本次使用热键 $HKKEY，提交时写作 $HKRAW）"
f=$(json hk_bind.json "{\"items\":[{\"accelerator\":\"$HKRAW\",\"event\":\"KEY_PRESSED\",\"action\":\"TOGGLE\",\"enabled\":true}]}")
hk=$(curl -s -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  -X PUT --data-binary "@$f" "$BASE/api/scripts/$SID/hotkeys")
check_contains "绑定热键成功" '"accelerator"' "$hk"
check_contains "热键表达式被规范化（不是原样落库）" "\"accelerator\":\"$HKKEY\"" "$hk"

# 另一个脚本绑同一个组合必须 409
f=$(json hk_new2.json '{"name":"冲突测试脚本","source":"function OnEvent(e,a) end"}')
SID2=$(post_json "$BASE/api/scripts" "$f" | grep -oE '"id":"[^"]+"' | head -1 | cut -d'"' -f4)
f=$(json hk_conflict.json "{\"items\":[{\"accelerator\":\"$HKRAW\",\"event\":\"KEY_PRESSED\",\"action\":\"RUN\",\"enabled\":true}]}")
hkcode=$(curl -s -o "$TMP/hk_out.json" -w '%{http_code}' -H "$H" -H 'Content-Type: application/json; charset=utf-8' \
  -X PUT --data-binary "@$f" "$BASE/api/scripts/$SID2/hotkeys")
check "同一组合重复绑定返回 409" "409" "$hkcode"
check_contains "冲突响应带 ACCEL_CONFLICT" 'ACCEL_CONFLICT' "$(cat "$TMP/hk_out.json")"
curl -s -o /dev/null -H "$H" -X DELETE "$BASE/api/scripts/$SID2"

# 删除收尾
check "删除脚本返回 204" "204" "$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -X DELETE "$BASE/api/scripts/$SID")"

echo
echo "============================================"
echo "  通过 $pass / 失败 $fail"
echo "  服务日志: $LOG"
echo "============================================"
[ "$fail" -eq 0 ] || exit 1
