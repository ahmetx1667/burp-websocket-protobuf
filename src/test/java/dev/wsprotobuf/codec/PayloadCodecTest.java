package dev.wsprotobuf.codec;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.wsprotobuf.codec.PayloadCodec.Encoding.*;

final class PayloadCodecTest {
    private final PayloadCodec codec = new PayloadCodec();
    private static byte[] hex(String text) { return HexFormat.of().parseHex(text.replace(" ", "")); }
    private static String json(PayloadCodec.Decoded decoded) { return decoded.message().toString(); }
    private static String types(PayloadCodec.Decoded decoded) { return decoded.types().toString(); }
    private PayloadCodec.Decoded typed(String wire, String typedef) { return codec.decode(hex(wire), RAW, typedef); }
    private byte[] encodeFresh(String message, String typedef) { return codec.encode(codec.decode(new byte[0], RAW), message, typedef); }

    @Test void noOpPreservesNonCanonicalVarintsAndInterleaving() {
        byte[] original = hex("0881001201780802");
        var decoded = codec.decode(original, RAW);
        assertEquals("{\"1\":[\"1\",\"2\"],\"2\":\"x\"}", json(decoded));
        assertArrayEquals(original, codec.encode(decoded, json(decoded), types(decoded)));
        JsonObject edited = decoded.message().getAsJsonObject(); edited.addProperty("2", "y");
        assertArrayEquals(hex("0881001201790802"), codec.encode(decoded, edited.toString(), types(decoded)));
    }
    @Test void allReturnedMutableStateIsDefensive() {
        byte[] source = hex("0801"); var decoded = codec.decode(source);
        source[1] = 99;
        byte[] obtained = decoded.original(); obtained[1] = 88;
        decoded.message().getAsJsonObject().addProperty("1", "77");
        decoded.types().getAsJsonObject().remove("1");
        assertArrayEquals(hex("0801"), decoded.original());
        assertEquals("1", decoded.message().getAsJsonObject().get("1").getAsString());
        assertTrue(decoded.types().getAsJsonObject().has("1"));
    }
    @Test void uint64RetainsPrecisionAndAcceptsMaximum() {
        var decoded = codec.decode(hex("08ffffffffffffffffff01"));
        assertEquals("18446744073709551615", decoded.message().getAsJsonObject().get("1").getAsString());
        assertArrayEquals(hex("08feffffffffffffffff01"), codec.encode(decoded, "{\"1\":\"18446744073709551614\"}", types(decoded)));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.encode(decoded, "{\"1\":\"18446744073709551616\"}", types(decoded)));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.encode(decoded, "{\"1\":\"-1\"}", types(decoded)));
    }
    @Test void signedIntAndZigzagAreDifferent() {
        String intType = "{\"1\":{\"type\":\"int\"}}";
        var signed = typed("08ffffffffffffffffff01", intType);
        assertEquals("{\"1\":\"-1\"}", json(signed));
        assertArrayEquals(hex("08feffffffffffffffff01"), codec.encode(signed, "{\"1\":\"-2\"}", intType));
        String sintType = "{\"1\":{\"type\":\"sint\"}}";
        var zigzag = typed("0801", sintType);
        assertEquals("{\"1\":\"-1\"}", json(zigzag));
        assertArrayEquals(hex("0803"), codec.encode(zigzag, "{\"1\":\"-2\"}", sintType));
        var min = typed("08ffffffffffffffffff01", sintType);
        assertEquals("{\"1\":\"-9223372036854775808\"}", json(min));
    }
    @Test void fixedNumbersUseLittleEndianAndExactWidths() {
        String typedef = "{\"1\":{\"type\":\"fixed32\"},\"2\":{\"type\":\"fixed64\"},\"3\":{\"type\":\"sfixed32\"},\"4\":{\"type\":\"sfixed64\"}}";
        byte[] wire = hex("0d785634121108070605040302011dffffffff21feffffffffffffff");
        var decoded = codec.decode(wire, RAW, typedef);
        assertEquals("{\"1\":305419896,\"2\":\"72623859790382856\",\"3\":-1,\"4\":\"-2\"}", json(decoded));
        assertArrayEquals(wire, encodeFresh(json(decoded), typedef));
    }
    @Test void floatingPointSupportsFiniteValuesAndExplicitNonFiniteStrings() {
        String typedef = "{\"1\":{\"type\":\"float\"},\"2\":{\"type\":\"double\"}}";
        var decoded = typed("0d0000c03f110000000000000440", typedef);
        assertEquals(1.5, decoded.message().getAsJsonObject().get("1").getAsDouble());
        assertEquals(2.5, decoded.message().getAsJsonObject().get("2").getAsDouble());
        byte[] special = encodeFresh("{\"1\":\"NaN\",\"2\":\"-Infinity\"}", typedef);
        var specialDecoded = codec.decode(special, RAW, typedef);
        assertEquals("NaN", specialDecoded.message().getAsJsonObject().get("1").getAsString());
        assertEquals("-Infinity", specialDecoded.message().getAsJsonObject().get("2").getAsString());
        assertThrows(PayloadCodec.CodecException.class, () -> encodeFresh("{\"1\":1e100}", "{\"1\":{\"type\":\"float\"}}"));
    }
    @Test void floatingPointNegativeZeroIsEditableAndNoOpPreservesItsBits() {
        String typedef = "{\"1\":{\"type\":\"float\"}}";
        var positive = typed("0d00000000", typedef);
        byte[] negative = codec.encode(positive, "{\"1\":-0.0}", typedef);
        assertArrayEquals(hex("0d00000080"), negative);
        var negativeDecoded = codec.decode(negative, RAW, typedef);
        assertArrayEquals(negative, codec.encode(negativeDecoded, json(negativeDecoded), typedef));
        assertArrayEquals(hex("0d00000000"), codec.encode(negativeDecoded, "{\"1\":0.0}", typedef));
    }
    @Test void opaqueDataSurvivesOtherFieldEdits() {
        var decoded = codec.decode(hex("0a0300ff801007"));
        assertEquals("bytes", decoded.types().getAsJsonObject().getAsJsonObject("1").get("type").getAsString());
        assertEquals("AP+A", decoded.message().getAsJsonObject().get("1").getAsString());
        assertArrayEquals(hex("0a0300ff801008"), codec.encode(decoded, "{\"1\":\"AP+A\",\"2\":\"8\"}", types(decoded)));
    }
    @Test void nestedEditsPreserveUnaffectedInnerWireBytes() {
        var decoded = codec.decode(hex("0a06088100120178"));
        assertEquals("message", decoded.types().getAsJsonObject().getAsJsonObject("1").get("type").getAsString());
        assertArrayEquals(hex("0a06088100120179"), codec.encode(decoded, "{\"1\":{\"1\":\"1\",\"2\":\"y\"}}", types(decoded)));
    }
    @Test void explicitPartialTypesAreExpandedIncludingNestedTypes() {
        var decoded = codec.decode(hex("0a0408011002"), RAW, "{\"1\":{\"type\":\"message\",\"message_typedef\":{\"1\":{\"type\":\"sint\"}}}}");
        assertEquals("{\"1\":{\"1\":\"-1\",\"2\":\"2\"}}", json(decoded));
        assertTrue(decoded.types().getAsJsonObject().getAsJsonObject("1").getAsJsonObject("message_typedef").has("2"));
        assertArrayEquals(hex("0a0408011002"), codec.encode(decoded, json(decoded), types(decoded)));
    }
    @Test void printableWireBytesCanBeReinterpretedExplicitly() {
        var inferred = codec.decode(hex("0a020801"));
        assertEquals("message", inferred.types().getAsJsonObject().getAsJsonObject("1").get("type").getAsString());
        var explicit = typed("0a020801", "{\"1\":{\"type\":\"bytes\"}}");
        assertEquals("{\"1\":\"CAE=\"}", json(explicit));
    }
    @Test void repeatedFieldsAndPackedNumericFieldsRoundTrip() {
        var repeated = codec.decode(hex("080108020803"));
        assertEquals("{\"1\":[\"1\",\"2\",\"3\"]}", json(repeated));
        assertArrayEquals(hex("0801080408050806"), codec.encode(repeated, "{\"1\":[\"1\",\"4\",\"5\",\"6\"]}", types(repeated)));
        String typedef = "{\"1\":{\"type\":\"packed_sint\"},\"2\":{\"type\":\"packed_fixed32\"}}";
        var packed = typed("0a0301020312080100000078563412", typedef);
        assertEquals("{\"1\":[\"-1\",\"1\",\"-2\"],\"2\":[1,305419896]}", json(packed));
        byte[] edited = codec.encode(packed, "{\"1\":[\"-3\",\"1\"],\"2\":[1,305419896]}", typedef);
        assertEquals("{\"1\":[\"-3\",\"1\"],\"2\":[1,305419896]}", json(codec.decode(edited, RAW, typedef)));
    }
    @Test void mixedPackedAndExpandedNumbersAreInferredAndPreserveUntouchedWireBytes() {
        byte[] original = hex("0881001201780a0202030804");
        var decoded = codec.decode(original, RAW);
        assertEquals("{\"1\":[\"1\",\"2\",\"3\",\"4\"],\"2\":\"x\"}", json(decoded));
        assertEquals("packed_uint", decoded.types().getAsJsonObject().getAsJsonObject("1").get("type").getAsString());
        assertArrayEquals(original, codec.encode(decoded, json(decoded), types(decoded)));
        JsonObject edited = decoded.message().getAsJsonObject();
        edited.addProperty("2", "y");
        assertArrayEquals(hex("0881001201790a0202030804"), codec.encode(decoded, edited.toString(), types(decoded)));
        byte[] changed = codec.encode(decoded, "{\"1\":[\"1\",\"5\",\"3\",\"4\"],\"2\":\"x\"}", types(decoded));
        assertEquals("{\"1\":[\"1\",\"5\",\"3\",\"4\"],\"2\":\"x\"}", json(codec.decode(changed, RAW, types(decoded))));
    }
    @Test void numericTypedefsAcceptBothPackedAndExpandedRecordsInWireOrder() {
        for (String type : new String[]{"uint", "packed_uint"}) {
            String typedef = "{\"1\":{\"type\":\"" + type + "\"}}";
            for (String wire : new String[]{"080108020803", "0a03010203", "08010a020203", "0a0201020803", "0a0008010a01020803"}) {
                var decoded = typed(wire, typedef);
                assertEquals("{\"1\":[\"1\",\"2\",\"3\"]}", json(decoded), type + " " + wire);
                assertArrayEquals(hex(wire), codec.encode(decoded, json(decoded), types(decoded)));
                byte[] edited = codec.encode(decoded, "{\"1\":[\"1\",\"4\",\"3\"]}", types(decoded));
                assertEquals("{\"1\":[\"1\",\"4\",\"3\"]}", json(codec.decode(edited, RAW, typedef)));
            }
        }
    }
    @Test void mixedNumericRecordsSupportFixedWidthsAndSignedInterpretation() {
        String[][] cases = {
            {"fixed32", "0d010000000a080200000003000000", "{\"1\":[1,2,3]}"},
            {"fixed64", "0a080100000000000000090200000000000000", "{\"1\":[\"1\",\"2\"]}"},
            {"sint", "08010a020203", "{\"1\":[\"-1\",\"1\",\"-2\"]}"},
            {"float", "0d0000803f0a0400000040", "{\"1\":[1.0,2.0]}"},
            {"double", "0a08000000000000f03f090000000000000040", "{\"1\":[1.0,2.0]}"}
        };
        for (String[] sample : cases) {
            for (String prefix : new String[]{"", "packed_"}) {
                String typedef = "{\"1\":{\"type\":\"" + prefix + sample[0] + "\"}}";
                var decoded = typed(sample[1], typedef);
                assertEquals(sample[2], json(decoded), typedef);
                assertArrayEquals(hex(sample[1]), codec.encode(decoded, json(decoded), types(decoded)));
            }
        }
        assertEquals("{\"1\":[1,2,3]}", json(codec.decode(hex(cases[0][1]), RAW)));
        assertEquals("{\"1\":[\"1\",\"2\"]}", json(codec.decode(hex(cases[1][1]), RAW)));
    }
    @Test void expandedTypedefEditsDoNotReusePackedRecordsAsIndividualValues() {
        String typedef = "{\"1\":{\"type\":\"uint\"}}";
        var decoded = typed("0a000a020102", typedef);
        assertEquals("{\"1\":[\"1\",\"2\"]}", json(decoded));
        assertArrayEquals(hex("08010803"), codec.encode(decoded, "{\"1\":[\"1\",\"3\"]}", typedef));
    }
    @Test void numericPackedShapeAndMalformedRecordValidationArePreserved() {
        String packed = "{\"1\":{\"type\":\"packed_uint\"}}";
        assertEquals("{\"1\":[\"1\"]}", json(typed("0801", packed)));
        assertEquals("{\"1\":[]}", json(typed("0a00", packed)));
        assertEquals("{\"1\":[]}", json(typed("0a00", "{\"1\":{\"type\":\"uint\"}}")));
        assertThrows(PayloadCodec.CodecException.class, () -> typed("08010a0180", packed));
        assertThrows(PayloadCodec.CodecException.class, () -> typed("0d010000000a03020304", "{\"1\":{\"type\":\"packed_fixed32\"}}"));
        assertThrows(PayloadCodec.CodecException.class, () -> typed("0901000000000000000a0702030405060708", "{\"1\":{\"type\":\"fixed64\"}}"));
        assertThrows(PayloadCodec.CodecException.class, () -> typed("0d01000000", packed));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(hex("08010d02000000"), RAW));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(hex("08010a01020d03000000"), RAW));
    }
    @Test void additionsRequireTypesAndDeletionIsAllowed() {
        var original = codec.decode(hex("0801"));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.encode(original, "{\"1\":\"1\",\"2\":\"x\"}", types(original)));
        assertArrayEquals(hex("0801120178"), codec.encode(original, "{\"1\":\"1\",\"2\":\"x\"}", "{\"1\":{\"type\":\"uint\"},\"2\":{\"type\":\"string\"}}"));
        assertArrayEquals(new byte[0], codec.encode(original, "{}", types(original)));
    }
    @Test void gzipNoOpPreservesHeaderAndEditsRemainValid() throws Exception {
        byte[] compressed = gzip(hex("0801120178")); compressed[4] = 42;
        var decoded = codec.decode(compressed);
        assertEquals(GZIP, decoded.encoding());
        assertArrayEquals(compressed, codec.encode(decoded, json(decoded), types(decoded)));
        byte[] edited = codec.encode(decoded, "{\"1\":\"2\",\"2\":\"x\"}", types(decoded));
        assertEquals("{\"1\":\"2\",\"2\":\"x\"}", json(codec.decode(edited, GZIP)));
    }
    @Test void gzipBombIsStoppedAtBoundedInflation() throws Exception {
        byte[] bomb = gzip(new byte[PayloadCodec.MAX_PAYLOAD_BYTES + 1]);
        assertTrue(bomb.length < 10000);
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(bomb, GZIP));
    }
    @Test void grpcBigEndianRecordsAndCountArePreserved() {
        byte[] records = hex("0000000002080100000000020802");
        var decoded = codec.decode(records);
        assertEquals(GRPC, decoded.encoding());
        assertEquals("[{\"1\":\"1\"},{\"1\":\"2\"}]", json(decoded));
        assertArrayEquals(records, codec.encode(decoded, json(decoded), types(decoded)));
        assertArrayEquals(hex("0000000002080300000000020804"), codec.encode(decoded, "[{\"1\":\"3\"},{\"1\":\"4\"}]", types(decoded)));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.encode(decoded, "[{\"1\":\"1\"}]", types(decoded)));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(hex("01000000020801"), GRPC));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(hex("00000000030801"), GRPC));
        byte[] tooMany = new byte[(PayloadCodec.MAX_GRPC_RECORDS + 1) * 5];
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(tooMany, GRPC));
    }
    @Test void singleGrpcRecordHasObjectShapeAndEmptyRecordIsAllowed() {
        var single = codec.decode(hex("00000000020801"));
        assertTrue(single.message().isJsonObject());
        assertEquals("{\"1\":\"1\"}", json(single));
        var empty = codec.decode(new byte[5], GRPC);
        assertEquals("{}", json(empty));
    }
    @Test void rawModeSupportsAmbiguousWrapperLikeInput() {
        var raw = codec.decode(hex("0801"), RAW);
        assertEquals(RAW, raw.encoding());
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(hex("0801"), GRPC));
    }
    @Test void malformedWireDataFailsExplicitly() {
        for (String wire : new String[]{"00", "0b", "0c", "0e", "0f", "08", "0880", "0a05ff", "0d0000", "0900", "08ffffffffffffffffffff", "0880808080808080808002", "808080801001"}) {
            assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(hex(wire), RAW), wire);
        }
    }
    @Test void malformedTypedValuesCannotBeUsedEvenForNumericallyEqualNoOp() {
        var fixed = codec.decode(hex("0dffffffff"));
        for (String message : new String[]{"{\"1\":4294967295.0000001}", "{\"1\":4294967296}", "{\"1\":-1}", "{\"1\":true}", "{\"1\":null}"})
            assertThrows(PayloadCodec.CodecException.class, () -> codec.encode(fixed, message, types(fixed)));
        assertThrows(PayloadCodec.CodecException.class, () -> encodeFresh("{\"1\":\"%%%\"}", "{\"1\":{\"type\":\"bytes\"}}"));
        assertThrows(PayloadCodec.CodecException.class, () -> encodeFresh("{\"1\":\"\\ud800\"}", "{\"1\":{\"type\":\"string\"}}"));
    }
    @Test void strictJsonRejectsDuplicatesUnknownPropertiesAndNonCanonicalKeys() {
        var empty = codec.decode(new byte[0]);
        assertThrows(PayloadCodec.CodecException.class, () -> codec.encode(empty, "{\"1\":1,\"1\":2}", "{}"));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.encode(empty, "{'1':1}", "{}"));
        assertThrows(PayloadCodec.CodecException.class, () -> codec.encode(empty, "{\"1\":1} trailing", "{}"));
        for (String key : new String[]{"0", "01", "536870912", "a"})
            assertThrows(PayloadCodec.CodecException.class, () -> encodeFresh("{\"" + key + "\":1}", "{\"" + key + "\":{\"type\":\"uint\"}}"));
        assertThrows(PayloadCodec.CodecException.class, () -> encodeFresh("{\"1\":1}", "{\"1\":{\"type\":\"uint\",\"name\":\"x\"}}"));
        assertThrows(PayloadCodec.CodecException.class, () -> encodeFresh("{\"1\":1}", "{\"1\":{\"type\":\"packed_string\"}}"));
    }
    @Test void inputFieldAndCancellationLimitsAreEnforced() {
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(new byte[PayloadCodec.MAX_PAYLOAD_BYTES + 1]));
        byte[] tooMany = new byte[(PayloadCodec.MAX_FIELDS + 1) * 2];
        for (int i = 0; i < tooMany.length; i += 2) tooMany[i] = 8;
        assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(tooMany));
        Thread.currentThread().interrupt();
        try { assertThrows(PayloadCodec.CodecException.class, () -> codec.decode(hex("0801"))); }
        finally { Thread.interrupted(); }
    }
    @Test void nestingIsBoundedAndDeepInferenceDoesNotExplode() {
        byte[] body = hex("0801");
        for (int i = 0; i < 50; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(10);
            int length = body.length;
            while (length >= 128) { out.write((length & 127) | 128); length >>>= 7; }
            out.write(length); out.writeBytes(body); body = out.toByteArray();
        }
        var decoded = codec.decode(body, RAW);
        assertArrayEquals(body, codec.encode(decoded, json(decoded), types(decoded)));
        String nestedMessage = "{}", nestedTypes = "{}";
        for (int i = 0; i < 66; i++) {
            nestedMessage = "{\"1\":" + nestedMessage + "}";
            nestedTypes = "{\"1\":{\"type\":\"message\",\"message_typedef\":" + nestedTypes + "}}";
        }
        final String message = nestedMessage, types = nestedTypes;
        assertThrows(PayloadCodec.CodecException.class, () -> encodeFresh(message, types));
    }
    private static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(input); }
        return out.toByteArray();
    }
}
