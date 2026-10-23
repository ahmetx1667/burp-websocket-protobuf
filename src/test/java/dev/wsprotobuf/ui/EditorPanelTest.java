package dev.wsprotobuf.ui;

import dev.wsprotobuf.codec.PayloadCodec;
import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class EditorPanelTest {
    @Test void validEditsAreEncodedAndInvalidEditsCannotSend() throws Exception {
        try (CodecService service = new CodecService()) {
            EditorPanel panel = Edt.call(() -> new EditorPanel(service));
            panel.load(Workbench.SAMPLE, true);
            await(panel::isReady);
            assertArrayEquals(Workbench.SAMPLE, panel.bytesForSend());
            JTextArea message = find(panel, "Message JSON");
            Edt.run(() -> message.setText(message.getText().replace("hello", "world")));
            assertTrue(panel.isModified());
            assertThrows(IllegalStateException.class, panel::bytesForSend);
            await(panel::isValidated);
            var encoded = new PayloadCodec().decode(panel.bytesForSend());
            assertEquals("world", encoded.message().getAsJsonObject().get("2").getAsString());
            Edt.run(() -> message.setText("{\"2\": invalid}"));
            await(() -> panel.statusText().startsWith("Invalid edit:"));
            assertThrows(IllegalStateException.class, panel::bytesForSend);
            panel.load(Workbench.SAMPLE, true);
            await(panel::isReady);
            assertFalse(panel.isModified());
            assertArrayEquals(Workbench.SAMPLE, panel.bytesForSend());
        }
    }

    @Test void changingSelectionCannotApplyAnOlderDecode() throws Exception {
        try (CodecService service = new CodecService()) {
            EditorPanel panel = Edt.call(() -> new EditorPanel(service));
            panel.load(new byte[1_000_000], true);
            panel.load(Workbench.SAMPLE, true);
            await(panel::isReady);
            assertTrue(Edt.call(() -> find(panel, "Message JSON").getText().contains("hello")));
            assertArrayEquals(Workbench.SAMPLE, panel.bytesForSend());
        }
    }

    @Test void readOnlyAndUndecodableMessagesPreserveBytes() throws Exception {
        try (CodecService service = new CodecService()) {
            EditorPanel panel = Edt.call(() -> new EditorPanel(service));
            panel.load(Workbench.SAMPLE, false);
            await(panel::isReady);
            assertFalse(Edt.call(() -> find(panel, "Message JSON").isEditable()));
            assertFalse(Edt.call(() -> find(panel, "Inferred type definitions").isEditable()));
            byte[] malformed = {0};
            panel.load(malformed, true);
            await(() -> panel.statusText().startsWith("Cannot decode:"));
            assertFalse(panel.isModified());
            assertArrayEquals(malformed, panel.bytesForSend());
        }
    }

    @Test void unloadedServiceRejectsNewWork() {
        CodecService service = new CodecService();
        service.close();
        EditorPanel panel = Edt.call(() -> new EditorPanel(service));
        panel.load(Workbench.SAMPLE, true);
        assertTrue(panel.statusText().contains("unloaded"));
        assertArrayEquals(Workbench.SAMPLE, panel.bytesForSend());
    }

    public static JTextArea find(Container root, String accessibleName) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTextArea area
                && accessibleName.equals(area.getAccessibleContext().getAccessibleName())) return area;
            if (component instanceof Container container) {
                JTextArea found = find(container, accessibleName);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static void await(BooleanSupplier condition) throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (!condition.getAsBoolean()) Thread.sleep(15);
        });
    }
}
