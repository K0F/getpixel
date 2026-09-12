package org.getpixel.magnifier;

import java.util.ArrayList;
import java.util.List;

/** Ordered, de-duplicated palette of hex colours ("#rrggbb"). No Android deps. */
public final class Palette {

    public static final int MAX_SIZE = 12;
    public static final String SEPARATOR = ";";

    private final List<String> colors = new ArrayList<>();

    public int size() {
        return colors.size();
    }

    public String get(int index) {
        return colors.get(index);
    }

    /** Adds a colour, moving an existing duplicate to the end; caps the list. */
    public boolean add(String hex) {
        if (hex == null) return false;
        hex = normalize(hex);
        if (hex == null) return false;
        colors.remove(hex);
        colors.add(hex);
        trim();
        return true;
    }

    public boolean remove(String hex) {
        return colors.remove(hex);
    }

    public void clear() {
        colors.clear();
    }

    public boolean contains(String hex) {
        return colors.contains(normalize(hex));
    }

    public List<String> asList() {
        return new ArrayList<>(colors);
    }

    private void trim() {
        while (colors.size() > MAX_SIZE) {
            colors.remove(0);
        }
    }

    /** Lowcase hex or null. Accepts #RGB / #RRGGBB / RRGGBB. */
    public static String normalize(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 3) {
            String r = h.substring(0, 1);
            String g = h.substring(1, 2);
            String b = h.substring(2, 3);
            h = r + r + g + g + b + b;
        }
        if (h.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            char c = h.charAt(i);
            if (!isHex(c)) return null;
        }
        return '#' + h.toLowerCase();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Encodes to a compact string for SharedPreferences. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (String c : colors) {
            if (sb.length() > 0) sb.append(SEPARATOR);
            sb.append(c.substring(1)); // omit '#', store 6 hex chars
        }
        return sb.toString();
    }

    public static Palette decode(String encoded) {
        Palette p = new Palette();
        if (encoded == null || encoded.isEmpty()) return p;
        for (String part : encoded.split(SEPARATOR)) {
            if (part.isEmpty()) continue;
            String h = normalize(part);
            if (h != null) {
                p.add(h);
            }
        }
        return p;
    }
}