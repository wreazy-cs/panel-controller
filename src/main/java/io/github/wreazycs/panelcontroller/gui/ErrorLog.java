package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.vault.KeyVault;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Unexpected errors go to %LOCALAPPDATA%\PanelController\error.log so they never pass silently. */
final class ErrorLog {
    private ErrorLog() {}

    static Path file() {
        return KeyVault.defaultLocation().getParent().resolve("error.log");
    }

    static synchronized void write(String context, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String entry = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + "  " + context
                + "\n" + sw + "\n";
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // nowhere else to write
        }
    }
}
