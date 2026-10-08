package org.customvision.protocol;

public final class AllTests {
  private AllTests() {}
  public static void main(String[] args) throws Exception {
    DecoderTests.run();
    GoldenFixtureTests.run();
    LegacyFixtureTests.run();
    TimeTests.run();
    LifecycleTests.run();
    ClientTests.run();
    System.out.println("All protocol, golden fixture, replay, clock, lifecycle and client tests PASS");
  }
}
