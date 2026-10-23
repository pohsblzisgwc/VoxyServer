package com.dripps.voxyserver.config;

import com.google.gson.Gson;

import java.util.*;

/**
 * The same field types are offered and parsed by config commands; validation remains on the
 * candidate.
 */
public final class ConfigValueParser {
    private static final Gson JSON = new Gson();

    private ConfigValueParser() {}

    public static boolean supports(Class<?> type) {
        return type == int.class
                || type == boolean.class
                || type == double.class
                || type == List.class;
    }

    public static Object parse(Class<?> type, String value) {
        if (type == int.class) return Integer.parseInt(value);
        if (type == double.class) return Double.parseDouble(value);
        if (type == boolean.class) {
            if (value.equalsIgnoreCase("true")) return true;
            if (value.equalsIgnoreCase("false")) return false;
            throw new IllegalArgumentException("expected true or false");
        }
        if (type == List.class) {
            var strings = JSON.fromJson(value, String[].class);
            if (strings == null) throw new IllegalArgumentException("expected a JSON string array");
            return new ArrayList<>(Arrays.asList(strings));
        }
        throw new IllegalArgumentException("unsupported configuration type");
    }
}
