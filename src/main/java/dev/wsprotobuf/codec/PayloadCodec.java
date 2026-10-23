package dev.wsprotobuf.codec;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Schema-less, bounded Protobuf editing. No generated classes or native tools are required.
 * Numeric field names and explicit type definitions are used instead of guessed schema names.
 * Inference is necessarily ambiguous; select bytes or supply a typedef for ambiguous values.
 */
public final class PayloadCodec {
    public static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    public static final int MAX_DISPLAY_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_FIELDS = 100_000;
    public static final int MAX_GRPC_RECORDS = 1024;
    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger I64_MIN = BigInteger.ONE.shiftLeft(63).negate();
    private static final BigInteger I64_MAX = BigInteger.ONE.shiftLeft(63).subtract(BigInteger.ONE);
    private static final BigInteger U32_MAX = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
    private static final Set<String> TYPES = Set.of("uint", "int", "sint", "uint32", "int32", "sint32",
        "uint64", "int64", "sint64", "fixed32", "sfixed32", "float", "fixed64", "sfixed64", "double",
        "string", "bytes", "message");

    public enum Encoding { AUTO, RAW, GZIP, GRPC }

    /** Returned JSON and byte arrays are defensive copies. Keep this baseline for exact no-op output. */
    public static final class Decoded {
        private final byte[] original;
        private final Encoding encoding;
        private final JsonElement message;
        private final JsonElement types;
        private final List<Node> nodes;
        private Decoded(byte[] original, Encoding encoding, List<Node> nodes) {
            this.original = original.clone(); this.encoding = encoding; this.nodes = List.copyOf(nodes);
            if (nodes.size() == 1) {
                message = nodes.get(0).message.deepCopy(); types = nodes.get(0).types.deepCopy();
            } else {
                JsonArray messages = new JsonArray(), typedefs = new JsonArray();
                for (Node node : nodes) { messages.add(node.message.deepCopy()); typedefs.add(node.types.deepCopy()); }
                message = messages; types = typedefs;
            }
            if ((long) message.toString().getBytes(StandardCharsets.UTF_8).length
                + types.toString().getBytes(StandardCharsets.UTF_8).length > MAX_DISPLAY_BYTES)
                throw error("Decoded JSON exceeds the 16 MiB display limit");
        }
        public byte[] original() { return original.clone(); }
        public Encoding encoding() { return encoding; }
        public JsonElement message() { return message.deepCopy(); }
        public JsonElement types() { return types.deepCopy(); }
    }

