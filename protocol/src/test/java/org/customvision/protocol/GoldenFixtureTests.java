package org.customvision.protocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact-byte consumer acceptance of the locally vendored, producer-generated fixture export. */
public final class GoldenFixtureTests {
    private GoldenFixtureTests() {}
    private static int checks;

    public static void run() throws Exception {
        checks = 0;
        Path fixtureRoot = Path.of(System.getProperty("customvision.fixtures", "fixtures")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(fixtureRoot.resolve("fixture-manifest.json"))) {
            Path alternate = Path.of("..", "fixtures").toAbsolutePath().normalize();
            if (Files.isRegularFile(alternate.resolve("fixture-manifest.json"))) fixtureRoot = alternate;
        }
        Map<String, Object> manifest = map(Json.parse(Files.readString(fixtureRoot.resolve("fixture-manifest.json")), ProtocolDecoder.Limits.defaults()));
        check(Long.valueOf(1).equals(manifest.get("manifest_version")), "manifest version");
        check(ProtocolDecoder.ADDITIVE_PROFILE.equals(manifest.get("profile")), "producer profile pinned");
        String status = (String) manifest.get("contract_status");
        check(status != null && !status.isBlank(), "manifest status is explicit");
        ProtocolDecoder decoder = new ProtocolDecoder();
        Map<String, Packet> packets = new HashMap<>();
        List<?> fixtures = (List<?>) manifest.get("fixtures");
        check(fixtures.size() == 27, "exact final producer export count");
        check(status.equals("matched_runtime"), "fixture export is matched to producer runtime");
        for (Object entry : fixtures) {
            Map<String, Object> fixture = map(entry);
            String name = (String) fixture.get("name");
            Path payloadPath = fixtureRoot.resolve((String) fixture.get("path")).normalize();
            check(payloadPath.startsWith(fixtureRoot) && Files.isRegularFile(payloadPath), name + ": vendored path");
            byte[] bytes = Files.readAllBytes(payloadPath);
            check(Long.valueOf(bytes.length).equals(fixture.get("byte_count")), name + ": exact byte count");
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            check(digest.equals(fixture.get("sha256")), name + ": SHA-256 matches producer manifest");
            String json = new String(bytes, StandardCharsets.UTF_8);
            Map<String, Object> raw = map(Json.parse(json, decoder.limits()));
            // The manifest has no NT namespace field: configure one explicitly in this offline test.
            SourceKey source = new SourceKey("/fixture-export/" + raw.get("pipeline"), (String) raw.get("pipeline"), (String) raw.get("type"));
            Packet packet;
            try { packet = decoder.decode(source, json); }
            catch (DecodeException e) { throw new AssertionError(name + ": producer fixture rejected: " + e.getMessage(), e); }
            check(packets.put(name, packet) == null, "unique fixture name");
            check(packet.profile().equals(ProtocolDecoder.ADDITIVE_PROFILE) && packet.packetSeq().isPresent(), name + ": additive envelope");
            Map<String, Object> typed = map(fixture.get("typed_topics"));
            check(Objects.equals(typed.get("connected"), packet.connected()), name + ": connected matches same-publication typed snapshot");
            check(Objects.equals(typed.get("frame_id"), packet.frameId()), name + ": frame identity");
            check(Objects.equals(typed.get("packet_seq"), packet.packetSeq().orElseThrow()), name + ": publication identity");
            check(Objects.equals(typed.get("time_sync_valid"), packet.timeSyncValid()), name + ": synchronization provenance");
            check(Objects.equals(typed.get("capture_server_us"), packet.captureServerUs()), name + ": exact capture micros");
            check(Objects.equals(typed.get("count"), (long) packet.detections().size()), name + ": coherent detection count");
            check(Objects.equals(typed.get("has_target"), !packet.detections().isEmpty()), name + ": coherent detection availability");
            boolean fieldRobotValid = packet.familyValid("localization") && packet.pose("localization", "field_to_robot").isPresent();
            check(Objects.equals(typed.get("pose_valid"), fieldRobotValid), name + ": field robot pose gate");
            Map<String, Object> expected = map(fixture.get("expected"));
            for (Map.Entry<String, Object> expectation : expected.entrySet()) {
                Object actual = expectedValue(packet, expectation.getKey());
                if (!expectation.getKey().equals("new_session")) {
                    check(Objects.equals(actual, expectation.getValue()), name + ": expected " + expectation.getKey() + "=" + expectation.getValue() + " received " + actual);
                }
            }
        }
        for (Object entry : fixtures) {
            Map<String, Object> fixture = map(entry);
            String name = (String) fixture.get("name");
            Packet packet = packets.get(name);
            if (fixture.get("same_frame_as") instanceof String related) {
                Packet prior = packets.get(related);
                check(prior != null && packet.observationId().equals(prior.observationId()), name + ": shared observation identity");
                check(packet.packetSeq().orElseThrow() > prior.packetSeq().orElseThrow(), name + ": invalidation sequence advances same frame");
            }
            if (Boolean.TRUE.equals(map(fixture.get("expected")).get("new_session"))) {
                check(!packet.bootId().equals(packets.get("single_tag").bootId()), name + ": producer boot changes");
            }
        }
        Map<String, Object> invalidManifest = map(Json.parse(Files.readString(fixtureRoot.resolve("consumer-invalid/manifest.json")), decoder.limits()));
        List<?> invalidCases = (List<?>) invalidManifest.get("cases");
        check(invalidCases.size() == 6 && Boolean.FALSE.equals(invalidManifest.get("producer_fixture")), "derived invalid examples marked separately");
        for (Object entry : invalidCases) {
            Map<String, Object> invalid = map(entry);
            String name = (String) invalid.get("name");
            Path invalidRoot = fixtureRoot.resolve("consumer-invalid");
            Path payloadPath = invalidRoot.resolve((String) invalid.get("path")).normalize();
            check(payloadPath.startsWith(invalidRoot), name + ": invalid fixture path");
            byte[] bytes = Files.readAllBytes(payloadPath);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            check(digest.equals(invalid.get("sha256")), name + ": exact derived-invalid SHA-256");
            Packet sourcePacket = packets.get((String) invalid.get("derived_from"));
            DecodeException.Reason expectedReason = switch (name) {
                case "negative_packet_seq", "overflow_packet_seq", "wrong_timing_unit" -> DecodeException.Reason.OUT_OF_RANGE;
                case "string_timestamp" -> DecodeException.Reason.WRONG_TYPE;
                case "dangling_selection", "dangling_reference" -> DecodeException.Reason.BROKEN_REFERENCE;
                default -> throw new AssertionError("Unknown invalid case " + name);
            };
            try { decoder.decode(sourcePacket.source(), new String(bytes, StandardCharsets.UTF_8)); throw new AssertionError(name + ": invalid sample accepted"); }
            catch (DecodeException e) { check(e.reason() == expectedReason, name + ": structured rejection " + e.reason()); }
        }
        System.out.println("GoldenFixtureTests: " + fixtures.size() + " exact-byte producer packets, " + invalidCases.size() + " hashed invalid examples and " + checks + " assertions passed; contract_status=" + status);
    }

