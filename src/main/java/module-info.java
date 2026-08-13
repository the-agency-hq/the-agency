/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
module dev.theagencyhq.agency {
  requires com.zaxxer.hikari;
  requires gg.jte;
  requires gg.jte.runtime;
  requires java.net.http;
  requires java.sql;
  requires org.jooq;
  requires org.lattejava.database;
  requires org.lattejava.http;
  requires org.lattejava.jwt;
  requires org.lattejava.version;
  requires org.lattejava.web;
  requires org.postgresql.jdbc;

  requires static org.lattejava.json;

  exports dev.theagencyhq.agency;
  exports dev.theagencyhq.agency.controller;
  exports dev.theagencyhq.agency.db;
  exports dev.theagencyhq.agency.db.jooq;
  exports dev.theagencyhq.agency.error;
  exports dev.theagencyhq.agency.github;
  exports dev.theagencyhq.agency.model;
  exports dev.theagencyhq.agency.model.api;
  exports dev.theagencyhq.agency.model.github;
  exports dev.theagencyhq.agency.model.view;
  exports dev.theagencyhq.agency.service;
  exports dev.theagencyhq.agency.service.validation;
  exports dev.theagencyhq.agency.util;

  // jOOQ reflectively instantiates the generated table-record classes.
  opens dev.theagencyhq.agency.db.jooq.tables.records to org.jooq;
}
