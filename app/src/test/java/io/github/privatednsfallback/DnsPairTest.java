package io.github.privatednsfallback;

import static org.junit.Assert.*;

import org.junit.Test;

public final class DnsPairTest {
  private final DnsPair before = new DnsPair("opportunistic", "old.example");
  private final DnsPair strict = new DnsPair("hostname", "new.example");

  @Test
  public void restartBeforeWritingKeepsBaseline() {
    assertEquals(DnsPair.Recovery.UNCHANGED, DnsPair.recover(before, strict, before));
  }

  @Test
  public void restartBetweenAndroidWritesRecognizesInactiveHost() {
    assertEquals(
        DnsPair.Recovery.HOST_ONLY,
        DnsPair.recover(before, strict, new DnsPair(before.mode, strict.host)));
  }

  @Test
  public void restartAfterAndroidWritesRecoversStrictOwnership() {
    assertEquals(DnsPair.Recovery.COMPLETE, DnsPair.recover(before, strict, strict));
  }

  @Test
  public void completedReleaseIsRecognizedAfterInterruption() {
    DnsPair auto = new DnsPair("opportunistic", strict.host);
    assertEquals(DnsPair.Recovery.COMPLETE, DnsPair.recover(strict, auto, auto));
  }

  @Test
  public void editAfterSuccessfulCheckCannotBecomeTheWriteBaseline() {
    DnsPair manual = new DnsPair("hostname", "manual.example");
    before.requireUnchanged(new DnsPair(before.mode, before.host));
    assertThrows(SecurityException.class, () -> before.requireUnchanged(manual));
  }

  @Test
  public void externalChangesAreNotAdopted() {
    for (DnsPair changed :
        new DnsPair[] {
          new DnsPair("off", strict.host),
          new DnsPair("hostname", "other.example"),
          new DnsPair(null, null)
        }) {
      assertFalse(before.same(changed));
      assertEquals(DnsPair.Recovery.EXTERNAL, DnsPair.recover(before, strict, changed));
    }
  }
}
