package sdfx.core.panorama

class PanoramaVideoClockSuite extends munit.FunSuite:
  test("pause freezes position, seek resumes at the chosen point, speed scales time") {
    var now = 0L
    val clock = new PanoramaVideoClock(60.0, () => now)
    assert(!clock.playing)
    clock.play()
    now += 2_000_000_000L
    assertEqualsDouble(clock.position, 2.0, 1e-9)
    clock.pause()
    now += 5_000_000_000L
    assertEqualsDouble(clock.position, 2.0, 1e-9)
    clock.seek(12.0)
    clock.setSpeed(2.0)
    clock.play()
    now += 1_500_000_000L
    assertEqualsDouble(clock.position, 15.0, 1e-9)
  }

  test("end clamps, default play restarts, and seek after end preserves selection") {
    var now = 0L
    val clock = new PanoramaVideoClock(10.0, () => now)
    clock.play()
    now += 12_000_000_000L
    assertEqualsDouble(clock.position, 10.0, 1e-9)
    assert(!clock.playing)
    clock.play()
    assertEqualsDouble(clock.position, 0.0, 1e-9)
    now += 11_000_000_000L
    assert(!clock.playing)
    clock.seek(4.0)
    clock.play()
    assertEqualsDouble(clock.position, 4.0, 1e-9)
  }

  test("invalid duration, speed and seek values are rejected") {
    intercept[IllegalArgumentException] { new PanoramaVideoClock(Double.NaN, () => 0L) }
    intercept[IllegalArgumentException] { new PanoramaVideoClock(0.0, () => 0L) }
    val clock = new PanoramaVideoClock(10.0, () => 0L)
    for speed <- Seq(Double.NaN, -1.0, 0.0, 0.1, 4.1, Double.PositiveInfinity) do
      intercept[IllegalArgumentException] { clock.setSpeed(speed) }
    for point <- Seq(Double.NaN, -1.0, 11.0, Double.PositiveInfinity) do
      intercept[IllegalArgumentException] { clock.seek(point) }
  }
