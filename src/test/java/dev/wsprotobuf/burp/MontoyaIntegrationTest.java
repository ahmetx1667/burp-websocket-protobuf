package dev.wsprotobuf.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.WebSocketMessageEditorProvider;
import dev.wsprotobuf.Extension;
import dev.wsprotobuf.ui.CodecService;
import dev.wsprotobuf.ui.EditorPanel;
import dev.wsprotobuf.ui.Edt;
import dev.wsprotobuf.ui.Workbench;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MontoyaIntegrationTest {
    @Test void registersWebSocketEditorAndClosesServiceOnUnload() {
        AtomicReference<WebSocketMessageEditorProvider> provider = new AtomicReference<>();
        AtomicReference<ExtensionUnloadingHandler> unload = new AtomicReference<>();
        Map<String, Object> observed = new HashMap<>();
        MontoyaApi api = api(provider, unload, observed);
        new Extension().initialize(api);
        assertEquals("WebSocket Protobuf", observed.get("name"));
        assertNotNull(provider.get());
        assertNotNull(observed.get("suiteTab"));
        var context = proxy(burp.api.montoya.ui.editor.extension.EditorCreationContext.class,
            (p, method, args) -> method.getName().equals("editorMode") ? EditorMode.READ_ONLY : null);
        var editor = provider.get().provideMessageEditor(context);
        assertEquals("Protobuf", editor.caption());
        assertFalse(editor.isEnabledFor(null));
        unload.get().extensionUnloaded();
        EditorPanel panel = (EditorPanel) editor.uiComponent();
        panel.load(Workbench.SAMPLE, true);
        assertTrue(panel.statusText().contains("unloaded"));
    }

    @Test void adapterReturnsOriginalBytesAndEnforcesReadOnlyMode() throws Exception {
        MontoyaApi api = api(new AtomicReference<>(), new AtomicReference<>(), new HashMap<>());
        try (CodecService service = new CodecService()) {
            var editor = new ProtobufWebSocketEditor(api, service, false, MontoyaIntegrationTest::bytes);
            WebSocketMessage message = proxy(WebSocketMessage.class,
                (p, method, args) -> method.getName().equals("payload") ? bytes(Workbench.SAMPLE) : null);
            assertTrue(editor.isEnabledFor(message));
            editor.setMessage(message);
            EditorPanel panel = (EditorPanel) editor.uiComponent();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!panel.isReady() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(panel.isReady());
            assertArrayEquals(Workbench.SAMPLE, editor.getMessage().getBytes());
            assertFalse(editor.isModified());
            assertNull(editor.selectedData());
            editor.setMessage(null);
            assertEquals(0, editor.getMessage().length());
        }
    }

    private static MontoyaApi api(AtomicReference<WebSocketMessageEditorProvider> provider,
                                 AtomicReference<ExtensionUnloadingHandler> unload,
                                 Map<String, Object> observed) {
        var extension = proxy(burp.api.montoya.extension.Extension.class, (p, method, args) -> {
            if (method.getName().equals("setName")) observed.put("name", args[0]);
            if (method.getName().equals("registerUnloadingHandler")) unload.set((ExtensionUnloadingHandler) args[0]);
            return null;
        });
        var ui = proxy(burp.api.montoya.ui.UserInterface.class, (p, method, args) -> {
            return switch (method.getName()) {
                case "currentEditorFont" -> new Font(Font.MONOSPACED, Font.PLAIN, 13);
                case "registerWebSocketMessageEditorProvider" -> {
                    provider.set((WebSocketMessageEditorProvider) args[0]); yield null;
                }
                case "registerSuiteTab" -> { observed.put("suiteTab", args[1]); yield null; }
                default -> null;
            };
        });
        var log = proxy(burp.api.montoya.logging.Logging.class, (p, method, args) -> null);
        return proxy(MontoyaApi.class, (p, method, args) -> switch (method.getName()) {
            case "extension" -> extension;
            case "userInterface" -> ui;
            case "logging" -> log;
            default -> null;
        });
    }

    private static ByteArray bytes(byte[] value) {
        byte[] copy = value.clone();
        return proxy(ByteArray.class, (p, method, args) -> switch (method.getName()) {
            case "getBytes" -> copy.clone();
            case "length" -> copy.length;
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
