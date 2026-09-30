package com.joshuamorley.depthwise.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import org.maplibre.geojson.Feature;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads feature properties and fills "{field}" templates from pack configs. */
public final class FeatureText {

    private static final Pattern FIELD = Pattern.compile("\\{([^}]+)\\}");

    private FeatureText() {}

    public static String string(Feature f, String key) {
        if (key == null || f.properties() == null) return null;
        JsonElement e = f.getProperty(key);
        if (e == null || e.isJsonNull()) return null;
        String s = e.isJsonPrimitive() ? e.getAsString() : e.toString();
        return s.isEmpty() ? null : s;
    }

    public static Double number(Feature f, String key) {
        if (key == null || f.properties() == null) return null;
        JsonElement e = f.getProperty(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) return p.getAsDouble();
        if (p.isString()) {
            try {
                return Double.parseDouble(p.getAsString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** First template that fills to something non-blank, or null. */
    public static String first(java.util.List<String> templates, Feature f) {
        if (templates == null) return null;
        for (String t : templates) {
            String s = fill(t, f);
            if (s != null) return s;
        }
        return null;
    }

    /** Fills {field} placeholders; returns null if the result is blank. */
    public static String fill(String template, Feature f) {
        if (template == null) return null;
        Matcher m = FIELD.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = string(f, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(sb);
        String out = sb.toString().trim().replaceAll("\\s{2,}", " ");
        return out.isEmpty() ? null : out;
    }
}
