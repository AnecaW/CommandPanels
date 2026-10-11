package org.wannes.commandpanels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class TextFormat {
    private static final LegacyComponentSerializer LEGACY=LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    static Component parse(String text) {
        var matcher=java.util.regex.Pattern.compile("&#([0-9a-fA-F]{6})").matcher(text);
        String normalized=matcher.replaceAll(match->{StringBuilder hex=new StringBuilder("§x");for(char digit:match.group(1).toCharArray())hex.append('§').append(digit);return hex.toString();});
        Component component=LEGACY.deserialize(org.bukkit.ChatColor.translateAlternateColorCodes('&',normalized));
        var italic=net.kyori.adventure.text.format.TextDecoration.ITALIC;
        return component.decoration(italic)==net.kyori.adventure.text.format.TextDecoration.State.NOT_SET?component.decoration(italic,false):component;
    }
}
