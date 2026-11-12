package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.vault.KeySession;
import io.github.wreazycs.panelcontroller.vault.KeyVault;
import io.github.wreazycs.panelcontroller.vault.Pin;
import io.github.wreazycs.panelcontroller.vault.VaultException;
import io.github.wreazycs.panelcontroller.vault.VaultWipedException;
import io.github.wreazycs.panelcontroller.vault.WrongPinException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Everything the user sees about the API key: the lock screen at start-up, the settings menu
 * (top right), and the key / PIN dialogs. The key itself is never shown, logged or copied;
 * only its last four characters appear.
 *
 * <p>{@code vault} is null on platforms without an OS protector: the key can then be kept for
 * the session only.
 */
final class VaultUi {
    /** Turkish button labels (the JavaFX defaults follow the system language). */
    static final ButtonType OK_BUTTON = new ButtonType("Tamam", ButtonBar.ButtonData.OK_DONE);
    static final ButtonType CANCEL_BUTTON = new ButtonType("Vazgeç", ButtonBar.ButtonData.CANCEL_CLOSE);

    private final Stage stage;
    private final KeyVault vault;
    private final KeySession session;
    private final ExecutorService background;
    private final Runnable onChange;
    private final Consumer<String> log;

    VaultUi(Stage stage, KeyVault vault, KeySession session, ExecutorService background,
            Runnable onChange, Consumer<String> log) {
        this.stage = stage;
        this.vault = vault;
        this.session = session;
        this.background = background;
        this.onChange = onChange;
        this.log = log;
    }

    boolean hasStoredKey() {
        return vault != null && vault.exists();
    }

    /** One line for the top bar. Never contains the key. */
    String statusText() {
        if (!session.hasKey()) {
            return hasStoredKey() ? "API anahtarı: kayıtlı (kilitli)" : "API anahtarı: yok";
        }
        return "API anahtarı: " + session.masked()
                + (session.isRemembered() ? " · kayıtlı, PIN korumalı" : " · yalnız bu oturumda");
    }

    // ------------------------------------------------------------ lock screen

    /** Shown instead of the main window when a key is stored. {@code onOpen} runs once the app may open. */
    Scene lockScene(Runnable onOpen) {
        Label title = new Label("Panel Controller");
        title.getStyleClass().add("lock-title");
        Label text = new Label("Kayıtlı API anahtarı var. Açmak için 4 haneli PIN'ini gir.");
        PasswordField pin = pinField();
        pin.getStyleClass().add("lock-pin");
        Button open = new Button("Aç");
        open.getStyleClass().add("primary");
        open.setDefaultButton(true);
        Label message = new Label();
        message.getStyleClass().add("error-text");
        Hyperlink forgot = new Hyperlink("PIN'imi unuttum");

        try {
            int failed = vault.failedAttempts();
            if (failed > 0) {
                message.setText("Önceki yanlış deneme: " + failed + ". Kalan: " + (KeyVault.MAX_ATTEMPTS - failed));
            }
        } catch (VaultException e) {
            message.setText(e.getMessage());
        }

        open.setOnAction(e -> {
            char[] p = pin.getText().toCharArray();
            pin.clear();
            if (!Pin.isWellFormed(p)) {
                message.setText("4 rakam gir.");
                return;
            }
            pin.setDisable(true);
            open.setDisable(true);
            message.setText("Kontrol ediliyor…");
            background.submit(() -> {
                try {
                    char[] key = vault.unlock(p);
                    session.set(key, true);
                    Arrays.fill(key, '\0');
                    Platform.runLater(onOpen);
                } catch (WrongPinException ex) {
                    Platform.runLater(() -> {
                        message.setText(ex.getMessage());
                        pin.setDisable(false);
                        open.setDisable(false);
                        pin.requestFocus();
                    });
                } catch (VaultWipedException ex) {
                    Platform.runLater(() -> {
                        info(ex.getMessage() + "\nUygulama kilitsiz açılıyor. Anahtarı ⚙ menüsünden yeniden girebilirsin.");
                        onOpen.run();
                    });
                } catch (VaultException ex) {
                    Platform.runLater(() -> unreadableVault(ex, onOpen));
                } finally {
                    Arrays.fill(p, '\0');
                }
            });
        });

        forgot.setOnAction(e -> {
            if (confirm("PIN'imi unuttum",
                    "Kayıtlı anahtar silinecek ve uygulama kilitsiz açılacak.\nAnahtarı yeniden girmen gerekecek.",
                    "Anahtarı sil ve aç")) {
                destroyQuietly();
                log.accept("Kayıtlı API anahtarı silindi (PIN unutuldu).");
                onOpen.run();
            }
        });

        VBox box = new VBox(12, title, text, pin, open, message, forgot);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        box.getStyleClass().addAll("app", "lock");
        Scene scene = new Scene(box, 1040, 720);
        Platform.runLater(pin::requestFocus);
        return scene;
    }

