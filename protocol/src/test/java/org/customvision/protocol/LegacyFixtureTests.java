package org.customvision.protocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Exact strings reproduced with the baseline Python Publisher, no native dependencies. */
public final class LegacyFixtureTests {
  private LegacyFixtureTests() {}
  public static void run() throws Exception {
    Path root = Path.of("fixtures");
    Map<?, ?> manifest = (Map<?, ?>) Json.parse(Files.readString(root.resolve("legacy-manifest.json")), ProtocolDecoder.Limits.defaults());
    if (!"2aee0fc1794b31539b02000d16791d4eec8df29a".equals(manifest.get("producer_revision"))) throw new AssertionError("legacy revision pin");
    int count = 0;
    for (Object object : (List<?>) manifest.get("fixtures")) {
      Map<?, ?> entry = (Map<?, ?>) object;
      byte[] bytes = Files.readAllBytes(root.resolve((String) entry.get("path")));
      if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(entry.get("sha256"))
          || bytes.length != ((Long) entry.get("byte_count")).longValue()) throw new AssertionError("legacy exact byte pin");
      String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      Map<?, ?> raw = (Map<?, ?>) Json.parse(json, ProtocolDecoder.Limits.defaults());
      SourceKey source = new SourceKey("/LegacyFixture/" + raw.get("pipeline"), (String) raw.get("pipeline"), (String) raw.get("type"));
      Packet packet = new ProtocolDecoder().decode(source, json);
      if (!packet.profile().equals("legacy-schema2") || packet.packetSeq().isPresent()) throw new AssertionError("legacy ordering semantics");
      if (packet.fields().containsKey("timing")) throw new AssertionError("legacy invented timing");
      count++;
    }
    if (count != 5) throw new AssertionError("legacy export count");
    System.out.println("LegacyFixtureTests: " + count + " exact baseline Publisher strings PASS");
  }
}
