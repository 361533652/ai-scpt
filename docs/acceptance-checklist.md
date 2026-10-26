# 验收清单（GmScript 2.0）

默认结论：**未通过**。只有拿到可复现证据才改判。分三类：

- **[A] 自动化**：命令 + 预期输出，跑一遍就知道。
- **[S] 半自动**：带着令牌调接口 / 看日志，判断依据明确。
- **[M] 真机人工**：必须有人在真实桌面按键、移动鼠标、看游戏反应。留四件套证据：
  操作前截图 → 操作 → 操作后截图 → 控制台日志文本。

启动前把 `TOKEN` 替换成启动日志里的令牌（或直接用浏览器地址里的那个）。

```bash
TOKEN=xxxx
BASE=http://127.0.0.1:8790
H="X-GmScript-Token: $TOKEN"
```

---

## 1. 编译与内核测试 [A]

```bash
mvn -B test -Dtest='KernelInputTest,LuaRuntimeTest'
```

预期：`Tests run: 18, Failures: 0, Errors: 0`（数量随版本浮动，但失败必须为 0）。

**证伪**：如果 `while true do end` 那条测试超时失败，说明停止钩子没装上——
那是旧版最致命的 bug（ADR 的 F1），必须当天修掉，不能放行。

## 2. 服务启动与鉴权 [A]

```bash
curl -s -o /dev/null -w "%{http_code}\n" $BASE/api/health                 # 预期 401
curl -s -H "$H" $BASE/api/health | head -c 400                            # 预期 200 + JSON
netstat -ano | findstr :8790                                              # 预期 127.0.0.1:8790，不能是 0.0.0.0
```

- `/api/health` 里 `hook.registered` 必须为 `true`；为 `false` 时 `hook.error` 要写清原因。
- `ollama.reachable` 反映本机 Ollama 是否在跑。

**证伪**：`netstat` 出现 `0.0.0.0:8790` ⇒ 监听范围错了，局域网可访问，必须改回回环。
无 token 也能拿到 200 ⇒ 鉴权没生效。

**特别注意 `hook.registered=false` 且 `error` 是 `URI is not hierarchical`**：
这是 JNativeHook 2.2.2 在 Spring Boot fat jar 下的固有问题——
它用 `new File(CodeSource.getLocation().toURI())` 定位原生库，而 fat jar 里依赖的 code source 是
`jar:file:...!/BOOT-INF/lib/...`（非分层 URI），`new File(URI)` 直接抛 `IllegalArgumentException`，
而它只 catch 了 `URISyntaxException`。
本仓库已用 `NativeLibraryLoader` 绕过（解包到 `~/.gmscpt/native/`）。
如果这行日志又出现了，说明自定义定位器没生效，热键会全部失效——这不是「用户环境问题」。

## 3. 脚本库往返 [S]

```bash
curl -s -H "$H" -H 'Content-Type: application/json' \
  -d '{"name":"验收脚本","source":"function OnEvent(e,a) end"}' $BASE/api/scripts
# 记下返回的 id，然后：
curl -s -H "$H" $BASE/api/scripts | head -c 300
curl -s -H "$H" $BASE/api/scripts/<id>/export
```

预期：新建后立刻能列出；`export` 与提交的正文逐字节一致；重启服务后仍在。

**证伪**：再建第二个脚本后第一个消失 ⇒ 还是旧的「单脚本」模型。

## 4. 编译校验 [S]

```bash
curl -s -H "$H" -H 'Content-Type: application/json' -X POST \
  -d '{"source":"function OnEvent(e,a)\n  if true then\nend\n"}' \
  $BASE/api/scripts/<id>/validate
```

预期：`ok=false`，`diagnostics[0].line` 指向出错行。

**证伪**：返回 `ok=true` ⇒ 校验是假的（旧版把「跑一遍不报错」当校验，那会真的注入键鼠）。

## 5. 运行与停止 [M]

1. 在界面里给脚本绑定 `f8`（绑定框按下 F8）。
2. 打开记事本，按 F8。
3. 页面控制台应出现「运行开始…」「已进入事件派发循环」，脚本动作在记事本上可见。

