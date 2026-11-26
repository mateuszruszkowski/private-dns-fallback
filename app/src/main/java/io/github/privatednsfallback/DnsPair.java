package io.github.privatednsfallback;

import java.util.Objects;

final class DnsPair {
  final String mode, host;

  DnsPair(String mode, String host) {
    this.mode = mode;
    this.host = host;
  }

  boolean same(DnsPair other) {
    return Objects.equals(mode, other.mode) && Objects.equals(host, other.host);
  }

  void requireUnchanged(DnsPair actual) {
    if (!same(actual)) throw new SecurityException("DNS setting changed");
  }

  enum Recovery {
    COMPLETE,
    UNCHANGED,
    HOST_ONLY,
    EXTERNAL
  }

  static Recovery recover(DnsPair before, DnsPair target, DnsPair actual) {
    if (target.same(actual)) return Recovery.COMPLETE;
    if (before.same(actual)) return Recovery.UNCHANGED;
    if ("hostname".equals(target.mode)
        && !"hostname".equals(before.mode)
        && Objects.equals(actual.mode, before.mode)
        && Objects.equals(actual.host, target.host)) return Recovery.HOST_ONLY;
    return Recovery.EXTERNAL;
  }
}