    private void unreadableVault(VaultException e, Runnable onOpen) {
        ButtonType delete = new ButtonType("Kasayı sil ve aç", ButtonBar.ButtonData.OK_DONE);
        ButtonType quit = new ButtonType("Çık", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.ERROR, e.getMessage(), delete, quit);
        a.setHeaderText("Kayıtlı anahtar açılamıyor");
        a.initOwner(stage);
        if (a.showAndWait().filter(b -> b == delete).isPresent()) {
            destroyQuietly();
            onOpen.run();
        } else {
            Platform.exit();
        }
    }

    // -------------------------------------------------------- settings menu

    /** The ⚙ button for the top-right corner. */
    Button settingsButton(Runnable lockNow) {
        Button gear = new Button("⚙");
        gear.getStyleClass().add("gear");
        gear.setTooltip(new Tooltip("Ayarlar"));

        MenuItem key = new MenuItem("API anahtarı…");
        key.setOnAction(e -> keyDialog());
        MenuItem change = new MenuItem("PIN'i değiştir…");
        change.setOnAction(e -> changePinDialog());
        MenuItem remove = new MenuItem("PIN'i kaldır…");
        remove.setOnAction(e -> removePinDialog());
        MenuItem lock = new MenuItem("Şimdi kilitle");
        lock.setOnAction(e -> lockNow.run());
        ContextMenu menu = new ContextMenu(key, new SeparatorMenuItem(), change, remove, new SeparatorMenuItem(), lock);
        menu.setOnShowing(e -> {
            boolean stored = hasStoredKey();
            change.setDisable(!stored);
            remove.setDisable(!stored);
            lock.setDisable(!stored);
        });
        gear.setOnAction(e -> menu.show(gear, javafx.geometry.Side.BOTTOM, 0, 4));
        return gear;
    }

    // ------------------------------------------------------------ key dialog

    void keyDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("API anahtarı");
        d.setHeaderText("Şu an: " + (session.hasKey()
                ? session.masked() + (session.isRemembered() ? " (bu bilgisayarda kayıtlı)" : " (yalnız bu oturumda)")
                : hasStoredKey() ? "kayıtlı (kilitli)" : "anahtar yok"));

        PasswordField keyField = new PasswordField();
        keyField.setPromptText(session.hasKey() ? "Değiştirmek için yeni anahtarı yapıştır (boş: aynı kalır)"
                : "API anahtarını yapıştır");
        keyField.setPrefColumnCount(36);
        CheckBox remember = new CheckBox("Bu bilgisayarda hatırla (her açılışta PIN sorulur)");
        remember.setSelected(hasStoredKey());
        Label note = new Label(vault == null
                ? "Kalıcı saklama yalnız Windows'ta var; anahtar bu oturumda kalır."
                : "Anahtar ekrana, günlüğe ya da panoya hiç yazılmaz. İşaretsizse uygulama kapanınca silinir.");
        note.getStyleClass().add("hint");
        note.setWrapText(true);
        remember.setDisable(vault == null);
        Label error = new Label();
        error.getStyleClass().add("error-text");

