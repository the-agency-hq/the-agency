/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;
import module org.lattejava.json;
import module org.lattejava.version;

/**
 * The contents of a source repository's {@code the-agency-hq-settings.json}. Currently just the SemVer version of
 * the source layout, which starts at {@code 1.0.0}.
 *
 * <p>{@code @JSONField(asString)} carries the version through {@link Version}'s own {@code String} constructor, so
 * a malformed version is rejected while the file is being read rather than by a second parse downstream of it.
 * {@code version} is null only when the member is absent from the file entirely — the one malformed shape the
 * codec cannot reject on its own, because there is nothing for it to convert.
 */
@JSON
public record SourceSettings(@JSONField(asString = true) Version version) {
}
