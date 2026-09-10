/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model.gitlab;

import module org.lattejava.json;

/**
 * The slice of a GitLab project object the Agency reads, from {@code GET /projects?membership=true}. GitLab returns
 * dozens of members per project even in its {@code simple} form; these two are the ones the picker needs, and
 * declaring only them keeps the codec from having an opinion about the rest.
 *
 * @param pathWithNamespace {@code group/subgroup/project}, which is how GitLab names a project everywhere an
 *                          operator has seen one, and so what the picker shows and what the form posts back.
 * @param defaultBranch     The branch a source registers against unless the operator names another. {@code null}
 *                          for an empty project.
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record GitLabProject(String pathWithNamespace, String defaultBranch) {
}
