/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
module dev.theagencyhq.agency.tests {
  requires dev.theagencyhq.agency;
  requires java.net.http;
  requires java.sql;
  requires org.jooq;
  requires org.lattejava.database;
  requires org.lattejava.fusionauth;
  requires org.lattejava.http;
  requires org.lattejava.jwt;
  requires org.lattejava.web;
  requires org.testng;

  opens dev.theagencyhq.agency.tests to org.testng;
  opens dev.theagencyhq.agency.tests.github to org.testng;
  opens dev.theagencyhq.agency.tests.service to org.testng;
  opens dev.theagencyhq.agency.tests.service.translation to org.testng;
}
