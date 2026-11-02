package gg.essential.loader.stage2.components;

import gg.essential.loader.stage2.jvm.ForkedJvm;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ForkedRestartUI {
    private final Logger LOGGER = LogManager.getLogger();
    private final List<String> mods;
    private ForkedJvm jvm;

    public ForkedRestartUI(List<String> mods) {
        this.mods = mods;
    }

    public void show() {
        try {
            this.jvm = new ForkedJvm(getClass());

            DataOutputStream out = new DataOutputStream(this.jvm.process.getOutputStream());
            for (String name : this.mods) {
                out.writeBoolean(true); // signal more entries
                out.writeUTF(name);
            }
            out.writeBoolean(false); // signal end of list
            out.flush();
        } catch (IOException e) {
            LOGGER.warn("Failed to fork JVM for RestartUI:", e);
        }
    }

    public void waitForClose() {
        if (this.jvm == null) return;

        try {
            this.jvm.process.getInputStream().read();
        } catch (IOException e) {
            LOGGER.warn("Failed to wait for RestartUI to close:", e);
        } finally {
            this.jvm.close();
            this.jvm = null;
        }
    }

    public static void main(String[] args) throws IOException {
        DataInputStream in = new DataInputStream(System.in);

        List<String> mods = new ArrayList<>();
        while(in.readBoolean()) {
            mods.add(in.readUTF());
        }

        try {
            RestartUI ui = new RestartUI(mods);
            ui.show();

            ui.waitForClose();
        } catch (Throwable t) {
            t.printStackTrace();
        }

        System.out.flush();
        System.out.close();
    }
}
