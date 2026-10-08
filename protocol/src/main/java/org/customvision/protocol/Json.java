package org.customvision.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, non-polymorphic JSON parser. Integers stay Long; decimal/exponent tokens stay Double. */
final class Json {
    private final String input;
    private final ProtocolDecoder.Limits limits;
    private int position;
    private int tokens;

    private Json(String input, ProtocolDecoder.Limits limits) {
        this.input = input;
        this.limits = limits;
    }

    static Object parse(String input, ProtocolDecoder.Limits limits) throws DecodeException {
        if (input == null) throw new DecodeException(DecodeException.Reason.WRONG_TYPE, "$", "null payload");
        if (input.length() > limits.maxPayloadChars()) {
            throw new DecodeException(DecodeException.Reason.PAYLOAD_LIMIT, "$", "payload character limit");
        }
        Json parser = new Json(input, limits);
        Object result = parser.value(0);
        parser.whitespace();
        if (parser.position != input.length()) parser.fail(DecodeException.Reason.MALFORMED_JSON, "trailing data");
        return result;
    }

    private Object value(int depth) throws DecodeException {
        whitespace();
        if (++tokens > limits.maxTokens()) fail(DecodeException.Reason.TOKEN_LIMIT, "token limit");
        if (depth > limits.maxDepth()) fail(DecodeException.Reason.NESTING_LIMIT, "nesting limit");
        if (position >= input.length()) fail(DecodeException.Reason.MALFORMED_JSON, "missing value");
        char c = input.charAt(position);
        if (c == '{' || c == '[') {
            if (depth >= limits.maxDepth()) fail(DecodeException.Reason.NESTING_LIMIT, "nesting limit");
            return c == '{' ? object(depth + 1) : array(depth + 1);
        }
        if (c == '"') return string();
        if (c == 't') { literal("true"); return Boolean.TRUE; }
        if (c == 'f') { literal("false"); return Boolean.FALSE; }
        if (c == 'n') { literal("null"); return null; }
        if (c == '-' || c >= '0' && c <= '9') return number();
        fail(DecodeException.Reason.MALFORMED_JSON, "unexpected value character");
        return null;
    }

    private Map<String, Object> object(int depth) throws DecodeException {
        position++;
        Map<String, Object> result = new LinkedHashMap<>();
        whitespace();
        if (take('}')) return result;
        while (true) {
            whitespace();
            if (!peek('"')) fail(DecodeException.Reason.MALFORMED_JSON, "object key must be a string");
            if (++tokens > limits.maxTokens()) fail(DecodeException.Reason.TOKEN_LIMIT, "token limit");
            String key = string();
            if (result.containsKey(key)) fail(DecodeException.Reason.DUPLICATE_KEY, "duplicate object key: " + key);
            if (result.size() >= limits.maxObjectFields()) fail(DecodeException.Reason.COLLECTION_LIMIT, "object field limit");
            whitespace();
            if (!take(':')) fail(DecodeException.Reason.MALFORMED_JSON, "missing colon");
            result.put(key, value(depth));
            whitespace();
            if (take('}')) return result;
            if (!take(',')) fail(DecodeException.Reason.MALFORMED_JSON, "missing object separator");
        }
    }

    private List<Object> array(int depth) throws DecodeException {
        position++;
        List<Object> result = new ArrayList<>();
        whitespace();
        if (take(']')) return result;
        while (true) {
            if (result.size() >= limits.maxArrayEntries()) fail(DecodeException.Reason.COLLECTION_LIMIT, "array entry limit");
            result.add(value(depth));
            whitespace();
            if (take(']')) return result;
            if (!take(',')) fail(DecodeException.Reason.MALFORMED_JSON, "missing array separator");
        }
    }

