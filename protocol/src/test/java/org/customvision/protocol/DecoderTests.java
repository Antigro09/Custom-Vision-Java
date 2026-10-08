package org.customvision.protocol;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.customvision.protocol.DecodeException.Reason.*;

/** Adversarial synthetic tests; pinned producer fixture interoperability is a separate acceptance check. */
public final class DecoderTests {
    private DecoderTests() {}
    private static int checks;
    private static final SourceKey TAG_SOURCE = new SourceKey("/CustomVision/jetson-tags/front", "front", "apriltag");
    private static final SourceKey OBJECT_SOURCE = new SourceKey("/CustomVision/jetson-objects/front", "front", "object");
    private static final String EMPTY = """
            {"schema_version":2,"boot_id":"boot-A","pipeline":"front","type":"apriltag",
             "mode":"3d","backend":"pupil","detector_device":"cpu","pose_device":"none",
             "input_kind":"camera","connected":true,"frame_id":7,
             "capture_monotonic_us":1000000,"publish_unix_us":2000000,"latency_ms":5.0,
             "capture_server_us":3000000,"time_sync_valid":true,
             "timestamp_source":"host_frame_read_complete","capture_latency_offset_ms":0.0,
             "detections":[],"error":null}
            """.strip();
    private static final String TAG = """
            {"id":4,"hamming":0,"decision_margin":100.0,"center":[100.0,100.0],
             "corners":[[90.0,90.0],[110.0,90.0],[110.0,110.0],[90.0,110.0]],
             "pose_valid":true,"pose_source":"field_layout_multitag",
             "rvec_rad":[0.0,0.0,0.0],"tvec_m":[0.0,0.0,2.0],
             "distance_m":2.0,"pose_ambiguity":0.1,"reprojection_error_px":0.5}
            """.strip();
    private static final String POSE = """
            {"translation_m":[1.0,2.0,0.0],"rotation_quaternion_wxyz":[1.0,0.0,0.0,0.0],
             "rotation_rpy_deg":[0.0,0.0,0.0],"frame":"wpilib_nwu"}
            """.strip();
    private static final String LOCALIZATION = """
            {"valid":true,"method":"multitag_pnp","field_to_camera":POSE,"field_to_robot":POSE,
             "used_tag_ids":[4],"inlier_tag_count":1,"reprojection_error_px":0.5,"ambiguity":0.1,
             "tag_reprojection_errors_px":{"4":0.5}}
            """.strip().replace("POSE", POSE);
    private static final String ADDITIVE = """
            "protocol_profile":"custom-vision-schema2-2026.1","packet_seq":8,
            "calibration_revision":null,"mount_revision":null,"field_layout_revision":null,
            "timing":{"clock_domain":"nt_server","timestamp_unit":"us",
              "capture_event":"host_frame_read_complete","capture_correction_verified":false,
              "capture_correction_uncertainty_ms":null}
            """.strip();
    private static final String OBJECT_DETECTION = """
            {"class_id":1,"label":"ball","confidence":0.9,"bbox_xyxy":[90.0,90.0,110.0,110.0],
             "center":[100.0,100.0],"robot_relative":{"valid":true,"track_id":3}}
            """.strip();
    private static final String OBJECTS = """
            {"valid":true,"selected_track_id":3,"capture_monotonic_us":1000000,
             "invalid_reason":null,"frame":"robot_relative_at_capture_wpilib_nwu",
             "motion_compensated":false,"targets":[
              {"valid":true,"frame":"robot_relative_at_capture_wpilib_nwu",
               "translation_m":[2.0,1.0,0.0],"range_xy_m":2.236068,"bearing_deg":26.565051,
               "anchor":"bbox_center","anchor_px":[100.0,100.0],
               "method":"calibrated_ray_target_height_plane","approximate":true,"target_height_m":0.0,
               "uncertainty":{"model":"first_order_configured_pixel_height_pitch_mount",
                  "covariance_xy_m2":[[0.01,0.001],[0.001,0.01]],"std_xy_m":[0.1,0.1],
                  "max_position_std_m":0.105,"range_std_m":0.1,"bearing_std_deg":2.5},
               "range_from_intake_m":2.0,"approach":{"translation_m":[1.9,1.0,0.0],
                  "rotation_yaw_deg":0.0,"frame":"robot_relative_at_capture_wpilib_nwu",
                  "heading_policy":"maintain_capture_heading","path_validated":false},
               "detection_index":0,"class_id":1,"label":"ball","track_id":3,
               "observed":true,"predicted":false,"capture_monotonic_us":1000000,
               "track_observations":1,"track_age_ms":0.0}]}
            """.strip();
    private static final String POI = """
            {"valid":true,"selected_name":"goal","invalid_reason":null,
             "capture_monotonic_us":1000000,"uses_odometry":false,"uses_field_layout":false,
             "targets":[{"name":"goal","tag_id":4,"offset_m":[0.0,0.0,1.0],
               "offset_frame":"tag_wpilib","valid":true,"geometry_valid":true,
               "calibration_verified":true,"invalid_reason":null,"pixel":[100.0,100.0],
               "camera_translation_m":[0.0,-1.0,2.0],"camera_frame":"opencv_right_down_forward",
               "tx_deg":0.0,"ty_deg":26.565051,"robot_translation_m":null,
               "robot_yaw_deg":null,"robot_elevation_deg":null}]}
            """.strip();

