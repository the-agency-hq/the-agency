/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.util;

import module java.base;

/**
 * SHA-256 helpers. Every checksum in The Agency is SHA-256, hex-encoded lowercase.
 */
public final class Checksums {
  private Checksums() {
  }

  public static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform but is unavailable", e);
    }
  }
}