    private String string() throws DecodeException {
        position++;
        StringBuilder value = new StringBuilder();
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == '"') return value.toString();
            if (c < 0x20) fail(DecodeException.Reason.MALFORMED_JSON, "unescaped control character");
            if (c == '\\') {
                if (position >= input.length()) fail(DecodeException.Reason.MALFORMED_JSON, "incomplete escape");
                char escape = input.charAt(position++);
                c = switch (escape) {
                    case '"', '\\', '/' -> escape;
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> { fail(DecodeException.Reason.MALFORMED_JSON, "invalid escape"); yield 0; }
                };
                if (Character.isHighSurrogate(c)) {
                    if (position + 2 > input.length() || input.charAt(position) != '\\'
                            || input.charAt(position + 1) != 'u') {
                        fail(DecodeException.Reason.MALFORMED_JSON, "unpaired Unicode surrogate");
                    }
                    position += 2;
                    char low = unicode();
                    if (!Character.isLowSurrogate(low)) fail(DecodeException.Reason.MALFORMED_JSON, "unpaired Unicode surrogate");
                    value.append(c).append(low);
                } else {
                    if (Character.isLowSurrogate(c)) fail(DecodeException.Reason.MALFORMED_JSON, "unpaired Unicode surrogate");
                    value.append(c);
                }
            } else if (Character.isHighSurrogate(c)) {
                if (position >= input.length() || !Character.isLowSurrogate(input.charAt(position))) {
                    fail(DecodeException.Reason.MALFORMED_JSON, "unpaired Unicode surrogate");
                }
                value.append(c).append(input.charAt(position++));
            } else {
                if (Character.isLowSurrogate(c)) fail(DecodeException.Reason.MALFORMED_JSON, "unpaired Unicode surrogate");
                value.append(c);
            }
            if (value.length() > limits.maxStringChars()) fail(DecodeException.Reason.STRING_LIMIT, "string limit");
        }
        fail(DecodeException.Reason.MALFORMED_JSON, "unterminated string");
        return null;
    }

    private char unicode() throws DecodeException {
        if (position + 4 > input.length()) fail(DecodeException.Reason.MALFORMED_JSON, "incomplete Unicode escape");
        int result = 0;
        for (int i = 0; i < 4; i++) {
            char c = input.charAt(position++);
            int digit = c >= '0' && c <= '9' ? c - '0'
                    : c >= 'a' && c <= 'f' ? c - 'a' + 10
                    : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) fail(DecodeException.Reason.MALFORMED_JSON, "invalid Unicode escape");
            result = (result << 4) | digit;
        }
        return (char) result;
    }

    private Number number() throws DecodeException {
        int start = position;
        take('-');
        if (take('0')) {
            if (digit()) fail(DecodeException.Reason.MALFORMED_JSON, "leading zero");
        } else {
            if (!digit()) fail(DecodeException.Reason.MALFORMED_JSON, "missing integer digits");
            while (digit()) position++;
        }
        boolean decimal = false;
        if (take('.')) {
            decimal = true;
            if (!digit()) fail(DecodeException.Reason.MALFORMED_JSON, "missing fractional digits");
            while (digit()) position++;
        }
        if (take('e') || take('E')) {
            decimal = true;
            if (!take('+')) take('-');
            if (!digit()) fail(DecodeException.Reason.MALFORMED_JSON, "missing exponent digits");
            while (digit()) position++;
        }
        if (position - start > limits.maxStringChars()) fail(DecodeException.Reason.STRING_LIMIT, "numeric token limit");
        String token = input.substring(start, position);
        try {
            // Do not use a conditional expression here: Java can promote Long to Double.
            if (!decimal) return Long.valueOf(token);
            double result = Double.parseDouble(token);
            if (!Double.isFinite(result)) fail(DecodeException.Reason.NONFINITE_NUMBER, "nonfinite numeric value");
            return Double.valueOf(result);
        } catch (NumberFormatException e) {
            fail(decimal ? DecodeException.Reason.MALFORMED_JSON : DecodeException.Reason.INTEGER_OVERFLOW,
                    decimal ? "invalid decimal" : "integer outside signed 64-bit range");
            return null;
        }
    }

    private void literal(String text) throws DecodeException {
        if (!input.startsWith(text, position)) fail(DecodeException.Reason.MALFORMED_JSON, "invalid literal");
        position += text.length();
    }

    private boolean digit() {
        return position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9';
    }
    private boolean peek(char c) { return position < input.length() && input.charAt(position) == c; }
    private boolean take(char c) { if (!peek(c)) return false; position++; return true; }
    private void whitespace() {
        while (position < input.length()) {
            char c = input.charAt(position);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
            position++;
        }
    }
    private void fail(DecodeException.Reason reason, String detail) throws DecodeException {
        throw new DecodeException(reason, "$", position, detail);
    }
}
