/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.view;

import module java.base;

import dev.theagencyhq.agency.model.BriefFile;
import dev.theagencyhq.agency.model.Organization;

/**
 * One file within a Brief version. {@code text} is {@code false} for a base64-encoded (binary) file, which is
 * never rendered inline — only its size and a download link are shown.
 */
public record BriefFileView(Organization organization, int version, BriefFile file, boolean text, int size) {
}
