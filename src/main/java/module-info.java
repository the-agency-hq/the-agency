/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
import io.avaje.inject.InjectModule;

// The configuration is supplied to the scope by Main rather than built by a bean, so the generator is told not to
// look for a provider of it.
@InjectModule(name = "agency", requires = org.lattejava.web.Configuration.class)
module dev.theagencyhq.agency {
  requires com.zaxxer.hikari;
  requires gg.jte;
  requires gg.jte.runtime;
  requires io.avaje.inject;
  requires io.avaje.applog;
  requires jakarta.inject;
  requires java.net.http;
  requires java.sql;
  requires org.jooq;
  requires org.jspecify;
  requires org.lattejava.database;
  requires org.lattejava.fusionauth;
  requires org.lattejava.http;
  requires org.lattejava.jwt;
  requires org.lattejava.version;
  requires org.lattejava.web;
  requires org.postgresql.jdbc;

  requires static org.lattejava.json;

  exports dev.theagencyhq.agency;
  exports dev.theagencyhq.agency.bitbucket;
  exports dev.theagencyhq.agency.controller;
  exports dev.theagencyhq.agency.db;
  exports dev.theagencyhq.agency.db.jooq;
  exports dev.theagencyhq.agency.error;
  exports dev.theagencyhq.agency.github;
  exports dev.theagencyhq.agency.gitlab;
  exports dev.theagencyhq.agency.model;
  exports dev.theagencyhq.agency.model.api;
  exports dev.theagencyhq.agency.model.bitbucket;
  exports dev.theagencyhq.agency.model.github;
  exports dev.theagencyhq.agency.model.gitlab;
  exports dev.theagencyhq.agency.model.view;
  exports dev.theagencyhq.agency.security;
  exports dev.theagencyhq.agency.service;
  exports dev.theagencyhq.agency.service.translation;
  exports dev.theagencyhq.agency.service.validation;
  exports dev.theagencyhq.agency.source;
  exports dev.theagencyhq.agency.util;

  // jOOQ reflectively instantiates the generated table-record classes.
  opens dev.theagencyhq.agency.db.jooq.tables.records to org.jooq;

  provides io.avaje.inject.spi.InjectExtension with dev.theagencyhq.agency.AgencyModule;
}