**证伪（旧版失败点）**：
- 按 F8 后**任何窗口都没反应**，但页面显示「运行中」⇒ 钩子没注册，看 `/api/health`。
- 脚本是 `while true do end` 时点「停止」停不下来、CPU 占满 ⇒ F1 回归。
- 脚本 `PressKey("w")` 后点「停止」，记事本里 W 还在重复输入 ⇒ H1 回归（卡键）。
  日志里应有一行「已释放残留输入 N 项」。

## 5b. 真机注入（已自动化，会真的动鼠标）[M]

```bash
bash scripts/real-input-test.sh
```

启动隔离实例，让脚本执行 `MoveMouseRelative(200, 0)`，再用
`System.Windows.Forms.Cursor.Position` 从**系统侧**读光标，对比前后差值。
通过判据：`dx=200 dy=0`（±1 像素），且脚本内的 `GetMousePosition()` 与系统读数一致。

实测（2026-10-27）：

```
初始光标: (1260, 247)  ->  注入后: (1460, 247)
实际位移: dx=200 dy=0
[PASS] 相对移动生效 / 无垂直漂移 / 坐标两边一致 / 按键注入未被拦 / 未被 UIPI 拦截
```

**这条是唯一能证明 `SendInput` 真生效的测试**——单元测试与 HTTP 测试都碰不到系统调用。

## 5c. 全局钩子与热键（已自动化，会真的按键）[M]

```bash
bash scripts/hook-test.sh
```

用 PowerShell `SendKeys` 从系统侧合成按键（与真人按键同源），验证钩子收到按下（`event=3`）、
收到抬起（`event=4`），以及**绑定的热键能把已停止的脚本重新拉起来**。

实测（2026-10-27）：5/5 通过。

两个踩过的坑（写下来免得下次误判成产品缺陷）：
- 脚本若用「`Sleep` 到某时刻」维持存活，跑完就退出了，后续按键落在空窗里 → 假失败。
  正确写法只定义 `OnEvent`，让运行时进入派发循环。
- `SendKeys('{F13}')` **只发按下不发抬起**，用它验证 `event=4` 必然失败。
  验证抬起要用普通键（脚本里用 `a`，F13 只用来验证"热键拉起脚本"）。

## 6. 不依赖 Sleep 的事件派发 [S]

抓一个脚本，内容只有：

```lua
function OnEvent(event, arg)
    OutputLogMessage("evt:" .. tostring(event))
end
```

运行后按任意键，日志里必须出现 `evt:3`（按下）/`evt:4`（抬起）。

**证伪**：顶层没有任何 `Sleep` 却一条事件都没有 ⇒ M1 回归。

## 7. 自注入不回流（回声抑制）[M]

脚本：

```lua
function OnEvent(event, arg)
    ClickMouseButton(1)
    Sleep(50)
end
```

运行后**只按一次物理左键**，看日志里有几条 `evt:1`。

预期：1 条（物理那次）。**证伪**：出现 2 条及以上 ⇒ 自己注入的点击被当成真实事件回灌，
旧版就是这样（存 1/2/3、比 16/8/4，集合永不相等）。

## 8. 鼠标左右键语义 [M]

脚本：

```lua
function OnEvent(event, arg)
    if arg == 3 then OutputLogMessage("右键是对的") end
end
```

运行后按**右键**。

预期：出现「右键是对的」。**证伪**：按右键没反应、按中键反而触发 ⇒ 又回到了
「钩子 2=右 3=中」与「Lua 2=中 3=右」混用的老问题。

## 9. 相对移动精度 [M]

```lua
function OnEvent(event, arg)
    local x1, y1 = GetMousePosition()
    MoveMouseRelative(200, 0)
    Sleep(30)
    local x2, y2 = GetMousePosition()
    OutputLogMessage("dx=" .. (x2 - x1) .. " dy=" .. (y2 - y1))
end
```

预期：`dx=200 dy=0`（±1 像素，DPI 缩放下按物理像素算）。

**证伪**：`dx=0` ⇒ 注入被拦（看日志里有没有「鼠标注入被系统拒绝」，
通常是目标程序以管理员运行时 UIPI 阻挡）；`dx≈400` ⇒ 同时走了两条注入路径。

## 10. AI 生成脚本 [S]

```bash
curl -s -H "$H" $BASE/api/ai/providers    # ollama.available 应为 true
curl -s -H "$H" "$BASE/api/ai/models?providerId=ollama"   # 应含 qwen2.5-coder:7b
```

