package com.arc1.mcp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Json {

    private Json() {
    }

    static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static String readString(String json, String key) {
        Pattern p = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return unescape(m.group(1));
    }

    static Integer readInt(String json, String key) {
        Pattern p = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Boolean readBoolean(String json, String key) {
        Pattern p = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return Boolean.valueOf(m.group(1));
    }

    /**
     * Extracts a flat array of strings for a given key. Only handles JSON arrays
     * of string scalars (which is all our tool schemas expose). Nested objects
     * inside the array are ignored.
     */
    static java.util.List<String> readStringArray(String json, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Pattern arrPattern = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher arrM = arrPattern.matcher(json);
        if (!arrM.find()) {
            return out;
        }
        String inside = arrM.group(1);
        Pattern strPattern = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher strM = strPattern.matcher(inside);
        while (strM.find()) {
            out.add(unescape(strM.group(1)));
        }
        return out;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default:
                        sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
