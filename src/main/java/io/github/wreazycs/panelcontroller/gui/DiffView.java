package io.github.wreazycs.panelcontroller.gui;

import io.github.wreazycs.panelcontroller.AppInfo;
import io.github.wreazycs.panelcontroller.Comparer;
import java.time.format.DateTimeFormatter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Shows what differs between the live file and the local one: lines only on the live site in red
 * ("-", lost when the new file is pasted), lines only in the local file in green ("+"), with a few
 * unchanged lines around each change.
 */
final class DiffView {
    private static final int MAX_SHOWN_CHARS = 300;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private DiffView() {}

    static void show(AppContext ctx, Comparer.FileResult f) {
        ListView<Comparer.Line> list = new ListView<>();
        list.getStyleClass().add("diff-list");
        list.setFocusTraversable(false);
        list.getItems().setAll(f.lines());
        list.setCellFactory(v -> new Cell());
        VBox.setVgrow(list, Priority.ALWAYS);

        HBox counts = new HBox(8,
                Ui.chip("+" + Ui.number(f.added()), "ok"),
                Ui.chip("-" + Ui.number(f.removed()), "err"),
                Ui.label(T.t("diff.sizes", Ui.number(f.liveChars()), Ui.number(f.localChars())), "muted", "small"));
        counts.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(6, Ui.label(f.name(), "section-title", "mono"), counts,
                Ui.wrapped(T.t("diff.legend"), "muted", "small"));
        if (ctx.lastCompareTime != null) {
            top.getChildren().add(Ui.label(T.t("diff.checked", ctx.lastCompareTime.format(HM)), "muted", "small"));
        }
        VBox body = new VBox(12, top, list);
        body.setPadding(new Insets(4, 0, 0, 0));
        body.setPrefSize(980, 560);

        Dialog<Void> d = new Dialog<>();
        d.initOwner(ctx.stage);
        d.setTitle(AppInfo.NAME + " - " + f.name());
        d.setHeaderText(null);
        d.setResizable(true);
        DialogPane pane = d.getDialogPane();
        pane.setContent(body);
        pane.getButtonTypes().add(new ButtonType(T.t("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        d.showAndWait();
    }

    /** Line numbers (live, local), the sign and the text; coloured by kind in the stylesheet. */
    private static final class Cell extends ListCell<Comparer.Line> {
        Cell() {
            setMouseTransparent(true); // nothing to select; wheel and scroll bar still work
        }

        @Override
        protected void updateItem(Comparer.Line l, boolean empty) {
            super.updateItem(l, empty);
            getStyleClass().removeAll("diff-add", "diff-del", "diff-gap");
            setText(null);
            if (empty || l == null) {
                setGraphic(null);
                return;
            }
            if (l.kind() == '~') {
                getStyleClass().add("diff-gap");
                setGraphic(Ui.label(T.t("diff.gap", l.text()), "muted", "small"));
                return;
            }
            if (l.kind() == '+') {
                getStyleClass().add("diff-add");
            } else if (l.kind() == '-') {
                getStyleClass().add("diff-del");
            }
            String text = l.text().length() > MAX_SHOWN_CHARS ? l.text().substring(0, MAX_SHOWN_CHARS) + "…" : l.text();
            HBox row = new HBox(8, number(l.live()), number(l.local()), Ui.label(String.valueOf(l.kind()), "mono", "strong"),
                    Ui.label(text, "mono"));
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }

        private static Region number(int n) {
            Label lb = Ui.label(n > 0 ? String.valueOf(n) : "", "mono", "muted", "small");
            lb.setMinWidth(44);
            lb.setPrefWidth(44);
            lb.setAlignment(Pos.CENTER_RIGHT);
            return lb;
        }
    }
}
