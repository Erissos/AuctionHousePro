package com.auctionhousepro.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class ItemSerializer {
    private ItemSerializer() {
    }

    public static String serialize(ItemStack itemStack) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeObject(itemStack);
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize ItemStack", exception);
        }
    }

    public static ItemStack deserialize(String base64) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(base64));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            return (ItemStack) dataInput.readObject();
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to deserialize ItemStack", exception);
        }
    }
}
