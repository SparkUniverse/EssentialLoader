package gg.essential.loader.stage2.components;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static gg.essential.loader.stage2.components.ButtonShadowBorder.X_SHADOW;
import static gg.essential.loader.stage2.components.ButtonShadowBorder.Y_SHADOW;

public class RestartUI implements EssentialStyle {
    private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();

    private final JFrame frame = makeFrame(it -> closedFuture.complete(null));

    private final List<String> mods;

    public RestartUI(List<String> mods) {
        this.mods = mods;
    }

    public void show() {
        final List<JLabel> htmlLabels = new ArrayList<>();

        final JPanel content = makeContent(frame);
        content.setBorder(new EmptyBorder(0, 60 - X_SHADOW, 0, 60 - X_SHADOW));
        content.add(Box.createHorizontalStrut(CONTENT_WIDTH));

        htmlLabels.add(makeTitle(content, html(centered("Please restart your game to automatically install Essential."))));

        final JLabel explanation = new JLabel(html(centered("The following mods require<br>Essential Mod's API:")), SwingConstants.CENTER);
        explanation.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));
        explanation.setForeground(COLOR_FOREGROUND);
        explanation.setAlignmentX(Container.CENTER_ALIGNMENT);
        if (Fonts.medium != null) {
            explanation.setFont(Fonts.medium.deriveFont(16F));
        }
        content.add(explanation);
        htmlLabels.add(explanation);

        content.add(Box.createVerticalStrut(19));

        final JPanel modList = new JPanel();
        modList.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));
        modList.setBackground(COLOR_BACKGROUND);
        modList.setLayout(new BoxLayout(modList, BoxLayout.Y_AXIS));
        content.add(modList);

        for (String modName : mods) {
            final JLabel text = new JLabel(html(centered(modName)), SwingConstants.CENTER);
            text.setForeground(COLOR_HIGHLIGHT);
            text.setAlignmentX(Container.CENTER_ALIGNMENT);
            if (Fonts.mediumItalic != null) {
                text.setFont(Fonts.mediumItalic.deriveFont(16F));
            }
            modList.add(text);
            htmlLabels.add(text);
        }

        content.add(Box.createVerticalStrut(32 - Y_SHADOW));

        final JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(makeButton("Quit Game", COLOR_PRIMARY_BUTTON, COLOR_BUTTON_HOVER, () -> closedFuture.complete(null)));
        content.add(buttons);

        content.add(Box.createVerticalStrut(32 - Y_SHADOW));

        frame.pack();

        htmlLabels.forEach(this::fixJLabelHeight);
        frame.pack();

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public void waitForClose() {
        closedFuture.join();
        close();
    }

    public void close() {
        frame.dispose();
    }

    public static void main(String[] args) {
        RestartUI ui = new RestartUI(List.of("Skytils"));
        ui.show();
        ui.waitForClose();
    }
}
