package io.github.wreazycs.panelcontroller;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Puts a file's text on the system clipboard, ready to paste into the admin panel. */
public final class Clipboard {
    private Clipboard() {}

    /** @return number of characters copied */
    public static int copyFile(Path file) throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("Pano kullanilamiyor (ekransiz ortam)");
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        return text.length();
    }
}
