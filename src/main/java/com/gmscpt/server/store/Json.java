package com.gmscpt.server.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小的 JSON 解析/序列化实现，只覆盖知识库种子文件与 {@code tags_json} 列所需的子集。
 *
 * <p>为什么不引第三方库：pom 里能用的 Gson 属于「旧版 Swing 遗留依赖」（pom 注释明确写了新代码勿引用），
 * 而为了解析一次种子文件再引一个 JSON 库不值得。这里 200 行搞定：
 * 支持 object / array / string（含 {@code \\uXXXX} 转义）/ number / true / false / null。
 *
 * <p>解析失败一律抛 {@link IllegalArgumentException}，由调用方决定是跳过还是上抛——
 * 种子导入路径必须捕获它，不能让一份坏文件把服务启动带崩。
 */
final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- 解析

    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 文本为 null");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容（位置 " + parser.pos + "）");
        }
        return value;
    }

    private static final class Parser {

        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private boolean eof() {
            return pos >= text.length();
        }

        private char peek() {
            if (eof()) {
                throw new IllegalArgumentException("JSON 意外结束（位置 " + pos + "）");
            }
            return text.charAt(pos);
        }

        private void skipWhitespace() {
            while (!eof()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private Object readValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw new IllegalArgumentException("JSON 期望 " + literal + "（位置 " + pos + "）");
            }
            pos += literal.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IllegalArgumentException("JSON 对象缺少 ':'（位置 " + pos + "）");
                }
                pos++;
                map.put(key, readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new IllegalArgumentException("JSON 对象缺少 ',' 或 '}'（位置 " + pos + "）");
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new IllegalArgumentException("JSON 数组缺少 ',' 或 ']'（位置 " + pos + "）");
            }
        }

        private String readString() {
            if (peek() != '"') {
                throw new IllegalArgumentException("JSON 期望字符串（位置 " + pos + "）");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new IllegalArgumentException("JSON 字符串未闭合（位置 " + pos + "）");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new IllegalArgumentException("JSON 转义未完成（位置 " + pos + "）");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new IllegalArgumentException("JSON \\u 转义不完整（位置 " + pos + "）");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("无法识别的 JSON 转义 \\" + esc);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            while (!eof()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("JSON 期望值（位置 " + start + "）");
            }
            try {
                if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                    return Long.parseLong(raw);
                }
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("JSON 数字格式非法: " + raw);
            }
        }
    }

    // ---------------------------------------------------------------- 取值

    static String string(Object node) {
        if (node == null) {
            return null;
        }
        return node instanceof String s ? s : String.valueOf(node);
    }

    static String string(Object node, String fallback) {
        String s = string(node);
        return (s == null || s.isBlank()) ? fallback : s;
    }

    static boolean bool(Object node, boolean fallback) {
        if (node instanceof Boolean b) {
            return b;
        }
        if (node instanceof String s) {
            return "true".equalsIgnoreCase(s.trim());
        }
        if (node instanceof Number n) {
            return n.intValue() != 0;
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    static List<String> stringList(Object node) {
        if (!(node instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            String s = string(item);
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object node) {
        if (node instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    // ---------------------------------------------------------------- 序列化

    /** 序列化：只支持 Map / List / String / Number / Boolean / null。 */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    static String writeStrings(List<String> values) {
        return write(values == null ? List.of() : values);
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