        char[][] newPin = {null};
        remember.selectedProperty().addListener((obs, was, now) -> {
            if (now && !hasStoredKey() && newPin[0] == null) {
                Platform.runLater(() -> {
                    if (confirm("PIN gerekli",
                            "Anahtarı bu bilgisayarda saklamak için 4 haneli bir PIN belirlemen gerekir.\n"
                                    + "Uygulama her açılışta bu PIN'i soracak.", "PIN belirle")) {
                        Optional<char[]> pin = newPinDialog();
                        if (pin.isPresent()) {
                            newPin[0] = pin.get();
                            return;
                        }
                    }
                    remember.setSelected(false);
                });
            } else if (!now && newPin[0] != null) {
                Arrays.fill(newPin[0], '\0');
                newPin[0] = null;
            }
        });

        VBox body = new VBox(10, keyField, remember, note, error);
        body.setPrefWidth(460);
        d.getDialogPane().setContent(body);
        ButtonType save = new ButtonType("Kaydet", ButtonBar.ButtonData.OK_DONE);
        ButtonType forget = new ButtonType("Anahtarı unut", ButtonBar.ButtonData.LEFT);
        d.getDialogPane().getButtonTypes().add(save);
        if (session.hasKey() || hasStoredKey()) {
            d.getDialogPane().getButtonTypes().add(forget);
        }
        d.getDialogPane().getButtonTypes().add(CANCEL_BUTTON);
        d.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, ev -> {
            if (keyField.getText().isBlank() && !session.hasKey()) {
                error.setText("Anahtar boş.");
                ev.consume();
            } else if (remember.isSelected() && !hasStoredKey() && newPin[0] == null) {
                error.setText("Hatırlamak için önce PIN belirle (kutuyu yeniden işaretle).");
                ev.consume();
            }
        });
        Platform.runLater(keyField::requestFocus);

        Optional<ButtonType> result = d.showAndWait();
        char[] typed = keyField.getText().strip().toCharArray();
        keyField.clear();
        try {
            if (result.isEmpty() || result.get() == CANCEL_BUTTON) {
                return;
            }
            if (result.get() == forget) {
                if (confirm("Anahtarı unut", "API anahtarı bellekten ve bu bilgisayardan silinecek.", "Sil")) {
                    session.clear();
                    destroyQuietly();
                    log.accept("API anahtarı silindi.");
                }
                return;
            }
            char[] key = typed.length > 0 ? typed.clone() : session.copy().orElseThrow();
            if (!remember.isSelected()) {
                if (hasStoredKey() && !confirm("Kayıt silinsin mi?",
                        "PIN ve bu bilgisayardaki kayıt silinecek.\nAnahtar yalnız bu oturumda kalacak.", "Sil")) {
                    Arrays.fill(key, '\0');
                    return;
                }
                destroyQuietly();
                session.set(key, false);
                Arrays.fill(key, '\0');
                log.accept("API anahtarı yalnız bu oturum için tutuluyor.");
            } else if (!hasStoredKey()) {
                char[] pin = newPin[0];
                newPin[0] = null;
                runVault("Kaydediliyor", () -> {
                    vault.store(pin, key);
                    session.set(key, true);
                    return "API anahtarı bu bilgisayarda kaydedildi. Bir sonraki açılışta PIN sorulacak.";
                }, () -> {
                    Arrays.fill(pin, '\0');
                    Arrays.fill(key, '\0');
                });
            } else {
                Optional<char[]> pin = pinPrompt("Değişikliği onayla", "Mevcut PIN'ini gir.");
                if (pin.isEmpty()) {
                    Arrays.fill(key, '\0');
                    return;
                }
                char[] p = pin.get();
                runVault("Kaydediliyor", () -> {
                    char[] old = vault.unlock(p); // verifies (and counts) the PIN
                    Arrays.fill(old, '\0');
                    vault.store(p, key);
                    session.set(key, true);
                    return "Yeni API anahtarı kaydedildi.";
                }, () -> {
                    Arrays.fill(p, '\0');
                    Arrays.fill(key, '\0');
                });
            }
        } finally {
            Arrays.fill(typed, '\0');
            if (newPin[0] != null) {
                Arrays.fill(newPin[0], '\0');
            }
            onChange.run();
        }
    }

    // ------------------------------------------------------------ PIN dialogs

    private void changePinDialog() {
        PasswordField current = pinField();
        PasswordField next = pinField();
        PasswordField repeat = pinField();
        Label error = new Label();
        error.getStyleClass().add("error-text");
        GridPane grid = form(new String[] {"Mevcut PIN", "Yeni PIN", "Yeni PIN (tekrar)"}, current, next, repeat);
        grid.add(error, 0, 3, 2, 1);

        Dialog<ButtonType> d = pinDialog("PIN'i değiştir", "Yeni 4 haneli PIN belirle.", grid);
        d.getDialogPane().lookupButton(OK_BUTTON).addEventFilter(ActionEvent.ACTION, ev -> {
            String problem = !Pin.isWellFormed(current.getText().toCharArray()) ? "Mevcut PIN 4 rakam olmalı."
                    : newPinProblem(next, repeat);
            if (problem == null && next.getText().equals(current.getText())) {
                problem = "Yeni PIN eskisiyle aynı.";
            }
            if (problem != null) {
                error.setText(problem);
                ev.consume();
            }
        });
        Platform.runLater(current::requestFocus);
        boolean ok = d.showAndWait().filter(b -> b == OK_BUTTON).isPresent();
        char[] cur = current.getText().toCharArray();
        char[] nxt = next.getText().toCharArray();
        current.clear();
        next.clear();
        repeat.clear();
        if (!ok) {
            Arrays.fill(cur, '\0');
            Arrays.fill(nxt, '\0');
            return;
        }
        runVault("PIN değiştiriliyor", () -> {
            vault.changePin(cur, nxt);
            return "PIN değiştirildi.";
        }, () -> {
            Arrays.fill(cur, '\0');
            Arrays.fill(nxt, '\0');
        });
    }

    private void removePinDialog() {
        Optional<char[]> pin = pinPrompt("PIN'i kaldır",
                "PIN ve bu bilgisayardaki anahtar kaydı silinecek.\n"
                        + "Anahtar uygulama kapanana kadar bellekte kalır.\nOnaylamak için PIN'ini gir.");
        if (pin.isEmpty()) {
            return;
        }
        char[] p = pin.get();
        runVault("PIN kaldırılıyor", () -> {
            char[] key = vault.unlock(p);
            if (!session.hasKey()) {
                session.set(key, false);
            }
            Arrays.fill(key, '\0');
            vault.destroy();
            session.setRemembered(false);
            return "PIN ve kayıt silindi. Anahtar yalnız bu oturumda tutuluyor.";
        }, () -> Arrays.fill(p, '\0'));
    }

    /** Asks for a new PIN twice. */
    private Optional<char[]> newPinDialog() {
        PasswordField first = pinField();
        PasswordField second = pinField();
        Label error = new Label();
        error.getStyleClass().add("error-text");
        GridPane grid = form(new String[] {"PIN", "PIN (tekrar)"}, first, second);
        grid.add(error, 0, 2, 2, 1);
        Label rules = new Label("4 rakam. 1111 ya da 1234 gibi kolay tahmin edilenler kabul edilmez.\n"
                + KeyVault.MAX_ATTEMPTS + " yanlış girişte kayıtlı anahtar silinir.");
        rules.getStyleClass().add("hint");
        grid.add(rules, 0, 3, 2, 1);

        Dialog<ButtonType> d = pinDialog("PIN belirle", "Uygulamanın açılış PIN'i", grid);
        d.getDialogPane().lookupButton(OK_BUTTON).addEventFilter(ActionEvent.ACTION, ev -> {
            String problem = newPinProblem(first, second);
            if (problem != null) {
                error.setText(problem);
                ev.consume();
            }
        });
        Platform.runLater(first::requestFocus);
        boolean ok = d.showAndWait().filter(b -> b == OK_BUTTON).isPresent();
        char[] pin = first.getText().toCharArray();
        first.clear();
        second.clear();
        if (!ok) {
            Arrays.fill(pin, '\0');
            return Optional.empty();
        }
        return Optional.of(pin);
    }

    private Optional<char[]> pinPrompt(String title, String text) {
        PasswordField field = pinField();
        Label error = new Label();
        error.getStyleClass().add("error-text");
        VBox body = new VBox(8, field, error);
        Dialog<ButtonType> d = pinDialog(title, text, body);
        d.getDialogPane().lookupButton(OK_BUTTON).addEventFilter(ActionEvent.ACTION, ev -> {
            if (!Pin.isWellFormed(field.getText().toCharArray())) {
                error.setText("4 rakam gir.");
                ev.consume();
            }
        });
        Platform.runLater(field::requestFocus);
        boolean ok = d.showAndWait().filter(b -> b == OK_BUTTON).isPresent();
        char[] pin = field.getText().toCharArray();
        field.clear();
        if (!ok) {
            Arrays.fill(pin, '\0');
            return Optional.empty();
        }
        return Optional.of(pin);
    }

    private static String newPinProblem(PasswordField first, PasswordField second) {
        String problem = Pin.problem(first.getText().toCharArray());
        if (problem == null && !first.getText().equals(second.getText())) {
            problem = "İki PIN aynı değil.";
        }
        return problem;
    }

    private Dialog<ButtonType> pinDialog(String title, String header, Node content) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle(title);
        d.setHeaderText(header);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(OK_BUTTON, CANCEL_BUTTON);
        return d;
    }

    private static GridPane form(String[] labels, Node... fields) {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        for (int i = 0; i < fields.length; i++) {
            g.add(new Label(labels[i]), 0, i);
            g.add(fields[i], 1, i);
        }
        return g;
    }

    /** Digits only, at most four, shown as dots. */
    static PasswordField pinField() {
        PasswordField f = new PasswordField();
        f.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,4}") ? c : null));
        f.setPrefColumnCount(6);
        f.setPromptText("••••");
        f.getStyleClass().add("pin");
        return f;
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface VaultOp {
        String run() throws VaultException;
    }

    /** Runs a vault operation off the UI thread (key derivation takes a moment), then reports. */
    private void runVault(String busyText, VaultOp op, Runnable cleanup) {
        stage.getScene().getRoot().setDisable(true);
        log.accept(busyText + "…");
        background.submit(() -> {
            String message;
            boolean failed = true;
            try {
                message = op.run();
                failed = false;
            } catch (WrongPinException e) {
                message = e.getMessage();
            } catch (VaultWipedException e) {
                session.setRemembered(false);
                message = e.getMessage() + " Anahtar bu oturum kapanana kadar bellekte kalır.";
            } catch (VaultException | RuntimeException e) {
                message = e.getMessage();
            } finally {
                cleanup.run();
            }
            String text = message;
            boolean error = failed;
            Platform.runLater(() -> {
                stage.getScene().getRoot().setDisable(false);
                log.accept((error ? "✗ " : "✓ ") + text);
                onChange.run();
                if (error) {
                    info(text);
                }
            });
        });
    }

    private void destroyQuietly() {
        if (vault == null) {
            return;
        }
        try {
            vault.destroy();
        } catch (VaultException e) {
            info(e.getMessage());
        }
    }

    private void info(String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, message, OK_BUTTON);
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait();
    }

    private boolean confirm(String header, String text, String yes) {
        ButtonType go = new ButtonType(yes, ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, go, CANCEL_BUTTON);
        a.setHeaderText(header);
        a.initOwner(stage);
        return a.showAndWait().filter(b -> b == go).isPresent();
    }
}