    /** All invalid input and limit failures use this unchecked, user-displayable exception. */
    public static final class CodecException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public CodecException(String message) { super(message); }
        public CodecException(String message, Throwable cause) { super(message, cause); }
    }

    public Decoded decode(byte[] input) { return decode(input, Encoding.AUTO); }
    public Decoded decode(byte[] input, Encoding encoding) { return decodeInternal(input, encoding, null); }

    /** Reinterpret bytes with explicit JSON typedefs; omitted fields keep their inferred type.
     * For a multi-record gRPC body the typedef JSON must be an equally sized array.
     */
    public Decoded decode(byte[] input, Encoding encoding, String typesJson) {
        return decodeInternal(input, encoding, parseJson(typesJson));
    }

    private Decoded decodeInternal(byte[] input, Encoding encoding, JsonElement supplied) {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(encoding, "encoding");
        limit(input.length, MAX_PAYLOAD_BYTES, "Payload exceeds 8 MiB");
        input = input.clone(); // Parsed slices never alias a caller-owned, mutable array.
        if (encoding == Encoding.AUTO) {
            if (input.length >= 2 && (input[0] & 255) == 31 && (input[1] & 255) == 139) {
                try { return decodeWrapped(input, Encoding.GZIP, supplied); }
                catch (CodecException ex) { if (Thread.currentThread().isInterrupted()) throw ex; }
            }
            if (looksGrpc(input)) {
                try { return decodeWrapped(input, Encoding.GRPC, supplied); }
                catch (CodecException ex) { if (Thread.currentThread().isInterrupted()) throw ex; }
            }
            return decodeWrapped(input, Encoding.RAW, supplied);
        }
        return decodeWrapped(input, encoding, supplied);
    }

    private Decoded decodeWrapped(byte[] input, Encoding encoding, JsonElement supplied) {
        List<byte[]> bodies = switch (encoding) {
            case RAW -> List.of(input);
            case GZIP -> List.of(ungzip(input));
            case GRPC -> grpcBodies(input);
            default -> throw error("AUTO is not a concrete payload encoding");
        };
        if (supplied != null) {
            if (bodies.size() == 1) requireObject(supplied, "Typedef");
            else if (!supplied.isJsonArray() || supplied.getAsJsonArray().size() != bodies.size())
                throw error("Multi-record gRPC typedefs must have one object for each record");
        }
        Budget budget = new Budget();
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            JsonObject types = supplied == null ? null : requireObject(bodies.size() == 1 ? supplied : supplied.getAsJsonArray().get(i), "Typedef");
            if (types != null) validateTypes(types, 0, new Budget());
            nodes.add(render(parseWire(bodies.get(i), budget, 0), types, budget, 0));
        }
        return new Decoded(input, encoding, nodes);
    }

    public byte[] encode(Decoded baseline, String messageJson, String typesJson) {
        Objects.requireNonNull(baseline, "baseline");
        JsonElement message = parseJson(messageJson), types = parseJson(typesJson);
        List<JsonObject> messages = new ArrayList<>(), typedefs = new ArrayList<>();
        if (baseline.nodes.size() == 1) {
            messages.add(requireObject(message, "Message")); typedefs.add(requireObject(types, "Typedef"));
        } else {
            if (!message.isJsonArray() || !types.isJsonArray()
                || message.getAsJsonArray().size() != baseline.nodes.size()
                || types.getAsJsonArray().size() != baseline.nodes.size())
                throw error("gRPC editing must preserve the number of records");
            for (int i = 0; i < baseline.nodes.size(); i++) {
                messages.add(requireObject(message.getAsJsonArray().get(i), "Message"));
                typedefs.add(requireObject(types.getAsJsonArray().get(i), "Typedef"));
            }
        }
        Budget validationBudget = new Budget();
        for (int i = 0; i < messages.size(); i++) {
            validateTypes(typedefs.get(i), 0, new Budget());
            validateMessage(messages.get(i), typedefs.get(i), validationBudget, 0);
        }
        if (sameValue(message, baseline.message) && types.equals(baseline.types)) return baseline.original();
        Budget budget = new Budget();
        List<byte[]> bodies = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            validateTypes(typedefs.get(i), 0, new Budget());
            byte[] body = encodeNode(baseline.nodes.get(i), messages.get(i), typedefs.get(i), budget, 0);
            total += body.length;
            limit(total, MAX_PAYLOAD_BYTES, "Encoded bodies exceed 8 MiB");
            bodies.add(body);
        }
        byte[] result = switch (baseline.encoding) {
            case RAW -> bodies.get(0);
            case GZIP -> gzip(bodies.get(0));
            case GRPC -> wrapGrpc(bodies);
            default -> throw error("Invalid baseline encoding");
        };
        limit(result.length, MAX_PAYLOAD_BYTES, "Encoded payload exceeds 8 MiB");
        return result;
    }

    private record Field(int number, int wire, byte[] source, int rawStart, int rawLength, int dataStart, int dataLength) {
        byte[] data() { return Arrays.copyOfRange(source, dataStart, dataStart + dataLength); }
        void writeRaw(LimitedOutput out) { out.write(source, rawStart, rawLength); }
    }
    private static final class Node {
        final List<Field> fields;
        final JsonObject message, types;
        final IdentityHashMap<Field, Node> children;
        Node(List<Field> fields, JsonObject message, JsonObject types, IdentityHashMap<Field, Node> children) {
            this.fields = fields; this.message = message; this.types = types; this.children = children;
        }
    }
    private static final class Budget {
        int fields;
        void field(int depth) {
            checkCancelled();
            if (depth > MAX_DEPTH) throw error("Message nesting exceeds 64 levels");
            if (++fields > MAX_FIELDS) throw error("Message exceeds the 100000-field limit");
        }
    }
    private static List<Field> parseWire(byte[] bytes, Budget budget, int depth) {
        return parseWire(bytes, 0, bytes.length, budget, depth);
    }
    private static List<Field> parseWire(Field field, Budget budget, int depth) {
        return parseWire(field.source, field.dataStart, field.dataLength, budget, depth);
    }
    private static List<Field> parseWire(byte[] bytes, int offset, int length, Budget budget, int depth) {
        if (depth > MAX_DEPTH) throw error("Message nesting exceeds 64 levels");
        List<Field> fields = new ArrayList<>();
        Cursor c = new Cursor(bytes, offset, length);
        while (c.hasRemaining()) {
            budget.field(depth);
            int start = c.pos;
            BigInteger tag = c.varint();
            if (tag.bitLength() > 32) throw error("Protobuf tag exceeds uint32");
            long tagLong = tag.longValue();
            int number = (int) (tagLong >>> 3), wire = (int) (tagLong & 7);
            validateNumber(number);
            int dataStart = c.pos, dataLength;
            switch (wire) {
                case 0 -> { c.varint(); dataLength = c.pos - dataStart; }
                case 1 -> { dataLength = 8; c.skip(dataLength); }
                case 2 -> {
                    BigInteger size = c.varint();
                    if (size.compareTo(BigInteger.valueOf(MAX_PAYLOAD_BYTES)) > 0) throw error("Length-delimited field exceeds 8 MiB");
                    dataStart = c.pos; dataLength = size.intValue(); c.skip(dataLength);
                }
                case 5 -> { dataLength = 4; c.skip(dataLength); }
                case 3, 4 -> throw error("Deprecated Protobuf groups are unsupported");
                default -> throw error("Invalid Protobuf wire type " + wire);
            }
            fields.add(new Field(number, wire, bytes, start, c.pos - start, dataStart, dataLength));
        }
        return List.copyOf(fields);
    }

    private static Node render(List<Field> fields, JsonObject supplied, Budget budget, int depth) {
        LinkedHashMap<Integer, List<Field>> groups = group(fields);
        JsonObject message = new JsonObject(), types = new JsonObject();
        // Internal trees share immutable descendants; repeated deep copies here would
        // multiply a large child typedef by every level of its ancestor chain.
        if (supplied != null) for (var entry : supplied.entrySet()) types.add(entry.getKey(), entry.getValue());
        IdentityHashMap<Field, Node> children = new IdentityHashMap<>();
        for (var entry : groups.entrySet()) {
            String key = entry.getKey().toString(); List<Field> occurrences = entry.getValue();
            JsonObject definition;
            Inference inference = null;
            if (types.has(key)) definition = requireObject(types.get(key), "Field " + key + " type");
            else { inference = infer(occurrences, budget, depth); definition = inference.definition; types.add(key, definition); }
            String type = definition.get("type").getAsString();
            String scalar = type.startsWith("packed_") ? type.substring(7) : type;
            boolean numeric = wireFor(scalar) != 2;
            boolean array = type.startsWith("packed_")
                || (numeric && occurrences.stream().anyMatch(field -> field.wire == 2));
            JsonArray values = new JsonArray();
            for (Field field : occurrences) {
                if (inference != null && inference.children.containsKey(field)) {
                    Node child = inference.children.get(field); children.put(field, child); values.add(child.message);
                } else if (numeric && field.wire == 2) {
                    for (JsonElement value : readPacked(field, scalar, budget, depth)) values.add(value);
                } else values.add(readValue(field, scalar, definition, children, budget, depth));
            }
            message.add(key, !array && values.size() == 1 ? values.get(0) : values);
        }
        return new Node(fields, message, types, children);
    }

    private record Inference(JsonObject definition, IdentityHashMap<Field, Node> children) {}
    private static Inference infer(List<Field> fields, Budget budget, int depth) {
        int scalarWire = -1;
        boolean packed = false;
        for (Field field : fields) {
            if (field.wire == 2) packed = true;
            else if (scalarWire == -1) scalarWire = field.wire;
            else if (field.wire != scalarWire)
                throw error("Field " + field.number + " mixes incompatible numeric wire types");
        }
        String type = switch (scalarWire) { case 0 -> "uint"; case 1 -> "fixed64"; case 5 -> "fixed32"; default -> "bytes"; };
        // A scalar record identifies the numeric wire type of adjacent packed records.
        // Signedness and floating-point interpretation still require explicit types.
        if (packed && scalarWire != -1) return new Inference(type("packed_" + type), new IdentityHashMap<>());
        if (packed) {
            boolean strings = true;
            for (Field field : fields) if (!printableUtf8(field)) { strings = false; break; }
            if (strings) type = "string";
            else {
                int saved = budget.fields;
                JsonObject merged = new JsonObject();
                IdentityHashMap<Field, Node> children = new IdentityHashMap<>();
                boolean nested = depth < MAX_DEPTH;
                try {
                    for (Field field : fields) {
                        if (field.dataLength == 0) { nested = false; break; }
                        Node node = render(parseWire(field, budget, depth + 1), null, budget, depth + 1);
                        children.put(field, node);
                        if (!mergeTypes(merged, node.types)) { nested = false; break; }
                    }
                } catch (CodecException ex) { if (Thread.currentThread().isInterrupted()) throw ex; nested = false; }
                if (nested) { JsonObject definition = type("message"); definition.add("message_typedef", merged); return new Inference(definition, children); }
                budget.fields = saved;
            }
        }
        return new Inference(type(type), new IdentityHashMap<>());
    }
    private static boolean mergeTypes(JsonObject target, JsonObject source) {
        for (var entry : source.entrySet()) {
            if (!target.has(entry.getKey())) target.add(entry.getKey(), entry.getValue());
            else if (!target.get(entry.getKey()).equals(entry.getValue())) return false;
        }
        return true;
    }
    private static JsonElement readValue(Field field, String type, JsonObject definition,
                                         IdentityHashMap<Field, Node> children, Budget budget, int depth) {
        int expected = wireFor(type);
        if (field.wire != expected) throw error("Field " + field.number + " has wire type " + field.wire + "; " + type + " requires " + expected);
        if (type.equals("message")) {
            Node child = render(parseWire(field, budget, depth + 1), requireObject(definition.get("message_typedef"), "Nested typedef"), budget, depth + 1);
            definition.add("message_typedef", child.types);
            children.put(field, child); return child.message;
        }
        if (type.equals("string")) {
            try { return new JsonPrimitive(utf8(field.source, field.dataStart, field.dataLength)); }
            catch (CharacterCodingException ex) { throw error("Field " + field.number + " is not valid UTF-8"); }
        }
        if (type.equals("bytes")) return new JsonPrimitive(Base64.getEncoder().encodeToString(field.data()));
        return readNumber(field.data(), type);
    }
    private static JsonElement readNumber(byte[] data, String type) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case "float" -> floating(b.getFloat());
            case "double" -> floating(b.getDouble());
            case "fixed32" -> new JsonPrimitive(Integer.toUnsignedLong(b.getInt()));
            case "sfixed32" -> new JsonPrimitive(b.getInt());
            case "fixed64" -> new JsonPrimitive(unsigned(b.getLong()).toString());
            case "sfixed64" -> new JsonPrimitive(Long.toString(b.getLong()));
            default -> {
                BigInteger value = new Cursor(data).varint();
                if (type.equals("uint32")) { integerRange(value, BigInteger.ZERO, U32_MAX, type); yield new JsonPrimitive(value.longValue()); }
                if (type.equals("int32")) { long signed = value.longValue(); if (signed < Integer.MIN_VALUE || signed > Integer.MAX_VALUE) throw error("int32 wire value is out of range"); yield new JsonPrimitive((int) signed); }
                if (type.equals("sint32")) { integerRange(value, BigInteger.ZERO, U32_MAX, type); yield new JsonPrimitive(unzigzag(value).intValue()); }
                if (type.equals("int") || type.equals("int64")) yield new JsonPrimitive(Long.toString(value.longValue()));
                if (type.equals("sint") || type.equals("sint64")) yield new JsonPrimitive(unzigzag(value).toString());
                yield new JsonPrimitive(value.toString());
            }
        };
    }
    private static JsonPrimitive floating(double value) {
        if (!Double.isFinite(value)) return new JsonPrimitive(Double.isNaN(value) ? "NaN" : value > 0 ? "Infinity" : "-Infinity");
        return new JsonPrimitive(value);
    }
    private static JsonArray readPacked(Field field, String type, Budget budget, int depth) {
        if (field.wire != 2) throw error("Packed fields require wire type 2");
        Cursor c = new Cursor(field.source, field.dataStart, field.dataLength); JsonArray result = new JsonArray();
        int wire = wireFor(type);
        while (c.hasRemaining()) {
            budget.field(depth);
            byte[] data;
            if (wire == 0) { int start = c.pos; c.varint(); data = Arrays.copyOfRange(c.bytes, start, c.pos); }
            else data = c.take(wire == 1 ? 8 : 4);
            result.add(readNumber(data, type));
        }
        return result;
    }

    private static byte[] encodeNode(Node baseline, JsonObject message, JsonObject types, Budget budget, int depth) {
        if (depth > MAX_DEPTH) throw error("Message nesting exceeds 64 levels");
        LinkedHashMap<Integer, List<Field>> original = baseline == null ? new LinkedHashMap<>() : group(baseline.fields);
        LinkedHashMap<Integer, byte[]> encoded = new LinkedHashMap<>();
        for (var entry : message.entrySet()) {
            int number = fieldNumber(entry.getKey());
            if (!types.has(entry.getKey())) throw error("Field " + number + " needs an explicit type definition");
            JsonObject definition = requireObject(types.get(entry.getKey()), "Field typedef");
            String type = definition.get("type").getAsString();
            JsonElement prior = baseline == null ? null : baseline.message.get(entry.getKey());
            JsonElement priorType = baseline == null ? null : baseline.types.get(entry.getKey());
            List<Field> old = original.getOrDefault(number, List.of());
            LimitedOutput out = new LimitedOutput();
            if (prior != null && sameValue(prior, entry.getValue()) && definition.equals(priorType)) {
                for (Field field : old) { budget.field(depth); field.writeRaw(out); }
            } else if (type.startsWith("packed_")) {
                if (!entry.getValue().isJsonArray()) throw error("Packed field " + number + " must be an array");
                LimitedOutput packed = new LimitedOutput();
                for (JsonElement value : entry.getValue().getAsJsonArray()) { budget.field(depth); packed.writeBytes(writeNumber(value, type.substring(7))); }
                if (!entry.getValue().getAsJsonArray().isEmpty()) out.writeBytes(wrapField(number, 2, packed.toByteArray()));
            } else {
                List<JsonElement> values = values(entry.getValue());
                List<JsonElement> oldValues = prior == null ? List.of() : values(prior);
                // One packed record can contain several values (or none), so it cannot
                // be reused as a single expanded value when only part of a field changes.
                boolean align = values.size() == old.size() && oldValues.size() == old.size()
                    && old.stream().allMatch(field -> field.wire == wireFor(type));
                for (int i = 0; i < values.size(); i++) {
                    budget.field(depth);
                    Field oldField = align ? old.get(i) : null;
                    JsonElement value = values.get(i);
                    if (oldField != null && definition.equals(priorType) && sameValue(value, oldValues.get(i))) oldField.writeRaw(out);
                    else {
                        Node child = oldField != null && baseline != null ? baseline.children.get(oldField) : null;
                        byte[] data;
                        if (type.equals("message")) data = encodeNode(child, requireObject(value, "Nested message"), requireObject(definition.get("message_typedef"), "Nested typedef"), budget, depth + 1);
                        else if (type.equals("string")) data = strictString(value, "string").getBytes(StandardCharsets.UTF_8);
                        else if (type.equals("bytes")) {
                            try { data = Base64.getDecoder().decode(strictString(value, "base64 bytes")); }
                            catch (IllegalArgumentException ex) { throw error("Field " + number + " is not valid base64"); }
                        } else data = writeNumber(value, type);
                        out.writeBytes(wrapField(number, wireFor(type), data));
                    }
                }
            }
            encoded.put(number, out.toByteArray());
        }
        LimitedOutput result = new LimitedOutput(); Set<Integer> emitted = new HashSet<>();
        if (baseline != null) {
            // Keep original interleaving for fields whose values and definitions have not changed.
            Set<Integer> unchanged = new HashSet<>();
            for (var e : message.entrySet()) {
                if (baseline.message.has(e.getKey()) && sameValue(baseline.message.get(e.getKey()), e.getValue())
                    && Objects.equals(baseline.types.get(e.getKey()), types.get(e.getKey()))) unchanged.add(fieldNumber(e.getKey()));
            }
            for (Field field : baseline.fields) {
                if (unchanged.contains(field.number)) { field.writeRaw(result); emitted.add(field.number); }
                else if (emitted.add(field.number) && encoded.containsKey(field.number)) result.writeBytes(encoded.get(field.number));
            }
        }
        for (var entry : encoded.entrySet()) if (emitted.add(entry.getKey())) result.writeBytes(entry.getValue());
        return result.toByteArray();
    }

    private static byte[] writeNumber(JsonElement value, String type) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        if (type.equals("float") || type.equals("double")) {
            double n = floatingValue(value);
            if (type.equals("float")) {
                float f = (float) n;
                if (Double.isFinite(n) && !Float.isFinite(f)) throw error("float is out of range");
                b.putFloat(f); return Arrays.copyOf(b.array(), 4);
            }
            b.putDouble(n); return b.array();
        }
        BigInteger n = integer(value, type);
        switch (type) {
            case "uint", "uint64" -> integerRange(n, BigInteger.ZERO, U64_MAX, type);
            case "int", "int64" -> { integerRange(n, I64_MIN, I64_MAX, type); n = unsigned(n.longValue()); }
            case "sint", "sint64" -> { integerRange(n, I64_MIN, I64_MAX, type); n = n.shiftLeft(1).xor(n.shiftRight(63)); }
            case "uint32" -> integerRange(n, BigInteger.ZERO, U32_MAX, type);
            case "int32" -> { integerRange(n, BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE), type); n = unsigned(n.longValue()); }
            case "sint32" -> { integerRange(n, BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE), type); n = n.shiftLeft(1).xor(n.shiftRight(31)); }
            case "fixed32" -> { integerRange(n, BigInteger.ZERO, U32_MAX, type); b.putInt(n.intValue()); return Arrays.copyOf(b.array(), 4); }
            case "sfixed32" -> { integerRange(n, BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE), type); b.putInt(n.intValue()); return Arrays.copyOf(b.array(), 4); }
            case "fixed64" -> { integerRange(n, BigInteger.ZERO, U64_MAX, type); b.putLong(n.longValue()); return b.array(); }
            case "sfixed64" -> { integerRange(n, I64_MIN, I64_MAX, type); b.putLong(n.longValue()); return b.array(); }
            default -> throw error("Unsupported numeric type " + type);
        }
        return varint(n);
    }

    private static void validateMessage(JsonObject message, JsonObject types, Budget budget, int depth) {
        if (depth > MAX_DEPTH) throw error("Message nesting exceeds 64 levels");
        for (var entry : message.entrySet()) {
            int number = fieldNumber(entry.getKey());
            if (!types.has(entry.getKey())) throw error("Field " + number + " needs an explicit type definition");
            JsonObject definition = requireObject(types.get(entry.getKey()), "Field typedef");
            String type = definition.get("type").getAsString();
            boolean packed = type.startsWith("packed_");
            if (packed && !entry.getValue().isJsonArray()) throw error("Packed field " + number + " must be an array");
            if (packed) type = type.substring(7);
            for (JsonElement value : values(entry.getValue())) {
                budget.field(depth);
                switch (type) {
                    case "message" -> validateMessage(requireObject(value, "Nested message"), requireObject(definition.get("message_typedef"), "Nested typedef"), budget, depth + 1);
                    case "string" -> {
                        String text = strictString(value, "string");
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (Character.isHighSurrogate(c)) {
                                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw error("String contains an unpaired Unicode surrogate");
                            } else if (Character.isLowSurrogate(c)) throw error("String contains an unpaired Unicode surrogate");
                        }
                    }
                    case "bytes" -> {
                        try { Base64.getDecoder().decode(strictString(value, "base64 bytes")); }
                        catch (IllegalArgumentException ex) { throw error("Field " + number + " is not valid base64"); }
                    }
                    default -> writeNumber(value, type);
                }
            }
        }
    }

    private static void validateTypes(JsonObject types, int depth, Budget budget) {
        if (depth > MAX_DEPTH) throw error("Typedef nesting exceeds 64 levels");
        for (var entry : types.entrySet()) {
            budget.field(depth); fieldNumber(entry.getKey());
            JsonObject definition = requireObject(entry.getValue(), "Field typedef");
            for (String key : definition.keySet()) if (!key.equals("type") && !key.equals("message_typedef")) throw error("Unknown typedef property: " + key);
            String type = strictString(definition.get("type"), "type");
            String scalar = type.startsWith("packed_") ? type.substring(7) : type;
            if (!TYPES.contains(scalar) || (type.startsWith("packed_") && Set.of("message", "string", "bytes").contains(scalar)))
                throw error("Unsupported field type: " + type);
            if (type.equals("message")) validateTypes(requireObject(definition.get("message_typedef"), "message_typedef"), depth + 1, budget);
            else if (definition.has("message_typedef")) throw error("message_typedef is only valid for message fields");
        }
    }
    private static JsonElement parseJson(String text) {
        Objects.requireNonNull(text, "JSON text");
        limit(text.length(), MAX_DISPLAY_BYTES, "JSON exceeds the display limit");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readJson(reader, 0, new Budget());
            if (reader.peek() != JsonToken.END_DOCUMENT) throw error("Trailing JSON content");
            return value;
        } catch (IOException | IllegalStateException | NumberFormatException ex) { throw new CodecException("Invalid JSON: " + ex.getMessage(), ex); }
    }
    private static JsonElement readJson(JsonReader reader, int depth, Budget budget) throws IOException {
        if (depth > MAX_DEPTH * 3 + 8) throw error("JSON nesting is too deep");
        checkCancelled();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    if (++budget.fields > MAX_FIELDS * 4) throw error("JSON has too many entries");
                    String name = reader.nextName(); if (object.has(name)) throw error("Duplicate JSON key: " + name);
                    object.add(name, readJson(reader, depth + 1, budget));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    if (++budget.fields > MAX_FIELDS * 4) throw error("JSON has too many entries");
                    array.add(readJson(reader, depth + 1, budget));
                }
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                new BigDecimal(number); // Validate the exponent, but retain IEEE negative-zero spelling.
                yield new JsonPrimitive(new LexicalNumber(number));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw error("Expected a JSON value");
        };
    }
    private static final class LexicalNumber extends Number {
        private static final long serialVersionUID = 1L;
        private final String text;
        LexicalNumber(String text) { this.text = text; }
        @Override public int intValue() { return new BigDecimal(text).intValue(); }
        @Override public long longValue() { return new BigDecimal(text).longValue(); }
        @Override public float floatValue() { return Float.parseFloat(text); }
        @Override public double doubleValue() { return Double.parseDouble(text); }
        @Override public String toString() { return text; }
    }
    private static boolean sameValue(JsonElement a, JsonElement b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.isJsonObject() && b.isJsonObject()) {
            JsonObject x = a.getAsJsonObject(), y = b.getAsJsonObject();
            if (!x.keySet().equals(y.keySet())) return false;
            for (String key : x.keySet()) if (!sameValue(x.get(key), y.get(key))) return false;
            return true;
        }
        if (a.isJsonArray() && b.isJsonArray()) {
            JsonArray x = a.getAsJsonArray(), y = b.getAsJsonArray();
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!sameValue(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber()) {
            double x = a.getAsDouble(), y = b.getAsDouble();
            if (x == 0 && y == 0) return Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(y);
        }
        return a.equals(b);
    }

    private static JsonObject requireObject(JsonElement element, String label) {
        if (element == null || !element.isJsonObject()) throw error(label + " must be a JSON object");
        return element.getAsJsonObject();
    }
    private static String strictString(JsonElement value, String type) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw error(type + " requires a JSON string");
        return value.getAsString();
    }
    private static BigInteger integer(JsonElement value, String type) {
        if (value == null || !value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) throw error(type + " requires an integer or decimal string");
        try {
            String text = value.getAsString();
            if (!text.matches("-?(0|[1-9][0-9]*)")) throw new NumberFormatException();
            if (text.length() > 21) throw new NumberFormatException();
            return new BigInteger(text);
        } catch (NumberFormatException ex) { throw error(type + " requires an exact integer in range"); }
    }
    private static double floatingValue(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) throw error("Floating-point value requires a number");
        String text = value.getAsString();
        if (text.equals("NaN")) return Double.NaN;
        if (text.equals("Infinity")) return Double.POSITIVE_INFINITY;
        if (text.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
        try {
            double result = Double.parseDouble(text);
            if (!Double.isFinite(result)) throw error("Floating-point value is out of range; use explicit Infinity strings");
            return result;
        } catch (NumberFormatException ex) { throw error("Invalid floating-point value"); }
    }
    private static void integerRange(BigInteger n, BigInteger min, BigInteger max, String type) {
        if (n.compareTo(min) < 0 || n.compareTo(max) > 0) throw error(type + " is outside [" + min + ", " + max + "]");
    }
    private static int wireFor(String type) {
        return switch (type) {
            case "fixed64", "sfixed64", "double" -> 1;
            case "string", "bytes", "message" -> 2;
            case "fixed32", "sfixed32", "float" -> 5;
            default -> 0;
        };
    }
    private static List<JsonElement> values(JsonElement element) {
        if (!element.isJsonArray()) return List.of(element);
        List<JsonElement> result = new ArrayList<>(); element.getAsJsonArray().forEach(result::add); return result;
    }
    private static LinkedHashMap<Integer, List<Field>> group(List<Field> fields) {
        LinkedHashMap<Integer, List<Field>> result = new LinkedHashMap<>();
        for (Field field : fields) result.computeIfAbsent(field.number, unused -> new ArrayList<>()).add(field);
        return result;
    }
    private static JsonObject type(String value) { JsonObject object = new JsonObject(); object.addProperty("type", value); return object; }
    private static int fieldNumber(String name) {
        if (!name.matches("[1-9][0-9]{0,8}")) throw error("Field keys must be canonical Protobuf field numbers: " + name);
        try { int number = Integer.parseInt(name); validateNumber(number); return number; }
        catch (NumberFormatException ex) { throw error("Invalid Protobuf field number: " + name); }
    }
    private static void validateNumber(int number) { if (number < 1 || number > 0x1fffffff) throw error("Invalid Protobuf field number: " + number); }
    private static BigInteger unsigned(long n) { return n >= 0 ? BigInteger.valueOf(n) : BigInteger.valueOf(n & Long.MAX_VALUE).setBit(63); }
    private static BigInteger unzigzag(BigInteger value) { return value.shiftRight(1).xor(value.testBit(0) ? BigInteger.valueOf(-1) : BigInteger.ZERO); }
    private static byte[] varint(BigInteger value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        do { int next = value.and(BigInteger.valueOf(127)).intValue(); value = value.shiftRight(7); out.write(next | (value.signum() != 0 ? 128 : 0)); } while (value.signum() != 0);
        return out.toByteArray();
    }
    private static byte[] wrapField(int number, int wire, byte[] data) {
        LimitedOutput out = new LimitedOutput(); out.writeBytes(varint(BigInteger.valueOf(((long) number << 3) | wire)));
        if (wire == 2) out.writeBytes(varint(BigInteger.valueOf(data.length)));
        out.writeBytes(data); return out.toByteArray();
    }
    private static String utf8(byte[] data, int offset, int length) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, offset, length)).toString();
    }
    private static boolean printableUtf8(Field field) {
        // Probe in a fixed-size character buffer: a failed nested-message guess must not
        // allocate another payload-sized String/char[] at each level of nesting.
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(field.source, field.dataStart, field.dataLength);
        CharBuffer chars = CharBuffer.allocate(Math.min(4096, Math.max(1, field.dataLength)));
        while (true) {
            checkCancelled();
            var result = decoder.decode(input, chars, true);
            for (int i = 0; i < chars.position(); i++) {
                char c = chars.get(i);
                if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') return false;
            }
            if (result.isError()) return false;
            if (result.isUnderflow()) return true;
            chars.clear();
        }
    }
    private static boolean looksGrpc(byte[] input) {
        if (input.length < 5 || input[0] != 0) return false;
        long size = Integer.toUnsignedLong(ByteBuffer.wrap(input, 1, 4).getInt());
        return size <= input.length - 5;
    }
    private static List<byte[]> grpcBodies(byte[] input) {
        Cursor c = new Cursor(input); List<byte[]> bodies = new ArrayList<>();
        while (c.hasRemaining()) {
            checkCancelled();
            if (bodies.size() >= MAX_GRPC_RECORDS) throw error("gRPC body exceeds 1024 records");
            byte[] header = c.take(5);
            if (header[0] != 0) throw error("Compressed gRPC records and gRPC-Web trailers are unsupported");
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(header, 1, 4).getInt());
            if (length > MAX_PAYLOAD_BYTES) throw error("gRPC record exceeds 8 MiB");
            bodies.add(c.take((int) length));
        }
        if (bodies.isEmpty()) throw error("A gRPC body needs at least one record");
        return bodies;
    }
    private static byte[] wrapGrpc(List<byte[]> bodies) {
        LimitedOutput out = new LimitedOutput();
        for (byte[] body : bodies) { out.write(0); out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array()); out.writeBytes(body); }
        return out.toByteArray();
    }
    private static byte[] ungzip(byte[] input) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input))) {
            LimitedOutput out = new LimitedOutput(); byte[] block = new byte[8192]; int count;
            while ((count = gzip.read(block)) != -1) { checkCancelled(); out.write(block, 0, count); }
            return out.toByteArray();
        } catch (IOException ex) { throw new CodecException("Invalid gzip body: " + ex.getMessage(), ex); }
    }
    private static byte[] gzip(byte[] input) {
        LimitedOutput out = new LimitedOutput();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(input); }
        catch (IOException ex) { throw new CodecException("Could not gzip body", ex); }
        return out.toByteArray();
    }
    private static final class LimitedOutput extends ByteArrayOutputStream {
        private void reserve(int amount) { if (amount < 0 || (long) count + amount > MAX_PAYLOAD_BYTES) throw error("Decoded or encoded payload exceeds 8 MiB"); }
        @Override public synchronized void write(int value) { reserve(1); super.write(value); }
        @Override public synchronized void write(byte[] b, int off, int len) { reserve(len); super.write(b, off, len); }
        @Override public void writeBytes(byte[] b) { write(b, 0, b.length); }
    }
    private static final class Cursor {
        final byte[] bytes; final int end; int pos;
        Cursor(byte[] bytes) { this(bytes, 0, bytes.length); }
        Cursor(byte[] bytes, int offset, int length) { this.bytes = bytes; this.pos = offset; this.end = offset + length; }
        boolean hasRemaining() { return pos < end; }
        void skip(int count) {
            if (count < 0 || count > end - pos) throw error("Truncated Protobuf or wrapper body");
            pos += count;
        }
        byte[] take(int count) {
            int start = pos; skip(count); return Arrays.copyOfRange(bytes, start, pos);
        }
        BigInteger varint() {
            BigInteger value = BigInteger.ZERO;
            for (int index = 0; index < 10; index++) {
                if (!hasRemaining()) throw error("Truncated Protobuf varint");
                int next = bytes[pos++] & 255;
                if (index == 9 && next > 1) throw error("Protobuf varint overflows uint64");
                value = value.or(BigInteger.valueOf(next & 127).shiftLeft(index * 7));
                if ((next & 128) == 0) return value;
            }
            throw error("Protobuf varint exceeds 10 bytes");
        }
    }
    private static void limit(int size, int maximum, String message) { if (size > maximum) throw error(message); }
    private static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw error("Protobuf operation was cancelled"); }
    private static CodecException error(String message) { return new CodecException(message); }
}
