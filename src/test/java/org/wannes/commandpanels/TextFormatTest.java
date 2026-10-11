package org.wannes.commandpanels;
import org.junit.Test;
import static org.junit.Assert.*;
public class TextFormatTest {
    @Test public void preservesLiteralAmpersandsAndLegacyAndHexFormatting() {
        var plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        var json=net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
        assertEquals("Salt & Pepper",plain.serialize(TextFormat.parse("Salt & Pepper")));
        assertTrue(json.serialize(TextFormat.parse("&aGreen &lBold")).contains("\"color\":\"green\""));
        assertTrue(json.serialize(TextFormat.parse("§cRed")).contains("\"color\":\"red\""));
        assertTrue(json.serialize(TextFormat.parse("&#123456Hex")).contains("#123456"));
        assertTrue(json.serialize(TextFormat.parse("§x§1§2§3§4§5§6Hex")).contains("#123456"));
        assertTrue(json.serialize(TextFormat.parse("&oItalic")).contains("\"italic\":true"));
    }
}