    public static void run() throws Exception {
        checks = 0;
        ProtocolDecoder decoder = new ProtocolDecoder();
        Packet empty = decoder.decode(TAG_SOURCE, EMPTY);
        check(empty.frameId() == 7 && empty.packetSeq().isEmpty() && !empty.usable(), "legacy empty semantics");
        check(empty.fields().get("frame_id") instanceof Long && empty.fields().get("latency_ms") instanceof Double, "integer/decimal representations retained");
        check(empty.source().resultTopic().equals("/CustomVision/jetson-tags/front/result"), "full namespace");
        Packet tag = decoder.decode(TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION));
        check(tag.usable() && tag.familyValid("localization") && tag.poseCandidates().size() == 2, "valid tag localization candidates");
        check(tag.localization().orElseThrow().valid() && tag.localization().orElseThrow().fieldRobot().isPresent(), "typed localization validity");
        check(tag.timestamps().captureServerUs().orElseThrow() == 3_000_000 && tag.timestamps().captureMonotonicUs().orElseThrow() == 1_000_000, "typed timestamp provenance");
        check(tag.diagnostics().get("backend").equals("pupil"), "typed diagnostics retain backend");
        check(tag.pose("localization", "field_to_robot").orElseThrow().translation().y() == 2.0, "geometry axes preserved");
        check(tag.poseCandidates().get(0).provenance().equals("multitag_pnp") && tag.poseCandidates().get(0).quality().containsKey("reprojection_error_px"), "joint quality provenance");
        Packet cameraOnly = decoder.decode(TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("\"field_to_robot\":" + POSE, "\"field_to_robot\":null")));
        check(cameraOnly.pose("localization", "field_to_robot").isEmpty() && cameraOnly.poseCandidates().size() == 1, "camera-only never synthesizes robot pose");
        check(cameraOnly.localization().orElseThrow().fieldCamera().isPresent() && cameraOnly.localization().orElseThrow().fieldRobot().isEmpty(), "typed optional camera-only pose");
        Packet additive = decoder.decode(TAG_SOURCE, append(EMPTY, ADDITIVE));
        check(additive.packetSeq().orElseThrow() == 8 && additive.revision().equals("null|null|null"), "producer-owned additive identity");
        check(additive.timestamps().captureCorrectionVerified().orElseThrow().equals(false) && additive.timestamps().captureCorrectionUncertaintyMs().isEmpty(), "nullable correction uncertainty distinct from zero");
        String hashed = ADDITIVE.replace("\"calibration_revision\":null", "\"calibration_revision\":\"sha256:" + "a".repeat(64) + "\"");
        check(decoder.decode(TAG_SOURCE, append(EMPTY, hashed)).revision().startsWith("sha256:"), "opaque revision hash");
        Packet unknown = decoder.decode(TAG_SOURCE, append(EMPTY, "\"future_addition\":{\"a\":[null,1,2.0,true,\"new\"]}"));
        check(unknown.family("future_addition").isPresent(), "unknown bounded additions retained");
        immutable(() -> unknown.fields().put("bad", true));
        immutable(() -> unknown.family("future_addition").orElseThrow().put("bad", true));
        List<?> nested = (List<?>) unknown.family("future_addition").orElseThrow().get("a");
        immutable(nested::clear);
        check(decoder.decode(TAG_SOURCE, EMPTY.replace(",\"error\":null", "")).connected(), "omitted error tolerated");
        check(decoder.decode(TAG_SOURCE, EMPTY.replace("\"capture_server_us\":3000000", "\"capture_server_us\":null").replace("\"time_sync_valid\":true", "\"time_sync_valid\":false")).captureServerUs() == 0, "unavailable capture remains raw null");
        Packet invalid = decoder.decode(TAG_SOURCE, EMPTY.replace("\"connected\":true", "\"connected\":false").replace("\"error\":null", "\"error\":\"watchdog\""));
        check(!invalid.usable() && !invalid.familyValid("localization"), "failure packet invalidates availability");
        Packet poi = decoder.decode(TAG_SOURCE, tagPacket("\"poi\":" + POI));
        check(poi.familyValid("poi") && poi.pose("localization", "field_to_robot").isEmpty(), "POI without field localization remains optional geometry");
        check(poi.selectedPoi().orElseThrow().cameraOptical().orElseThrow().z() == 2.0 && poi.selectedPoi().orElseThrow().robotNwu().isEmpty(), "typed POI retains optical axes and missing mount");
        Packet object = decoder.decode(OBJECT_SOURCE, objectPacket());
        check(object.familyValid("objects") && object.trackId(3).source().equals(OBJECT_SOURCE), "compact object references and source-local ID");
        check(object.objectTargets().size() == 1 && object.selectedTarget().orElseThrow().identity().equals(object.trackId(3)), "typed selected canonical object");
        check(object.selectedTarget().orElseThrow().covarianceXyM2().get(0).get(1) == 0.001, "typed square-meter covariance");
        check(decoder.decode(OBJECT_SOURCE, objectPacket().replace("\"valid\":true,\"track_id\":3", "\"valid\":true,\"track_id\":3,\"future_diagnostic\":1")).selectedTarget().isPresent(), "bounded unknown compact-reference diagnostic retained");
        immutable(() -> object.selectedTarget().orElseThrow().covarianceXyM2().get(0).clear());
        check(decoder.decode(OBJECT_SOURCE, objectPacket().replace("\"connected\":true", "\"connected\":false").replace("\"detections\":[" + OBJECT_DETECTION + "]", "\"detections\":[]").replace("\"objects\":" + OBJECTS, "\"objects\":{\"valid\":false,\"targets\":[],\"selected_track_id\":null,\"motion_compensated\":false}")).objectTargets().isEmpty(), "typed invalid family has no actionable cached targets");
        check(decoder.decode(TAG_SOURCE, append(EMPTY, "\"localization\":{\"valid\":false}")).localization().orElseThrow().fieldCamera().isEmpty(), "failure diagnostics can be omitted");

        reject(decoder, TAG_SOURCE, EMPTY + "x", MALFORMED_JSON);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"frame_id\":7", "\"frame_id\":7,\"frame_id\":7"), DUPLICATE_KEY);
        reject(decoder, TAG_SOURCE, append(EMPTY, "\"nested\":{\"a\":null,\"a\":2}"), DUPLICATE_KEY);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"frame_id\":7", "\"frame_id\":7.0"), WRONG_TYPE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"frame_id\":7", "\"frame_id\":\"7\""), WRONG_TYPE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"frame_id\":7", "\"frame_id\":true"), WRONG_TYPE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"frame_id\":7", "\"frame_id\":-1"), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"frame_id\":7", "\"frame_id\":9223372036854775808"), INTEGER_OVERFLOW);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"schema_version\":2", "\"schema_version\":3"), UNSUPPORTED_VERSION);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"pipeline\":\"front\"", "\"pipeline\":\"rear\""), SOURCE_MISMATCH);
        reject(decoder, OBJECT_SOURCE, EMPTY, SOURCE_MISMATCH);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"mode\":\"3d\"", "\"mode\":\"detect\""), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"connected\":true", "\"connected\":1"), WRONG_TYPE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"latency_ms\":5.0", "\"latency_ms\":\"5.0\""), WRONG_TYPE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"latency_ms\":5.0", "\"latency_ms\":1e309"), NONFINITE_NUMBER);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"latency_ms\":5.0", "\"latency_ms\":NaN"), MALFORMED_JSON);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"capture_server_us\":3000000", "\"capture_server_us\":3000000.0"), WRONG_TYPE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"capture_server_us\":3000000", "\"capture_server_us\":null"), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"time_sync_valid\":true", "\"time_sync_valid\":false"), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"capture_monotonic_us\":1000000,", ""), MISSING_REQUIRED_FIELD);
        reject(decoder, TAG_SOURCE, append(EMPTY, ADDITIVE.replace("\"packet_seq\":8,", "")), MISSING_REQUIRED_FIELD);
        reject(decoder, TAG_SOURCE, append(EMPTY, ADDITIVE.replace("\"packet_seq\":8", "\"packet_seq\":9007199254740992")), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, append(EMPTY, ADDITIVE.replace("\"packet_seq\":8", "\"packet_seq\":8.0")), WRONG_TYPE);
        reject(decoder, TAG_SOURCE, append(EMPTY, ADDITIVE.replace("custom-vision-schema2-2026.1", "unrecognized-profile")), UNSUPPORTED_VERSION);
        reject(decoder, TAG_SOURCE, append(EMPTY, ADDITIVE.replace("\"mount_revision\":null", "\"mount_revision\":\"arbitrary\"")), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, append(EMPTY, ADDITIVE.replace("\"timestamp_unit\":\"us\"", "\"timestamp_unit\":\"ns\"")), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, append(EMPTY, ADDITIVE.replace("\"capture_correction_uncertainty_ms\":null", "\"capture_correction_uncertainty_ms\":-1.0")), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, append(EMPTY, ADDITIVE.replace(",\n  \"capture_correction_uncertainty_ms\":null", "")), MISSING_REQUIRED_FIELD);

        reject(decoder, TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("[1.0,0.0,0.0,0.0]", "[0.0,0.0,0.0,0.0]")), INVALID_QUATERNION);
        reject(decoder, TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("[1.0,0.0,0.0,0.0]", "[2.0,0.0,0.0,0.0]")), INVALID_QUATERNION);
        reject(decoder, TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("[1.0,2.0,0.0]", "[1.0,2.0]")), BAD_DIMENSION);
        reject(decoder, TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("wpilib_nwu", "opencv_right_down_forward")), ILLEGAL_FRAME);
        reject(decoder, TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("\"used_tag_ids\":[4]", "\"used_tag_ids\":[9]")), BROKEN_REFERENCE);
        reject(decoder, TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("\"inlier_tag_count\":1", "\"inlier_tag_count\":2")), BROKEN_REFERENCE);
        reject(decoder, TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("\"4\":0.5", "\"9\":0.5")), BROKEN_REFERENCE);
        reject(decoder, TAG_SOURCE, tagPacket("\"localization\":" + LOCALIZATION.replace("\"valid\":true", "\"valid\":false")), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, tagPacket("\"poi\":" + POI.replace("\"selected_name\":\"goal\"", "\"selected_name\":\"other\"")), BROKEN_REFERENCE);
        reject(decoder, TAG_SOURCE, tagPacket("\"poi\":" + POI.replace("\"tag_id\":4", "\"tag_id\":9")), BROKEN_REFERENCE);
        reject(decoder, TAG_SOURCE, tagPacket("\"poi\":" + POI.replace("\"calibration_verified\":true", "\"calibration_verified\":false")), OUT_OF_RANGE);
        reject(decoder, TAG_SOURCE, tagPacket("\"poi\":" + POI.replace("opencv_right_down_forward", "wpilib_nwu")), ILLEGAL_FRAME);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("\"selected_track_id\":3", "\"selected_track_id\":99"), BROKEN_REFERENCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("\"detection_index\":0", "\"detection_index\":1"), BROKEN_REFERENCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("\"valid\":true,\"track_id\":3", "\"valid\":true,\"track_id\":99"), BROKEN_REFERENCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("\"valid\":true,\"track_id\":3", "\"valid\":true,\"track_id\":3,\"translation_m\":[1,2,3]"), BROKEN_REFERENCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("\"class_id\":1,\"label\":\"ball\",\"track_id\":3", "\"class_id\":2,\"label\":\"ball\",\"track_id\":3"), BROKEN_REFERENCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("[[0.01,0.001],[0.001,0.01]]", "[[0.01,0.002],[0.001,0.01]]"), INVALID_COVARIANCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("[[0.01,0.001],[0.001,0.01]]", "[[0.01,0.02],[0.02,0.01]]"), INVALID_COVARIANCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("[[0.01,0.001],[0.001,0.01]]", "[[1e300,1e150],[1e150,0.0]]"), INVALID_COVARIANCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("[[0.01,0.001],[0.001,0.01]]", "[[1e300,0.0],[0.0,-1.0]]"), INVALID_COVARIANCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("[[0.01,0.001],[0.001,0.01]]", "[[1e300,1e150],[0.0,1.0]]"), INVALID_COVARIANCE);
        reject(decoder, OBJECT_SOURCE, objectPacket().replace("[[0.01,0.001],[0.001,0.01]]", "[[0.01,0.001],[0.001]]"), BAD_DIMENSION);
        check(decoder.decode(OBJECT_SOURCE, objectPacket().replace("[[0.01,0.001],[0.001,0.01]]", "[[0.01,0.01],[0.01,0.01]]")).usable(), "singular PSD covariance allowed");
        check(decoder.decode(OBJECT_SOURCE, objectPacket().replace("[[0.01,0.001],[0.001,0.01]]", "[[0.0,0.0],[0.0,0.0]]")).usable(), "zero PSD covariance allowed");
        check(decoder.decode(TAG_SOURCE, append(EMPTY, "\"extra\":{\"covariance_future\":\"opaque addition\"}")).connected(), "unknown future fields remain uninterpreted");

        reject(decoder, TAG_SOURCE, EMPTY.replace("\"boot_id\":\"boot-A\"", "\"boot_id\":\"\\uD800\""), MALFORMED_JSON);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"boot_id\":\"boot-A\"", "\"boot_id\":\"\\uＦＦＦＦ\""), MALFORMED_JSON);
        check(decoder.decode(TAG_SOURCE, EMPTY.replace("boot-A", "\\uD83D\\uDE80")).bootId().equals("🚀"), "paired escaped surrogates allowed");
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"frame_id\":7", "\"frame_id\":07"), MALFORMED_JSON);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"latency_ms\":5.0", "\"latency_ms\":5."), MALFORMED_JSON);
        reject(decoder, TAG_SOURCE, EMPTY.replace("\"latency_ms\":5.0", "\"latency_ms\":5e"), MALFORMED_JSON);
        reject(new ProtocolDecoder(new ProtocolDecoder.Limits(10, 4096, 32768, 24, 4096, 256, 256, 256)), TAG_SOURCE, EMPTY, PAYLOAD_LIMIT);
        reject(new ProtocolDecoder(new ProtocolDecoder.Limits(262144, 8, 32768, 24, 4096, 256, 256, 256)), TAG_SOURCE, EMPTY, STRING_LIMIT);
        reject(new ProtocolDecoder(new ProtocolDecoder.Limits(262144, 4096, 3, 24, 4096, 256, 256, 256)), TAG_SOURCE, EMPTY, TOKEN_LIMIT);
        reject(new ProtocolDecoder(new ProtocolDecoder.Limits(262144, 4096, 32768, 2, 4096, 256, 256, 256)), TAG_SOURCE, append(EMPTY, "\"nested\":[[[0]]]"), NESTING_LIMIT);
        reject(new ProtocolDecoder(new ProtocolDecoder.Limits(262144, 4096, 32768, 2, 4096, 256, 256, 256)), TAG_SOURCE, append(EMPTY, "\"nested\":[[]]"), NESTING_LIMIT);
        reject(new ProtocolDecoder(new ProtocolDecoder.Limits(262144, 4096, 32768, 24, 1, 256, 256, 256)), TAG_SOURCE, append(EMPTY, "\"nested\":[0,1]"), COLLECTION_LIMIT);
        reject(new ProtocolDecoder(new ProtocolDecoder.Limits(262144, 4096, 32768, 24, 4096, 2, 256, 256)), TAG_SOURCE, EMPTY, COLLECTION_LIMIT);
        String twoTags = EMPTY.replace("\"detections\":[]", "\"detections\":[" + TAG + "," + TAG + "]");
        reject(new ProtocolDecoder(new ProtocolDecoder.Limits(262144, 4096, 32768, 24, 4096, 256, 1, 256)), TAG_SOURCE, twoTags, COLLECTION_LIMIT);
        check(decoder.decode(TAG_SOURCE, EMPTY.replace("\"frame_id\":7", "\"frame_id\":9223372036854775807")).frameId() == Long.MAX_VALUE, "integer boundary preserved exactly");
        SourceKey isolated = new SourceKey("/CustomVision/another/front", "front", "apriltag");
        check(!decoder.decode(isolated, EMPTY).observationId().equals(empty.observationId()), "same boot/frame remains isolated by full namespace");
        Packet manual = new Packet(TAG_SOURCE, 2, "manual", 0, OptionalLong.empty(), "legacy", true, 0, false, Map.of("detections", List.of()));
        check(!manual.usable(), "manual immutable fake packet");
        System.out.println("DecoderTests: " + checks + " checks passed (synthetic adversarial contracts; pinned fixture checks reported separately)");
    }

    private static String append(String base, String fields) { return base.substring(0, base.length() - 1) + "," + fields + "}"; }
    private static String tagPacket(String fields) { return append(EMPTY.replace("\"detections\":[]", "\"detections\":[" + TAG + "]"), fields); }
    private static String objectPacket() { return append(EMPTY.replace("\"type\":\"apriltag\"", "\"type\":\"object\"").replace("\"mode\":\"3d\"", "\"mode\":\"detect\"").replace("\"detections\":[]", "\"detections\":[" + OBJECT_DETECTION + "]"), "\"objects\":" + OBJECTS); }
    private static void reject(ProtocolDecoder decoder, SourceKey source, String json, DecodeException.Reason reason) throws Exception {
        try { decoder.decode(source, json); throw new AssertionError("expected " + reason + " for " + json); }
        catch (DecodeException e) { check(e.reason() == reason, "expected " + reason + ", received " + e.getMessage()); }
    }
    private static void immutable(Runnable action) { try { action.run(); throw new AssertionError("mutable DTO"); } catch (UnsupportedOperationException expected) { checks++; } }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    public static void main(String[] args) throws Exception { run(); }
}
