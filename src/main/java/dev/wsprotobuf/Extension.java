package dev.wsprotobuf;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorMode;
import dev.wsprotobuf.burp.ProtobufWebSocketEditor;
import dev.wsprotobuf.ui.CodecService;
import dev.wsprotobuf.ui.Edt;
import dev.wsprotobuf.ui.Workbench;

/** The sole Montoya entry point. No traffic handlers or network requests are registered. */
public final class Extension implements BurpExtension {
    @Override public void initialize(MontoyaApi api) {
        CodecService service = new CodecService();
        try {
            api.extension().setName("WebSocket Protobuf");
            api.extension().registerUnloadingHandler(service::close);
            api.userInterface().registerWebSocketMessageEditorProvider(context ->
                new ProtobufWebSocketEditor(api, service,
                    context.editorMode() == EditorMode.DEFAULT));
            Edt.run(() -> {
                var workbench = Workbench.createPanel(service);
                api.userInterface().applyThemeToComponent(workbench);
                api.userInterface().registerSuiteTab("WebSocket Protobuf", workbench);
            });
            api.logging().logToOutput("WebSocket Protobuf 0.1.0 loaded. Pure Java; offline raw/gzip/gRPC "
                + "decode and edit. Wait for Ready before sending edits. Inferred types are not a .proto schema.");
        } catch (RuntimeException error) {
            service.close();
            api.logging().logToError("WebSocket Protobuf could not initialize: " + error.getMessage());
            throw error;
        }
    }
}
