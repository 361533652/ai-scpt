package com.gmscpt.server.kernel.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只编译不执行的校验器。
 *
 * <p>旧版把「校验」做成了「跑一遍看看报不报错」——那会真的注入键鼠。
 * 这里只做语法编译，返回行列号给编辑器标红。
 */
public final class LuaCompiler {

    /** 一条编译诊断。 */
    public record Diagnostic(int line, int column, String message) {
    }

    /** 编译结果。 */
    public record Result(boolean ok, List<Diagnostic> diagnostics) {
        public static Result success() {
            return new Result(true, List.of());
        }
    }

    private static final Pattern LUAJ_LOCATION = Pattern.compile(":(\\d+)");

    private LuaCompiler() {
    }

    /** 编译源码；不执行、不产生任何副作用。 */
    public static Result compile(String name, String source) {
        if (source == null || source.isBlank()) {
            return new Result(true, List.of());
        }
        Globals globals = LuaSandbox.create();
        try {
            LuaValue chunk = globals.load(source, "@" + (name == null ? "script" : name));
            if (chunk.isnil()) {
                return new Result(false, List.of(new Diagnostic(1, 1, "编译结果为空")));
            }
            return Result.success();
        } catch (LuaError e) {
            return new Result(false, List.of(toDiagnostic(e)));
        } catch (Throwable t) {
            return new Result(false, List.of(new Diagnostic(1, 1, String.valueOf(t.getMessage()))));
        }
    }

    private static Diagnostic toDiagnostic(LuaError error) {
        String message = error.getMessage() == null ? "语法错误" : error.getMessage();
        int line = 1;
        int column = 1;
        // LuaJ 的报错形如：script:12: 'end' expected
        Matcher m = LUAJ_LOCATION.matcher(message);
        if (m.find()) {
            try {
                line = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                line = 1;
            }
        }
        // 去掉 "文件名:行号:" 前缀，只把可读错误给界面
        int idx = message.indexOf(": ", message.indexOf(':') + 1);
        String clean = idx > 0 && idx + 2 < message.length() ? message.substring(idx + 2) : message;
        return new Diagnostic(line, column, clean);
    }

    /** 顺带做一个「有没有 OnEvent」的静态提示，供界面在保存时提醒。 */
    public static boolean hasOnEvent(String source) {
        if (source == null) return false;
        return source.contains("function OnEvent") || source.contains("OnEvent =") || source.contains("OnEvent=");
    }

    /** 找出所有形如 `XxxYyy(` 的调用名，用于「AI 是否用了不存在的 API」这类粗筛。 */
    public static List<String> calledFunctions(String source) {
        List<String> out = new ArrayList<>();
        if (source == null) return out;
        Matcher m = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\s*\\(").matcher(source);
        while (m.find()) {
            String name = m.group(1);
            if (!out.contains(name)) out.add(name);
        }
        return out;
    }
}
