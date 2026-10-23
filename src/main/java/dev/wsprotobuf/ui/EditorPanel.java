package dev.wsprotobuf.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.wsprotobuf.codec.PayloadCodec;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Offline editor shared by Montoya and the standalone workbench. */
@SuppressWarnings("serial") // Swing panels are not persisted or serialized by this extension.
public final class EditorPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final CodecService service;
    private final PayloadCodec codec = new PayloadCodec();
    private final JTextArea message = area("Message JSON");
    private final JTextArea types = area("Inferred type definitions");
    private final JTextArea raw = area("Original bytes (hex)");
    private final JTabbedPane tabs = new JTabbedPane();
    private final JComboBox<PayloadCodec.Encoding> encoding = new JComboBox<>(PayloadCodec.Encoding.values());
    private final JLabel status = new JLabel("Load a WebSocket message or a sample file.");
    private final JButton validate = new JButton("Validate");
    private final JButton reinterpret = new JButton("Reinterpret types");
    private final Timer debounce;
    private volatile byte[] original = new byte[0];
    private volatile boolean modified;
    private volatile Validated validated;
    private volatile String problem;
    private volatile boolean ready;
    private long revision;
    private boolean replacing;
    private boolean editable;
    private boolean loaded;
    private String originalJson = "";
    private String originalTypes = "";
    private PayloadCodec.Decoded decoded;
    private Future<?> task;

    private record Validated(long revision, byte[] bytes) { }

    public EditorPanel(CodecService service) {
        super(new BorderLayout(0, 8));
        this.service = service;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel toolbar = new JPanel(new java.awt.GridLayout(0, 1, 0, 5));
        JPanel decodeRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        JPanel editRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        decodeRow.add(new JLabel("Payload:"));
        decodeRow.add(encoding);
        JButton decode = new JButton("Decode original");
        decodeRow.add(decode);
        editRow.add(validate);
        editRow.add(reinterpret);
        JButton reset = new JButton("Reset");
        editRow.add(reset);
        toolbar.add(decodeRow);
        toolbar.add(editRow);
        add(toolbar, BorderLayout.NORTH);
        tabs.addTab("Message", new JScrollPane(message));
        tabs.addTab("Types", new JScrollPane(types));
        tabs.addTab("Original hex", new JScrollPane(raw));
        raw.setEditable(false);
        add(tabs, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
        add(status, BorderLayout.SOUTH);
        debounce = new Timer(300, event -> validateDraft());
        debounce.setRepeats(false);
        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        };
        message.getDocument().addDocumentListener(listener);
        types.getDocument().addDocumentListener(listener);
        decode.addActionListener(event -> decodeOriginal(null));
        reset.addActionListener(event -> decodeOriginal(null));
        validate.addActionListener(event -> validateDraft());
        reinterpret.setToolTipText("Decode original bytes with these types; replaces message edits.");
        reinterpret.addActionListener(event -> decodeOriginal(types.getText()));
        encoding.setToolTipText("AUTO tries gzip, uncompressed gRPC, then raw protobuf.");
        controls(false);
    }

    private static JTextArea area(String name) {
        JTextArea area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(2);
        area.getAccessibleContext().setAccessibleName(name);
        return area;
    }

    public void setEditorFont(Font font) {
        if (font != null) Edt.run(() -> {
            message.setFont(font); types.setFont(font); raw.setFont(font);
        });
    }

    public void load(byte[] bytes, boolean canEdit) {
        byte[] copy = bytes == null ? new byte[0] : bytes.clone();
        Edt.run(() -> {
            original = copy;
            editable = canEdit;
            loaded = bytes != null;
            int count = Math.min(copy.length, 65_536);
            raw.setText(HexFormat.ofDelimiter(" ").formatHex(Arrays.copyOf(copy, count))
                + (count < copy.length ? "\n\nPreview limited to 64 KiB; full original is retained." : ""));
            raw.setCaretPosition(0);
            decodeOriginal(null);
        });
    }

    private void decodeOriginal(String typeOverride) {
        cancelTask();
        debounce.stop();
        long token = ++revision;
        modified = false;
        validated = null;
        problem = null;
        ready = false;
        decoded = null;
        controls(false);
        if (!loaded) {
            replaceText("", "");
            showStatus("No WebSocket message selected.");
            return;
        }
        byte[] input = original;
        PayloadCodec.Encoding mode = (PayloadCodec.Encoding) encoding.getSelectedItem();
        replaceText("", typeOverride == null ? "" : typeOverride);
        showStatus("Decoding " + input.length + " bytes…");
        try {
            task = service.submit(() -> {
                try {
                    PayloadCodec.Decoded result = typeOverride == null
                        ? codec.decode(input, mode) : codec.decode(input, mode, typeOverride);
                    String json = JSON.toJson(result.message());
                    String typeJson = JSON.toJson(result.types());
                    if ((long) json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + typeJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > PayloadCodec.MAX_DISPLAY_BYTES) {
                        throw new IllegalArgumentException("Formatted JSON exceeds the 16 MiB display limit");
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (token != revision || service.isClosed()) return;
                        decoded = result;
                        originalJson = json;
                        originalTypes = typeJson;
                        replaceText(json, typeJson);
                        ready = true;
                        controls(true);
                        showStatus(result.encoding() + " · " + input.length + " bytes · "
                            + (editable ? "Edit JSON, then wait for Ready before Send." : "Read-only message.")
                            + " Types are inferred; verify ambiguous fields.");
                    });
                } catch (Exception error) {
                    SwingUtilities.invokeLater(() -> {
                        if (token != revision || service.isClosed()) return;
                        problem = detail(error);
                        showStatus("Cannot decode: " + problem + " Original bytes are retained.");
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            showStatus("Decoder busy or unloaded. Try Decode original again.");
        }
    }

    private void changed() {
        if (replacing || !ready || !editable) return;
        revision++;
        modified = !message.getText().equals(originalJson) || !types.getText().equals(originalTypes);
        validated = null;
        problem = null;
        cancelTask();
        if (modified) {
            showStatus("Validating edits… Wait for Ready before Send.");
            debounce.restart();
        } else {
            debounce.stop();
            showStatus("Unchanged · original bytes will be used.");
        }
    }

    private void validateDraft() {
        debounce.stop();
        if (!ready || !editable || decoded == null) return;
        cancelTask();
        long token = revision;
        String json = message.getText();
        String typeJson = types.getText();
        PayloadCodec.Decoded baseline = decoded;
        validated = null;
        showStatus("Validating edits… Wait for Ready before Send.");
        try {
            task = service.submit(() -> {
                try {
                    byte[] result = codec.encode(baseline, json, typeJson);
                    SwingUtilities.invokeLater(() -> {
                        if (token != revision || service.isClosed()) return;
                        validated = new Validated(token, result);
                        problem = null;
                        showStatus("Ready · " + result.length + " bytes · "
                            + (Arrays.equals(original, result) ? "original bytes preserved." : "validated edits will be used on Send."));
                    });
                } catch (Exception error) {
                    SwingUtilities.invokeLater(() -> {
                        if (token != revision || service.isClosed()) return;
                        validated = null;
                        problem = detail(error);
                        showStatus("Invalid edit: " + problem + " Fix it or Reset before Send.");
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            problem = "Decoder busy or unloaded";
            showStatus(problem + ". Try Validate again.");
        }
    }

    /** Never blocks Burp's EDT, and never returns stale bytes for invalid edits. */
    public byte[] bytesForSend() {
        byte[] bytes = Edt.call(() -> {
            if (!modified) return original;
            Validated current = validated;
            if (current != null && current.revision() == revision) return current.bytes();
            String reason = problem;
            throw new IllegalStateException(reason == null
                ? "Protobuf edits are being validated. Wait for Ready or click Validate before Send."
                : "Invalid Protobuf edit: " + reason + ". Fix it or Reset before Send.");
        });
        return bytes.clone();
    }

    public boolean isModified() { return modified; }
    public boolean isReady() { return ready; }
    public boolean isValidated() { return validated != null; }
    public String statusText() { return Edt.call(status::getText); }

    public String selectedText() {
        return Edt.call(() -> {
            JTextArea area = switch (tabs.getSelectedIndex()) {
                case 1 -> types;
                case 2 -> raw;
                default -> message;
            };
            return area.getSelectedText();
        });
    }

    private void controls(boolean enabled) {
        message.setEditable(enabled && editable);
        types.setEditable(enabled && editable);
        validate.setEnabled(enabled && editable);
        reinterpret.setEnabled(enabled && editable);
    }

    private void replaceText(String json, String typeJson) {
        replacing = true;
        try {
            message.setText(json); types.setText(typeJson);
            message.setCaretPosition(0); types.setCaretPosition(0);
        } finally { replacing = false; }
    }

    private void cancelTask() {
        if (task != null) task.cancel(true);
        task = null;
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setToolTipText(text);
    }

    private static String detail(Exception error) {
        String text = error.getMessage();
        if (text == null || text.isBlank()) text = error.getClass().getSimpleName();
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.substring(0, Math.min(text.length(), 500));
    }
}
