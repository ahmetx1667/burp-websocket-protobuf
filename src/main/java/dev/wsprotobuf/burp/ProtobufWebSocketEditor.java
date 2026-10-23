package dev.wsprotobuf.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;
import dev.wsprotobuf.ui.CodecService;
import dev.wsprotobuf.ui.EditorPanel;
import dev.wsprotobuf.ui.Edt;

import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

public final class ProtobufWebSocketEditor implements ExtensionProvidedWebSocketMessageEditor {
    private final EditorPanel panel;
    private final boolean editable;
    private final Function<byte[], ByteArray> bytes;
    private final MontoyaApi api;

    public ProtobufWebSocketEditor(MontoyaApi api, CodecService service, boolean editable) {
        this(api, service, editable, ByteArray::byteArray);
    }

    /** Factory injection keeps adapter tests independent of Burp's internal object factory. */
    ProtobufWebSocketEditor(MontoyaApi api, CodecService service, boolean editable,
                           Function<byte[], ByteArray> bytes) {
        this.api = api;
        this.editable = editable;
        this.bytes = bytes;
        panel = Edt.call(() -> new EditorPanel(service));
        panel.setEditorFont(api.userInterface().currentEditorFont());
        Edt.run(() -> api.userInterface().applyThemeToComponent(panel));
    }

    @Override public ByteArray getMessage() {
        try { return bytes.apply(panel.bytesForSend()); }
        catch (IllegalStateException error) {
            api.logging().logToError(error.getMessage());
            throw error;
        }
    }

    @Override public void setMessage(WebSocketMessage message) {
        panel.load(message == null ? null : message.payload().getBytes(), editable);
    }

    @Override public boolean isEnabledFor(WebSocketMessage message) { return message != null; }
    @Override public String caption() { return "Protobuf"; }
    @Override public Component uiComponent() { return panel; }
    @Override public boolean isModified() { return panel.isModified(); }

    @Override public Selection selectedData() {
        String selected = panel.selectedText();
        return selected == null ? null : Selection.selection(bytes.apply(selected.getBytes(StandardCharsets.UTF_8)));
    }
}