界面里问「按住右键自动连点，间隔 150 毫秒」，预期：流式返回、思考链可折叠、
结束后给出一个 ```lua 代码块，点「插入」能进编辑器，运行不报 `attempt to call nil`。

**证伪**：
- 把模型名改成 `qwen3:99b` 再测连通性，如果还报「成功」⇒ 探活是假的。
- 生成的脚本里出现 `os.execute` 之类 ⇒ 沙箱或提示词有问题（沙箱会直接报 nil 调用错误）。
- 中途没有停止按钮、卡住就再也发不了消息 ⇒ 取消语义回归（旧版读超时 0 的老毛病）。

## 11. 打包后的 EXE [M]

```powershell
powershell -ExecutionPolicy Bypass -File build-exe.ps1
```

在**没有装 JDK 的干净环境**上跑 `target\dist\GmScript\GmScript.exe`：
窗口/控制台起得来、`/api/health` 里 `hook.registered=true`、脚本能跑。

**证伪**：双击闪退 / 提示找不到 Java ⇒ 运行时没打进去；
`hook.registered=false` 且看不到任何提示 ⇒ 又回到了「没有控制台、看不见失败」。

## 12. 退出后不留痕 [M]

脚本里 `PressKey("w")` 后**直接杀进程**（任务管理器结束），再回来看 W 键有没有卡住。
正常退出（关控制台/服务结束）时，日志必须有「停止脚本、取消 AI 请求、释放输入、卸载钩子」四步。

**证伪**：鼠标灵敏度被改过 ⇒ 有人又去动系统设置了（新版设计上不碰）。

---

## 放行条件

同时满足才判定「可用」：

1. 第 1、2、3、4 节自动化/半自动项全绿；
2. 第 5、6、7、8 节真机项各至少一次可复现的正向证据；
3. 第 11 节能在干净环境跑起来；
4. 以上任何一项失败时,失败原因是**已知且写在 README「已知限制」里**的，而不是未知的。

---

## 附：两个「真机才暴露」的坑（已修，留档防回归）

### A. fat jar 里全局钩子根本注册不上

现象：`/api/health` 返回 `hook:{registered:false, error:"URI is not hierarchical"}`，
脚本能启动、日志正常，但按什么键都没反应。

根因：JNativeHook 2.2.2 的 `DefaultLibraryLocator` 用
`new File(GlobalScreen.class.getProtectionDomain().getCodeSource().getLocation().toURI())`
定位 dll。Spring Boot 可执行 jar 把依赖放进 `BOOT-INF/lib/`，code source 变成
`jar:file:...!/BOOT-INF/lib/jnativehook-2.2.2.jar`（非分层 URI），
`new File(URI)` 抛 `IllegalArgumentException`，而它只 catch 了 `URISyntaxException`。

修复：`NativeLibraryLoader` 从 classpath 读 dll 解包到 `~/.gmscpt/native/`，
并通过 `jnativehook.lib.locator` 系统属性接管。修好后同一环境日志出现：

```
NativeLibraryLoader - 检测到嵌套 jar 运行环境，已启用自定义原生库定位器
NativeLibraryLoader - 原生库已解包：C:\Users\...\.gmscpt\native\jnativehook-2.2.2-x86_64\JNativeHook.dll
StartupRunner -  钩子状态：已注册
```

**回归判据**：任何一次发版，`/api/health` 的 `hook.registered` 必须是 `true`。
`mvn test` 覆盖不到这条（它需要真实 dll），只能靠启动日志。

### B. 硬编码 `think:true` 会让默认模型直接 400

现象：AI 一问就回 `LLM_UPSTREAM_ERROR: Ollama 上游错误：400 Bad Request`。

根因：请求体里带了 `"think": true`，而模型不支持思考链时 Ollama 报
`{"error":"\"qwen2.5-coder:7b\" does not support thinking"}`。

取证：

```bash
curl -s -X POST http://127.0.0.1:11434/api/chat -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-coder:7b","messages":[{"role":"user","content":"hi"}],"stream":false,"think":true}'
# {"error":"\"qwen2.5-coder:7b\" does not support thinking"}
```

修复：`OllamaAiGateway.supportsThinking(model)` 按家族白名单判断，只有推理模型才下发 `think`。
`OllamaThinkingTest` 覆盖这条。

**回归判据**：切换模型后（尤其是 coder 类模型）必须能正常出字。
