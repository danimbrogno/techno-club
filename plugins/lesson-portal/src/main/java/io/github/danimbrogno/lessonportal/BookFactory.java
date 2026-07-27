package io.github.danimbrogno.lessonportal;

import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

public final class BookFactory {

    private final NamespacedKey lessonKey;

    public BookFactory(JavaPlugin plugin) {
        this.lessonKey = new NamespacedKey(plugin, "lesson_id");
    }

    public ItemStack create(LessonDefinition lesson) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(lesson.title());
        meta.setAuthor(lesson.author());
        meta.addPages(Component.text("Techno Club lesson: " + lesson.id()));
        meta.getPersistentDataContainer().set(lessonKey, PersistentDataType.STRING, lesson.id());
        book.setItemMeta(meta);
        return book;
    }

    public Optional<String> readLessonId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(lessonKey, PersistentDataType.STRING);
        return Optional.ofNullable(id).filter(s -> !s.isBlank());
    }

    public boolean isLessonBook(ItemStack stack) {
        return readLessonId(stack).isPresent();
    }
}
