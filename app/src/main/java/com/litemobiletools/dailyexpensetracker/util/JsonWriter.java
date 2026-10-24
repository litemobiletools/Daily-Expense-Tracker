package com.litemobiletools.dailyexpensetracker.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
/**
 * A tiny deterministic JSON writer for the backup file format. Only what the backup needs:
 * objects and arrays of objects, strings, (long) integers, booleans and null. Commas between
 * members and between array elements are inserted here, so callers can chain {@code name()} and
 * {@code begin*()} in reading order. Output is compact and stable for the same input, so the
 * SHA-256 over the data part is reproducible; files are read back with Android's built-in
 * {@code org.json}, which accepts this valid RFC 8259 output.
 */
public final class JsonWriter {

    private final StringBuilder out = new StringBuilder(512);
    /** Open scopes, '{' for objects and '[' for arrays (top = innermost). */
    private final java.util.ArrayDeque<Character> scopes = new java.util.ArrayDeque<>();
    /** Per scope: whether it already contains at least one member/element (for commas). */
    private final java.util.ArrayDeque<Boolean> hasContent = new java.util.ArrayDeque<>();

    public JsonWriter() {
    }

    public JsonWriter beginObject() {
        openScope('{');
        return this;
    }

    public JsonWriter endObject() {
        closeScope('}');
        return this;
    }

    public JsonWriter beginArray() {
        openScope('[');
        return this;
    }

    public JsonWriter endArray() {
        closeScope(']');
        return this;
    }

    public JsonWriter name(@NonNull String key) {
        if (scopes.peek() == null || scopes.peek() != '{') {
            throw new IllegalStateException("name() is only valid directly inside an object");
        }
        writeCommaIfNeeded();
        consume();
        escapeStringInto(out, key);
        out.append(':');
        return this;
    }

    private void openScope(char open) {
        // Arrays separate their elements with commas; the enclosing marker is set when an
        // element closes. Object members are separated by {@link #name}.
        if (scopes.peek() != null && scopes.peek() == '[' && hasContent.peek()) {
            out.append(',');
        }
        out.append(open);
        scopes.push(open);
        hasContent.push(Boolean.FALSE);
    }

    private void closeScope(char close) {
        if (scopes.isEmpty()) {
            throw new IllegalStateException("end*() without a matching begin*()");
        }
        out.append(close);
        scopes.pop();
        hasContent.pop();
        // The scope that just closed was one element of an enclosing array.
        if (scopes.peek() != null && scopes.peek() == '[') {
            consume();
        }
    }

    private void consume() {
        hasContent.pop();
        hasContent.push(Boolean.TRUE);
    }

    private void writeCommaIfNeeded() {
        if (hasContent.peek()) {
            out.append(',');
        }
    }

    public JsonWriter value(long value) {
        out.append(value);
        return this;
    }

    public JsonWriter value(boolean value) {
        out.append(value);
        return this;
    }

    public JsonWriter value(@NonNull String value) {
        escapeStringInto(out, value);
        return this;
    }

    public JsonWriter valueNull() {
        out.append("null");
        return this;
    }

    /** Same as {@link #value(long)} but writes {@code null} for a null reference. */
    public JsonWriter valueNullable(@Nullable Long value) {
        return value == null ? valueNull() : value(value);
    }

    /** Same as {@link #value(String)} but writes {@code null} for a null reference. */
    public JsonWriter valueNullable(@Nullable String value) {
        return value == null ? valueNull() : value(value);
    }

    /** Appends the given text as a JSON string, for when callers assemble JSON fragments by hand. */
    @NonNull
    public static String escapeString(@NonNull String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        escapeStringInto(out, raw);
        return out.toString();
    }

    @Override
    @NonNull
    public String toString() {
        return out.toString();
    }

    private static void escapeStringInto(@NonNull StringBuilder out, @NonNull String raw) {
        out.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