    private static Object expectedValue(Packet packet, String key) {
        return switch (key) {
            case "connected" -> packet.connected();
            case "time_sync_valid" -> packet.timeSyncValid();
            case "detections_empty" -> packet.detections().isEmpty();
            case "segmentation_statuses" -> packet.detections().stream().map(detection -> detection.get("segmentation_status")).toList();
            case "localization_valid" -> packet.familyValid("localization");
            case "field_robot_valid" -> packet.familyValid("localization") && packet.pose("localization", "field_to_robot").isPresent();
            case "objects_valid" -> packet.familyValid("objects");
            case "poi_valid" -> packet.familyValid("poi");
            case "localization_reason" -> packet.family("localization").orElseThrow().get("invalid_reason");
            case "objects_reason" -> packet.family("objects").orElseThrow().get("invalid_reason");
            case "poi_reason" -> targets(packet, "poi").stream().map(value -> value.get("invalid_reason")).filter(Objects::nonNull).findFirst().orElse(null);
            case "poi_robot_valid" -> packet.familyValid("poi") && targets(packet, "poi").stream().anyMatch(target -> Boolean.TRUE.equals(target.get("valid")) && target.get("robot_translation_m") instanceof List<?>);
            case "tag_geometry_valid" -> packet.detections().stream().anyMatch(detection -> Boolean.TRUE.equals(detection.get("pose_valid")) && detection.get("camera_to_target") instanceof Map<?, ?>);
            case "all_actionable_topics_clear" -> !packet.usable() && !packet.familyValid("localization") && !packet.familyValid("poi") && !packet.familyValid("objects");
            case "capture_correction_verified" -> packet.family("timing").orElseThrow().get("capture_correction_verified");
            case "new_session" -> true; // Checked against the reference boot after all fixtures decode.
            default -> throw new AssertionError("Unknown producer expectation: " + key);
        };
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> targets(Packet packet, String family) {
        return (List<Map<String, Object>>) packet.family(family).orElseThrow().get("targets");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    public static void main(String[] args) throws Exception { run(); }
}
