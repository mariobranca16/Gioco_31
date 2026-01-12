package it.gioco31.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class UrlUtil {
    private UrlUtil() {}

    public static String enc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
