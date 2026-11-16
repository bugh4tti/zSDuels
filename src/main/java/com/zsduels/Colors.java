package com.zsduels;

import net.md_5.bungee.api.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Colors {

    // Acepta "#RRGGBB" y "&#RRGGBB"
    private static final Pattern HEX = Pattern.compile("&?#([A-Fa-f0-9]{6})");

    private Colors() {}

    public static String color(String text) {
        if (text == null) return "";
        Matcher m = HEX.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(ChatColor.of("#" + m.group(1)).toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static String strip(String text) {
        return ChatColor.stripColor(color(text));
    }
}
