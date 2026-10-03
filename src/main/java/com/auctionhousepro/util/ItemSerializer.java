package com.auctionhousepro.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

public final class ItemSerializer {
    private static final String NBT_PREFIX = "nbt:";
    private ItemSerializer() {
    }

    public static String serialize(ItemStack itemStack) {
        // Paper's NBT serializer retains item components and includes DataVersion
        // so Minecraft can migrate items when the server is upgraded.
        return NBT_PREFIX + Base64.getEncoder().encodeToString(itemStack.serializeAsBytes());
    }

    public static ItemStack deserialize(String base64) {
        if (base64.startsWith(NBT_PREFIX)) {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64.substring(NBT_PREFIX.length())));
        }
        // Existing database rows use Bukkit object serialization with wrapped Base64.
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getMimeDecoder().decode(base64));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            return (ItemStack) dataInput.readObject();
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to deserialize ItemStack", exception);
        }
    }
}
