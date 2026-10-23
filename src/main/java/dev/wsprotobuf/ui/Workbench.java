package dev.wsprotobuf.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.wsprotobuf.codec.PayloadCodec;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;

/** Standalone demo and file utility. Never sends data to a server. */
public final class Workbench {
    public static final byte[] SAMPLE = HexFormat.of().parseHex("089601120568656c6c6f1a020801");
    private static final int FILE_LIMIT = 8 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private Workbench() { }

    public static JPanel createPanel(CodecService service) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel heading = new JPanel(new BorderLayout());
        heading.add(new JLabel("WebSocket Protobuf · local file workbench"), BorderLayout.NORTH);
        heading.add(new JLabel("In Proxy / Repeater, select a WebSocket message and open its Protobuf tab."), BorderLayout.SOUTH);
        panel.add(heading, BorderLayout.NORTH);
        EditorPanel editor = new EditorPanel(service);
        panel.add(editor, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JButton sample = new JButton("Load sample");
        JButton open = new JButton("Open binary file…");
        JButton save = new JButton("Save encoded bytes…");
        actions.add(sample); actions.add(open); actions.add(save);
        panel.add(actions, BorderLayout.SOUTH);
        sample.addActionListener(event -> editor.load(SAMPLE, true));
        open.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(panel) != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath();
            try {
                service.submit(() -> {
                    try { editor.load(readBounded(path), true); }
                    catch (Exception error) { report(panel, error); }
                });
            } catch (RuntimeException error) { report(panel, error); }
        });
        save.addActionListener(event -> {
            try {
                byte[] encoded = editor.bytesForSend();
                JFileChooser chooser = new JFileChooser();
                if (chooser.showSaveDialog(panel) != JFileChooser.APPROVE_OPTION) return;
                Path path = chooser.getSelectedFile().toPath();
                if (Files.exists(path) && JOptionPane.showConfirmDialog(panel,
                        "Replace " + path.getFileName() + "?", "Save encoded bytes",
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                service.submit(() -> {
                    try { Files.write(path, encoded); }
                    catch (Exception error) { report(panel, error); }
                });
            } catch (RuntimeException error) { report(panel, error); }
        });
        editor.load(SAMPLE, true);
        return panel;
    }

    private static void report(JPanel parent, Exception error) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent, error.getMessage(),
            "WebSocket Protobuf", JOptionPane.ERROR_MESSAGE));
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            try { runCli(args); }
            catch (Exception error) {
                System.err.println("WebSocket Protobuf: " + error.getMessage());
                System.exit(2);
            }
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available. Use --decode FILE [auto|raw|gzip|grpc], or --help.");
            System.exit(2);
        }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* Swing's default theme remains usable. */ }
            CodecService service = new CodecService();
            JFrame frame = new JFrame("WebSocket Protobuf");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { service.close(); }
            });
            frame.setContentPane(createPanel(service));
            frame.setSize(1100, 760);
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        });
    }

    private static void runCli(String[] args) throws IOException {
        if (args[0].equals("--help")) {
            System.out.println("Usage:\n  java -jar EXTENSION.jar\n"
                + "  java -jar EXTENSION.jar --decode FILE [auto|raw|gzip|grpc]\n"
                + "  java -jar EXTENSION.jar --encode ORIGINAL MESSAGE.json TYPES.json OUTPUT [auto|raw|gzip|grpc]");
        } else if (args[0].equals("--decode") && (args.length == 2 || args.length == 3)) {
            PayloadCodec.Decoded decoded = new PayloadCodec().decode(readBounded(Path.of(args[1])),
                mode(args, 2));
            JsonObject output = new JsonObject();
            output.addProperty("encoding", decoded.encoding().name().toLowerCase(Locale.ROOT));
            output.add("message", decoded.message());
            output.add("types", decoded.types());
            System.out.println(JSON.toJson(output));
        } else if (args[0].equals("--encode") && (args.length == 5 || args.length == 6)) {
            PayloadCodec codec = new PayloadCodec();
            PayloadCodec.Decoded decoded = codec.decode(readBounded(Path.of(args[1])), mode(args, 5));
            String message = readJson(Path.of(args[2]));
            String types = readJson(Path.of(args[3]));
            Path output = Path.of(args[4]);
            if (Files.exists(output)) throw new IOException("Output already exists: " + output);
            Files.write(output, codec.encode(decoded, message, types), java.nio.file.StandardOpenOption.CREATE_NEW);
        } else {
            throw new IllegalArgumentException("Invalid arguments. Run with --help for usage.");
        }
    }

    private static PayloadCodec.Encoding mode(String[] args, int index) {
        return args.length > index ? PayloadCodec.Encoding.valueOf(args[index].toUpperCase(Locale.ROOT))
            : PayloadCodec.Encoding.AUTO;
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(FILE_LIMIT + 1);
            if (bytes.length > FILE_LIMIT) throw new IOException("Input exceeds 8 MiB");
            return bytes;
        }
    }

    private static String readJson(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IOException("JSON exceeds 16 MiB");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
