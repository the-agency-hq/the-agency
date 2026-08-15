# Brief Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build milestone 1 of The Agency — turn a Git working tree on the local machine into a versioned Brief and serve it to Handlers over `POST /api/v1/briefing`.

**Architecture:** A poller runs `git pull` against each registered source Path, a pure `BriefBuilder` maps the source layout to a list of Brief files, a content checksum decides whether that build becomes a new immutable version row, and a pure `BriefingService` decides what each Handler is told. An unauthenticated JTE admin UI registers sources and inspects the results.

**Tech Stack:** Java 25, Latte Web 0.6.0, Latte JSON 0.4.1 (`@JSON` compile-time codegen), Latte Database 0.1.0 (migrations), JTE 3.2.1, jOOQ 3.21.4, HikariCP 7.0.2, PostgreSQL 42.7.11, TestNG 7.12.0. Built with `latte`, not Maven/Gradle.

**Spec:** `docs/design/2026-07-30-brief-pipeline-design.md`. Read it before starting. Section references below (§4, §8.6, …) point into it.

## Global Constraints

Every task's requirements implicitly include this section.

- **DO NOT COMMIT AND DO NOT PUSH.** The user reviews the working tree at the end. No `git commit`, no `git push`, no `git add`. This overrides the commit steps that TDD skills normally require.
- **Java 25.** Module imports (`import module java.base;`), `var`, switch expressions, pattern matching over sealed types, unnamed variables (`_`), virtual threads. No reflection.
- **SPDX copyright header** is the first thing in every `.java` file including `module-info.java`, with no blank line above it:
  ```java
  /*
   * Copyright (c) 2026 The Agency HQ
   * SPDX-License-Identifier: MIT
   */
  ```
- **2-space indent**, 4-space continuation indent, 120-column target. Do not wrap before 120.
- **Acronyms are fully uppercase** in identifiers: `toJSON()`, `theAgencyURL`, `SQLDialect`. Never `Json`, `Url`, `Http`. If an identifier starts with an acronym, lowercase the whole acronym: `jsonBytes`.
- **Alphabetize** fields, methods (within visibility group), imports, `requires`, `exports`, `opens`, enum constants, and dependencies in `project.latte`. Class member order: static fields, instance fields, constructors, static methods, instance methods, inner classes.
- **No blank lines between field declarations.**
- **Runtime values in error and log messages go in square brackets**: `"Unknown encoding [" + encoding + "]"`. Never quotes.
- **Prefer module imports** (`import module java.base;`) over class imports.
- **Records normalize in their compact constructor** (trim, lowercase, null-to-empty).
- **`System.Logger`** for all logging. Never log a bearer token or a database password.
- **No new dependencies** beyond those listed in Task 1.
- **Test framework is TestNG**, not JUnit. Tests live in `src/test/java/dev/theagencyhq/agency/tests/` and that package is `opens ... to org.testng`.

## Environment (already verified)

- PostgreSQL is running on `127.0.0.1:5432` with a `dev` role (password `dev`).
- `git` 2.50.1 and the `latte` CLI are on the PATH.
- Databases `agency`, `agency_test`, and `agency_schema` do **not** exist yet — Task 1 creates them.

## File Structure

```
.javaversion                                          Task 1  — pins Java 25 for javaenv
project.latte                                         Task 1  — deps, plugins, targets
src/main/resources/config.properties                  Task 1  — default config
src/test/resources/config.properties                  Task 1  — points tests at agency_test
src/main/java/module-info.java                        Task 1  — grows in Tasks 2, 3, 12
src/main/java/dev/theagencyhq/agency/Main.java        Task 1  — grows in Tasks 10, 12

src/main/resources/db/0.1.0.sql                       Task 2  — schema
src/main/jooq/codegen.xml                             Task 2  — jOOQ generator config
src/main/java/.../agency/db/DatabaseService.java      Task 2  — pool + migrate + DSL; queries in Task 8
src/main/java/.../agency/db/jooq/**                   Task 2  — GENERATED, do not hand-edit

src/main/java/.../agency/api/BriefOrganization.java   Task 3
src/main/java/.../agency/api/BriefFile.java           Task 3
src/main/java/.../agency/api/BriefContent.java        Task 3
src/main/java/.../agency/api/Brief.java               Task 3
src/main/java/.../agency/api/CurrentVersion.java      Task 3
src/main/java/.../agency/api/BriefingRequest.java     Task 3
src/main/java/.../agency/util/Checksums.java          Task 3

src/main/java/.../agency/service/MissionTypeResolver.java  Task 4
src/main/java/.../agency/service/OutputPaths.java          Task 5
src/main/java/.../agency/service/BriefBuildException.java  Task 5
src/main/java/.../agency/service/BriefBuilder.java         Task 6
src/main/java/.../agency/service/GitService.java           Task 7

src/main/java/.../agency/model/Organization.java      Task 8
src/main/java/.../agency/model/BriefSource.java       Task 8
src/main/java/.../agency/model/SourceStatus.java      Task 8
src/main/java/.../agency/model/BriefVersion.java      Task 8

src/main/java/.../agency/service/BriefingOutcome.java Task 9
src/main/java/.../agency/service/BriefingService.java Task 9

src/main/java/.../agency/controller/BriefingController.java Task 10

src/main/java/.../agency/error/ValidationException.java     Task 11
src/main/java/.../agency/service/validation/OrganizationValidator.java Task 11
src/main/java/.../agency/service/OrganizationService.java   Task 11
src/main/java/.../agency/service/PollerService.java         Task 11
src/main/java/.../agency/service/Services.java              Task 11

src/main/java/.../agency/controller/OrganizationController.java Task 12
src/main/java/.../agency/model/view/*.java                      Task 12
web/templates/**                                                Task 12
```

`.../agency/` abbreviates `src/main/java/dev/theagencyhq/agency/`.

---

### Task 1: Build scaffolding, configuration, and databases

**Files:**
- Create: `.javaversion`
- Modify: `project.latte` (full replacement)
- Create: `src/main/resources/config.properties`
- Create: `src/test/resources/config.properties`
- Modify: `src/main/java/module-info.java`
- Modify: `src/main/java/dev/theagencyhq/agency/Main.java`
- Modify: `src/test/java/dev/theagencyhq/agency/tests/MainTest.java`

**Interfaces:**
- Produces: `Main.config` (a `org.lattejava.web.Configuration`), `Main.PORT`, `Main.BASE_DIR`, `Main.REQUIRED_CONFIG`.

- [ ] **Step 1: Pin the Java version**

Create `.javaversion` containing exactly:

```
25
```

- [ ] **Step 2: Replace `project.latte`**

The `semanticVersions` block and the `r2dbc-spi` / `reactive-streams` compile dependencies are not optional — jOOQ's `module-info` declares them `requires static`, so javac needs them on the compile module path, and Latte cannot resolve r2dbc's non-semantic `1.0.0.RELEASE` version without the mapping.

```groovy
project(group: "dev.theagencyhq", name: "agency", version: "0.1.0", licenses: ["MIT"]) {
  workflow {
    standard()

    // jOOQ declares Maven dependencies with non-semantic versions (io.r2dbc:r2dbc-spi + its parent POM).
    // jOOQ's module-info `requires` r2dbc.spi, so it must be present; these mappings let Latte resolve it.
    semanticVersions {
      mapping(id: "io.r2dbc:r2dbc-spi:1.0.0.RELEASE", version: "1.0.0")
      mapping(id: "io.r2dbc:r2dbc-spi-parent:1.0.0.RELEASE", version: "1.0.0")
    }
  }
  publishWorkflow {
    latte()
  }

  dependencies {
    group(name: "compile-processors") {
      dependency(id: "org.lattejava:json:0.4.1")
    }
    group(name: "compile") {
      dependency(id: "com.zaxxer:HikariCP:7.0.2")
      dependency(id: "gg.jte:jte:3.2.1")
      dependency(id: "gg.jte:jte-runtime:3.2.1")
      // jOOQ's module-info `requires static` r2dbc-spi and reactive-streams, so javac needs them on the
      // compile module-path. They are optional in jOOQ's POM and so are not resolved transitively.
      dependency(id: "io.r2dbc:r2dbc-spi:1.0.0.RELEASE")
      dependency(id: "org.jooq:jooq:3.21.4")
      dependency(id: "org.lattejava:database:0.1.0")
      dependency(id: "org.lattejava:http:0.3.0")
      dependency(id: "org.lattejava:web:0.6.0")
      dependency(id: "org.postgresql:postgresql:42.7.11")
      dependency(id: "org.reactivestreams:reactive-streams:1.0.4")
    }
    // Build-only jOOQ code-generation tooling. Never compiled into the app; assembled into a classpath
    // only by the `codegen` target below.
    group(name: "codegen", export: false) {
      dependency(id: "jakarta.activation:jakarta.activation-api:2.1.3")
      dependency(id: "jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
      dependency(id: "io.r2dbc:r2dbc-spi:1.0.0.RELEASE")
      dependency(id: "org.jooq:jooq:3.21.4")
      dependency(id: "org.jooq:jooq-codegen:3.21.4")
      dependency(id: "org.jooq:jooq-meta:3.21.4")
      dependency(id: "org.postgresql:postgresql:42.7.11")
      dependency(id: "org.reactivestreams:reactive-streams:1.0.4")
    }
    group(name: "test-compile", export: false) {
      dependency(id: "org.testng:testng:7.12.0")
    }
  }

  publications {
    standard()
  }
}

// Plugins
database = loadPlugin(id: "org.lattejava.plugin:database:0.5.0-{integration}")
dependency = loadPlugin(id: "org.lattejava.plugin:dependency:0.4.0")
idea = loadPlugin(id: "org.lattejava.plugin:idea:0.4.1")
java = loadPlugin(id: "org.lattejava.plugin:java:0.4.4")
javaTestNG = loadPlugin(id: "org.lattejava.plugin:java-testng:0.4.0")
release = loadPlugin(id: "org.lattejava.plugin:release-git:0.4.0")

// Plugin settings
database.settings.type = "postgresql"
java.settings.javaVersion = "25"
javaTestNG.settings.javaVersion = "25"

target(name: "clean", description: "Cleans the project") {
  java.clean()
}

target(name: "build", description: "Compiles and JARs the project", dependsOn: ["codegen"]) {
  java.compile()
  java.jar()
}

target(name: "test", description: "Runs the project's tests", dependsOn: ["build", "test-database"]) {
  javaTestNG.test()
}

target(name: "run", description: "Runs the web server", dependsOn: ["build"]) {
  java.run(main: "dev.theagencyhq.agency.Main")
}

target(name: "main-database", description: "Creates/recreates the main database. The app applies the SQL migrations on startup.") {
  database.createMainDatabase()
}

target(name: "test-database", description: "Creates/recreates the test database. The app applies the SQL migrations on startup.") {
  database.createTestDatabase()
}

target(name: "codegen", description: "Generates the jOOQ classes from the live `agency_schema` database") {
  // Generate against a separate database so the main and test databases are never disrupted
  database.settings.name = "agency_schema"
  database.createDatabase()
  database.migrate()

  java.run(
      main: "org.jooq.codegen.GenerationTool",
      arguments: "src/main/jooq/codegen.xml",
      jvmArguments: "--add-modules jakarta.xml.bind,r2dbc.spi,org.reactivestreams --add-opens org.jooq.meta/org.jooq.meta.jaxb=org.jooq",
      dependencies: [[group: "codegen", transitive: true, fetchSource: false, transitiveGroups: ["compile", "runtime"]]]
  )
}

target(name: "int", description: "Releases a local integration build of the project", dependsOn: ["test"]) {
  dependency.integrate()
}

target(name: "release", description: "Releases a full version of the project", dependsOn: ["clean", "test"]) {
  release.release()
}

target(name: "idea", description: "Updates the IntelliJ IDEA module file") {
  idea.iml()
}

target(name: "print-dependency-tree", description: "Prints the dependency tree") {
  dependency.printFull()
}
```

- [ ] **Step 3: Create the config files**

`src/main/resources/config.properties`:

```properties
db.password=dev
db.url=jdbc:postgresql://127.0.0.1:5432/agency
db.username=dev
handler.tokens=dev-token
poller.intervalSeconds=60
```

`src/test/resources/config.properties`:

```properties
# Points the tests at the test database and a fixed token
db.url=jdbc:postgresql://127.0.0.1:5432/agency_test
handler.tokens=test-token
poller.intervalSeconds=3600
```

The test poller interval is deliberately an hour: every test that needs a build triggers it explicitly, so a
background cycle can never race a test's assertions.

- [ ] **Step 4: Update `module-info.java`**

```java
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
  requires org.lattejava.web;
  requires org.postgresql.jdbc;

  requires static org.lattejava.json;

  exports dev.theagencyhq.agency;
}
```

- [ ] **Step 5: Rewrite `Main.java` to load configuration**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

@SuppressWarnings("resource")
public class Main {
  public static final Path BASE_DIR = Path.of("web");
  public static final int PORT = 8080;
  public static final List<String> REQUIRED_CONFIG = List.of("db.password", "db.url", "db.username", "handler.tokens");
  public final Configuration config;
  public final JTETemplates templates;
  public final Web web;

  public Main() {
    this(false);
  }

  public Main(boolean test) {
    this.config = new Configuration(
        REQUIRED_CONFIG,
        Path.of(System.getProperty("user.home"), ".config", "the-agency-hq", "agency", "config.properties"),
        test ? Path.of("src/test/resources/config.properties") : Path.of("non-existent"),
        Path.of("src/main/resources/config.properties")
    );
    this.templates = new JTETemplates(Path.of("web/templates"), Path.of("build"));
    this.web = new Web();
  }

  public void close() {
    web.close();
  }

  public void main() {
    web.install(SecurityHeaders.defaults())
       .baseDir(BASE_DIR)
       .files("/static")
       .get("/", templates::html)
       .start(PORT);
  }
}
```

- [ ] **Step 6: Update `MainTest.java` to boot in test mode**

Change only the field initializer so the test config chain is used:

```java
public Main main = new Main(true);
```

- [ ] **Step 7: Create the databases**

Run: `latte main-database && latte test-database`
Expected: both succeed, creating `agency` and `agency_test`.

- [ ] **Step 8: Verify the build**

Run: `latte clean && latte build`
Expected: BUILD SUCCESS. `codegen` runs first and will fail at this point because `src/main/jooq/codegen.xml` and the migration do not exist yet — that is expected. **If `codegen` fails, temporarily remove `dependsOn: ["codegen"]` from the `build` target, confirm `latte build` succeeds, then restore it.** Task 2 makes `codegen` work and Task 2's final step re-verifies the full chain.

---

### Task 2: Database schema, jOOQ codegen, and `DatabaseService`

**Files:**
- Create: `src/main/resources/db/0.1.0.sql`
- Create: `src/main/jooq/codegen.xml`
- Create: `src/main/java/dev/theagencyhq/agency/db/DatabaseService.java`
- Modify: `src/main/java/module-info.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/DatabaseServiceTest.java`

**Interfaces:**
- Consumes: `Main.config` from Task 1.
- Produces: `new DatabaseService(Configuration)`, `DatabaseService.close()`, `DatabaseService.dsl()` returning `org.jooq.DSLContext`. Generated jOOQ tables at `dev.theagencyhq.agency.db.jooq.Tables.{ORGANIZATIONS, BRIEF_SOURCES, BRIEFS}`.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/0.1.0.sql`. Copy §12 of the design exactly:

```sql
-- Initial PostgreSQL schema for The Agency.
--
-- Migrations in this directory are applied from the classpath by org.lattejava.database's Migrator when the app
-- starts (see DatabaseService). Files are named <semver>.sql and applied in SemVer order; each applied file is
-- recorded in the `versions` table with its SHA-256 checksum, so an applied migration must NEVER be edited -- add a
-- new, higher-versioned file instead. Timestamps are epoch-millis stored as BIGINT (mapped to java.time.Instant by
-- a jOOQ forced-type converter); enums are TEXT + CHECK (mapped to the Java enum by a forced-type converter).

CREATE TABLE organizations (
  id              UUID PRIMARY KEY,
  name            TEXT   NOT NULL,
  insert_instant  BIGINT NOT NULL,
  update_instant  BIGINT NOT NULL
);

-- Organization names are unique case-insensitively and first-come-first-serve, per idea.md.
CREATE UNIQUE INDEX organizations_uk_name ON organizations (LOWER(name));

-- Exactly one source per Organization (design decision 7).
CREATE TABLE brief_sources (
  id                   UUID PRIMARY KEY,
  organization_id      UUID   NOT NULL UNIQUE REFERENCES organizations (id) ON DELETE CASCADE,
  path                 TEXT   NOT NULL UNIQUE,
  last_built_commit    TEXT,
  last_polled_instant  BIGINT,
  last_status          TEXT   CHECK (last_status IN ('BUILD_FAILED', 'NOT_A_REPOSITORY', 'OK', 'UNCHANGED')),
  last_error           TEXT,
  last_pull_error      TEXT,
  insert_instant       BIGINT NOT NULL,
  update_instant       BIGINT NOT NULL
);

-- Insert-only version history. `document` is TEXT and not JSONB on purpose: JSONB reorders keys and normalizes
-- whitespace, so the document read back would not be the document written, and the Briefing API serves these
-- bytes verbatim.
CREATE TABLE briefs (
  id               UUID   PRIMARY KEY,
  organization_id  UUID   NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
  version          INT    NOT NULL,
  checksum         TEXT   NOT NULL,
  document         TEXT   NOT NULL,
  source_commit    TEXT,
  insert_instant   BIGINT NOT NULL,
  UNIQUE (organization_id, version)
);

CREATE INDEX briefs_idx_organization_version ON briefs (organization_id, version DESC);
```

- [ ] **Step 2: Write the jOOQ codegen config**

Create `src/main/jooq/codegen.xml`. The forced types use **lambda converters** so the generator never needs the
project's own classes on its classpath — that avoids the bootstrap problem where generated code would be required
to compile the classes the generator depends on.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  jOOQ code-generation configuration. Run via the `codegen` build target, which creates and migrates the
  `agency_schema` database and writes typed classes into dev.theagencyhq.agency.db.jooq.
-->
<configuration xmlns="http://www.jooq.org/xsd/jooq-codegen-3.0.0.xsd">
  <jdbc>
    <driver>org.postgresql.Driver</driver>
    <url>jdbc:postgresql://127.0.0.1:5432/agency_schema</url>
    <user>dev</user>
    <password>dev</password>
  </jdbc>

  <generator>
    <database>
      <name>org.jooq.meta.postgres.PostgresDatabase</name>
      <inputSchema>public</inputSchema>
      <includes>.*</includes>
      <excludes>versions</excludes>

      <forcedTypes>
        <!-- All epoch-millis BIGINT timestamp columns -> Instant. -->
        <forcedType>
          <userType>java.time.Instant</userType>
          <lambdaConverter>
            <from>t -&gt; t == null ? null : java.time.Instant.ofEpochMilli(t)</from>
            <to>u -&gt; u == null ? null : u.toEpochMilli()</to>
          </lambdaConverter>
          <includeExpression>.*\.(insert_instant|update_instant|last_polled_instant)</includeExpression>
        </forcedType>

        <!-- brief_sources.last_status -> SourceStatus -->
        <forcedType>
          <userType>dev.theagencyhq.agency.model.SourceStatus</userType>
          <lambdaConverter>
            <from>t -&gt; t == null ? null : dev.theagencyhq.agency.model.SourceStatus.valueOf(t)</from>
            <to>u -&gt; u == null ? null : u.name()</to>
          </lambdaConverter>
          <includeExpression>.*\.brief_sources\.last_status</includeExpression>
        </forcedType>
      </forcedTypes>
    </database>

    <generate>
      <pojos>false</pojos>
      <daos>false</daos>
      <interfaces>false</interfaces>
      <javadoc>true</javadoc>
    </generate>

    <target>
      <packageName>dev.theagencyhq.agency.db.jooq</packageName>
      <directory>src/main/java</directory>
      <clean>true</clean>
    </target>
  </generator>
</configuration>
```

- [ ] **Step 3: Create the `SourceStatus` enum now**

The codegen config references it, and generated code will not compile without it. Create
`src/main/java/dev/theagencyhq/agency/model/SourceStatus.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

/**
 * The outcome of the most recent poll cycle for a Brief source.
 */
public enum SourceStatus {
  BUILD_FAILED,
  NOT_A_REPOSITORY,
  OK,
  UNCHANGED
}
```

- [ ] **Step 4: Run codegen**

Run: `latte codegen`
Expected: BUILD SUCCESS, and `src/main/java/dev/theagencyhq/agency/db/jooq/` now exists containing `Tables.java`,
`tables/Organizations.java`, `tables/BriefSources.java`, `tables/Briefs.java` and a `tables/records/` directory.
Verify with: `find src/main/java/dev/theagencyhq/agency/db/jooq -name "*.java" | head -20`

Generated files are **never hand-edited**. If the schema changes, add a new migration and re-run `latte codegen`.

- [ ] **Step 5: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/DatabaseServiceTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.db.DatabaseService;

import static dev.theagencyhq.agency.db.jooq.Tables.ORGANIZATIONS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

@Test
public class DatabaseServiceTest {
  @Test
  public void migratesAndQueries() {
    var config = new Configuration(
        List.of("db.password", "db.url", "db.username"),
        Path.of("src/test/resources/config.properties"),
        Path.of("src/main/resources/config.properties")
    );

    var service = new DatabaseService(config);
    try {
      assertNotNull(service.dsl());

      // The migration ran, so the table exists and is queryable
      assertEquals(service.dsl().selectCount().from(ORGANIZATIONS).fetchOne(0, int.class).intValue(), 0);
    } finally {
      service.close();
    }
  }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.DatabaseServiceTest`
Expected: FAIL — `DatabaseService` does not exist (compilation error).

- [ ] **Step 7: Write `DatabaseService`**

Create `src/main/java/dev/theagencyhq/agency/db/DatabaseService.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.db;

import module java.base;
import module java.sql;
import module org.lattejava.database;
import module org.lattejava.web;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * PostgreSQL-backed data access, implemented with jOOQ over a HikariCP connection pool. This service owns the
 * persistence setup entirely — it builds the data source and jOOQ context from the {@code db.*} configuration and
 * applies any pending classpath migrations ({@code db/*.sql}) at construction time — so no other class touches
 * connections or the persistence technology directly.
 */
public class DatabaseService {
  private static final System.Logger logger = System.getLogger(DatabaseService.class.getName());
  private final HikariDataSource dataSource;
  private final DSLContext dsl;

  public DatabaseService(Configuration config) {
    var hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(config.get("db.url"));
    hikariConfig.setUsername(config.get("db.username"));
    hikariConfig.setPassword(config.get("db.password"));
    hikariConfig.setPoolName("the-agency");
    this.dataSource = new HikariDataSource(hikariConfig);

    try (Connection connection = dataSource.getConnection()) {
      var applied = new Migrator(connection, "db").migrate();
      if (!applied.isEmpty()) {
        logger.log(System.Logger.Level.INFO, "Applied database migrations [" + applied + "]");
      }
    } catch (MigrationException | SQLException e) {
      throw new IllegalStateException("Unable to migrate the database [" + config.get("db.url") + "]", e);
    }

    this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
  }

  public void close() {
    dataSource.close();
  }

  public DSLContext dsl() {
    return dsl;
  }
}
```

- [ ] **Step 8: Update `module-info.java`**

Add these lines in alphabetical position:

```java
  exports dev.theagencyhq.agency.db;
  exports dev.theagencyhq.agency.model;

  // jOOQ reflectively instantiates the generated table-record classes.
  opens dev.theagencyhq.agency.db.jooq.tables.records to org.jooq;
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.DatabaseServiceTest`
Expected: PASS.

- [ ] **Step 10: Verify the full build chain**

If Task 1 Step 8 temporarily removed `dependsOn: ["codegen"]` from the `build` target, restore it now.
Run: `latte clean && latte test`
Expected: BUILD SUCCESS with all tests passing.

---

### Task 3: Wire records and the checksum utility

The Handler is already shipped and parses these exact shapes. Getting a key name or a default wrong here breaks
every machine in the fleet silently. Cross-check against `../handler/src/main/java/dev/theagencyhq/handler/brief/`
and the fixtures in `../handler/src/test/resources/agency/`.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/api/{BriefOrganization,BriefFile,BriefContent,Brief,CurrentVersion,BriefingRequest}.java`
- Create: `src/main/java/dev/theagencyhq/agency/util/Checksums.java`
- Create: `src/test/resources/agency/briefing-updated.json` (copied from the Handler)
- Create: `src/test/java/dev/theagencyhq/agency/tests/WireContractTest.java`
- Modify: `src/main/java/module-info.java`

**Interfaces:**
- Produces:
  - `record BriefOrganization(String id, String name)`
  - `record BriefFile(String path, String encoding, String mode, String content, String checksum, List<String> missionTypes)`
  - `record BriefContent(BriefOrganization organization, List<BriefFile> files)`
  - `record Brief(String checksum, BriefOrganization organization, int version, List<BriefFile> files)`
  - `record CurrentVersion(String organizationId, int version, String checksum)`
  - `record BriefingRequest(List<CurrentVersion> currentVersions)`
  - `Checksums.sha256Hex(byte[]) -> String` (lowercase hex)
  - Generated companions at `dev.theagencyhq.agency.api.internal.{Type}JSON` with static `toJSON`, `toJSONBytes`, `fromJSON`.

- [ ] **Step 1: Copy the Handler's frozen fixture**

Run: `mkdir -p src/test/resources/agency && cp ../handler/src/test/resources/agency/briefing-updated.json src/test/resources/agency/`

- [ ] **Step 2: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/WireContractTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.api.Brief;
import dev.theagencyhq.agency.api.BriefFile;
import dev.theagencyhq.agency.api.BriefOrganization;
import dev.theagencyhq.agency.api.BriefingRequest;
import dev.theagencyhq.agency.api.CurrentVersion;
import dev.theagencyhq.agency.api.internal.BriefJSON;
import dev.theagencyhq.agency.api.internal.BriefingRequestJSON;
import dev.theagencyhq.agency.util.Checksums;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test
public class WireContractTest {
  @Test
  public void briefFileDefaults() {
    var file = new BriefFile(" a.md ", null, null, null, null, null);
    assertEquals(file.path(), "a.md");
    assertEquals(file.encoding(), "text");
    assertEquals(file.mode(), "0400");
    assertEquals(file.content(), "");
    assertEquals(file.missionTypes(), List.of());
  }

  @Test
  public void briefSerializesInContractKeyOrder() {
    var brief = new Brief("opaque-42-73", new BriefOrganization("42", "FusionAuth"), 73,
        List.of(new BriefFile(".claude/rules/foo.md", "text", "0400", "For Claude",
            "7b0464d7d419e8e21902270f71ec5e809ba2d1af68aea0df38f4e4913366a1b8", List.of("Web", "Library"))));

    var json = BriefJSON.toJSON(brief);

    // Key order must match the Handler's record declaration order
    assertTrue(json.indexOf("\"checksum\"") < json.indexOf("\"organization\""), json);
    assertTrue(json.indexOf("\"organization\"") < json.indexOf("\"version\""), json);
    assertTrue(json.indexOf("\"version\"") < json.indexOf("\"files\""), json);
    assertTrue(json.contains("\"missionTypes\":[\"Web\",\"Library\"]"), json);
  }

  @Test
  public void checksumIsLowercaseHex() {
    // Well-known SHA-256 of the empty input
    assertEquals(Checksums.sha256Hex(new byte[0]),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  public void parsesAHandlerRequest() {
    var request = BriefingRequestJSON.fromJSON(
        "{\"currentVersions\":[{\"organizationId\":\"42\",\"version\":73,\"checksum\":\"abc\"}]}"
            .getBytes(StandardCharsets.UTF_8));

    assertEquals(request.currentVersions(), List.of(new CurrentVersion("42", 73, "abc")));
  }

  @Test
  public void parsesTheHandlersFrozenFixture() throws Exception {
    var bytes = Files.readAllBytes(Path.of("src/test/resources/agency/briefing-updated.json"));
    var text = new String(bytes, StandardCharsets.UTF_8);

    // Every key the Agency must emit appears in the frozen fixture
    for (String key : List.of("organizationIds", "briefs", "checksum", "organization", "version", "files",
        "path", "encoding", "mode", "content", "missionTypes")) {
      assertTrue(text.contains("\"" + key + "\""), "Fixture is missing key [" + key + "]");
    }
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.WireContractTest`
Expected: FAIL — the `api` and `util` packages do not exist.

- [ ] **Step 4: Write the records**

`src/main/java/dev/theagencyhq/agency/api/BriefOrganization.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.api;

import module java.base;
import module org.lattejava.json;

/**
 * The Organization as it appears on the wire inside a Brief. Deliberately separate from the domain
 * {@code Organization} model: this shape is frozen by the Handler's contract and must never gain a member.
 */
@JSON
public record BriefOrganization(String id, String name) {
  public BriefOrganization {
    id = id == null ? "" : id.trim();
    name = name == null ? "" : name.trim();
  }
}
```

`src/main/java/dev/theagencyhq/agency/api/BriefFile.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.api;

import module java.base;
import module org.lattejava.json;

/**
 * One file in a Brief. The {@code checksum} is SHA-256 of the decoded bytes, hex-encoded lowercase — the Handler
 * verifies it and refuses to store a Brief that fails, so an incorrect value silently stalls the whole fleet.
 */
@JSON
public record BriefFile(String path, String encoding, String mode, String content, String checksum,
                        List<String> missionTypes) {
  public static final String DEFAULT_ENCODING = "text";
  public static final String DEFAULT_MODE = "0400";
  public static final String ENCODING_BASE64 = "base64";

  public BriefFile {
    path = path == null ? "" : path.trim();
    encoding = encoding == null || encoding.isBlank() ? DEFAULT_ENCODING : encoding.trim().toLowerCase(Locale.ROOT);
    mode = mode == null || mode.isBlank() ? DEFAULT_MODE : mode.trim();
    content = content == null ? "" : content;
    checksum = checksum == null ? "" : checksum.trim().toLowerCase(Locale.ROOT);
    missionTypes = missionTypes == null ? List.of() : List.copyOf(missionTypes);
  }
}
```

`src/main/java/dev/theagencyhq/agency/api/BriefContent.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.api;

import module java.base;
import module org.lattejava.json;

/**
 * The checksum input for a Brief: everything that makes a Brief what it is, with the version and the checksum
 * itself deliberately excluded. That makes the checksum purely content-addressed, so identical content always
 * produces an identical checksum regardless of how many versions came before it.
 */
@JSON
public record BriefContent(BriefOrganization organization, List<BriefFile> files) {
  public BriefContent {
    files = files == null ? List.of() : List.copyOf(files);
  }
}
```

`src/main/java/dev/theagencyhq/agency/api/Brief.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.api;

import module java.base;
import module org.lattejava.json;

/**
 * A Brief as the Handler receives it. Member declaration order is the wire key order and matches the Handler's
 * own record.
 */
@JSON
public record Brief(String checksum, BriefOrganization organization, int version, List<BriefFile> files) {
  public Brief {
    checksum = checksum == null ? "" : checksum.trim();
    files = files == null ? List.of() : List.copyOf(files);
  }
}
```

`src/main/java/dev/theagencyhq/agency/api/CurrentVersion.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.api;

import module java.base;
import module org.lattejava.json;

/**
 * One entry in a Handler's assertion of what it currently holds.
 */
@JSON
public record CurrentVersion(String organizationId, int version, String checksum) {
  public CurrentVersion {
    organizationId = organizationId == null ? "" : organizationId.trim();
    checksum = checksum == null ? "" : checksum.trim();
  }
}
```

`src/main/java/dev/theagencyhq/agency/api/BriefingRequest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.api;

import module java.base;
import module org.lattejava.json;

/**
 * The body of {@code POST /api/v1/briefing}.
 */
@JSON
public record BriefingRequest(List<CurrentVersion> currentVersions) {
  public BriefingRequest {
    currentVersions = currentVersions == null ? List.of() : List.copyOf(currentVersions);
  }
}
```

- [ ] **Step 5: Write `Checksums`**

`src/main/java/dev/theagencyhq/agency/util/Checksums.java`:

```java
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
```

- [ ] **Step 6: Update `module-info.java`**

Add in alphabetical position:

```java
  exports dev.theagencyhq.agency.api;
  exports dev.theagencyhq.agency.util;
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.WireContractTest`
Expected: PASS, 5 tests.

---

### Task 4: `MissionTypeResolver`

Implements design §8.3. First match wins: a sibling `<file>.mission-types`, then the nearest ancestor directory's
`.mission-types`, then the empty list.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/service/MissionTypeResolver.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/MissionTypeResolverTest.java`
- Modify: `src/main/java/module-info.java`

**Interfaces:**
- Produces: `new MissionTypeResolver(Path sourceRoot)` and `List<String> resolve(Path file) throws IOException`.
  Returned entries are trimmed, non-blank, de-duplicated, in file order, **original case preserved**.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/MissionTypeResolverTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.service.MissionTypeResolver;

import static org.testng.Assert.assertEquals;

@Test
public class MissionTypeResolverTest {
  private Path root;

  @AfterMethod
  public void afterMethod() throws IOException {
    if (root != null) {
      try (var walk = Files.walk(root)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    root = Files.createDirectories(Path.of("build/test/mission-types-" + UUID.randomUUID()));
  }

  @Test
  public void blankLinesAndDuplicatesAreDropped() throws Exception {
    write("skills/.mission-types", "Web\n\n  \nWeb\nLibrary\n");
    var file = write("skills/a.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Web", "Library"));
  }

  @Test
  public void directoryFileAppliesToSubdirectories() throws Exception {
    write("skills/.mission-types", "Web\n");
    var file = write("skills/skill1/scripts/run.sh", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Web"));
  }

  @Test
  public void nearerDirectoryFileWins() throws Exception {
    write("skills/.mission-types", "Web\n");
    write("skills/skill1/.mission-types", "Library\n");
    var file = write("skills/skill1/SKILL.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Library"));
  }

  @Test
  public void noFileMeansEveryMissionType() throws Exception {
    var file = write("rules/a.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of());
  }

  @Test
  public void originalCaseIsPreserved() throws Exception {
    write("rules/.mission-types", "Web\nLIBRARY\n");
    var file = write("rules/a.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Web", "LIBRARY"));
  }

  @Test
  public void siblingFileBeatsDirectoryFile() throws Exception {
    write("skills/.mission-types", "Web\n");
    write("skills/SKILL.md.mission-types", "Library\nFramework\n");
    var file = write("skills/SKILL.md", "x");
    assertEquals(new MissionTypeResolver(root).resolve(file), List.of("Library", "Framework"));
  }

  private Path write(String relative, String content) throws IOException {
    var path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    return path;
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.MissionTypeResolverTest`
Expected: FAIL — `MissionTypeResolver` does not exist.

- [ ] **Step 3: Write `MissionTypeResolver`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

/**
 * Resolves the Mission Types that apply to a source file, per the design's §8.3. First match wins: a sibling
 * {@code <file>.mission-types}, then the nearest ancestor directory's {@code .mission-types}, then the empty list
 * (which means "applies to every Mission Type").
 *
 * <p>Original case is preserved. Both the Agency and the Handler lowercase before comparing, so matching is
 * case-insensitive by construction without this class having to normalize.
 */
public class MissionTypeResolver {
  public static final String DIRECTORY_FILE = ".mission-types";
  public static final String SUFFIX = ".mission-types";
  private final Map<Path, List<String>> directoryCache = new HashMap<>();
  private final Path sourceRoot;

  public MissionTypeResolver(Path sourceRoot) {
    this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
  }

  public List<String> resolve(Path file) throws IOException {
    var absolute = file.toAbsolutePath().normalize();

    var sibling = absolute.resolveSibling(absolute.getFileName().toString() + SUFFIX);
    if (Files.isRegularFile(sibling)) {
      return read(sibling);
    }

    for (var directory = absolute.getParent();
         directory != null && directory.startsWith(sourceRoot);
         directory = directory.getParent()) {
      var types = cachedDirectoryTypes(directory);
      if (types != null) {
        return types;
      }
    }

    return List.of();
  }

  private List<String> cachedDirectoryTypes(Path directory) throws IOException {
    if (directoryCache.containsKey(directory)) {
      return directoryCache.get(directory);
    }

    var file = directory.resolve(DIRECTORY_FILE);
    var types = Files.isRegularFile(file) ? read(file) : null;
    directoryCache.put(directory, types);
    return types;
  }

  private List<String> read(Path file) throws IOException {
    return Files.readAllLines(file, StandardCharsets.UTF_8)
                .stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .distinct()
                .toList();
  }
}
```

- [ ] **Step 4: Update `module-info.java`**

Add in alphabetical position:

```java
  exports dev.theagencyhq.agency.service;
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.MissionTypeResolverTest`
Expected: PASS, 6 tests.

---

### Task 5: `OutputPaths` — path mapping and validation

Implements design §8.4 and §8.6. The validation rules mirror the Handler's planner exactly; a Brief that violates
them makes the Handler reject the *entire* plan, so one bad file silently stops that Organization updating on every
machine. Failing here turns a fleet-wide silent stall into one visible error.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/service/BriefBuildException.java`
- Create: `src/main/java/dev/theagencyhq/agency/service/OutputPaths.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/OutputPathsTest.java`

**Interfaces:**
- Produces:
  - `class BriefBuildException extends RuntimeException` with `(String message)` and `(String message, Throwable cause)`.
  - `OutputPaths.AGENT_TYPES` — `List<AgentType>` where `record AgentType(String name, String outputRoot)`.
  - `OutputPaths.SHARED_DIRECTORIES` — `List<String>`.
  - `static List<String> map(String sourceRelativePath)` — returns output paths, empty if the source path is not mapped.
  - `static void validate(String outputPath)` — throws `BriefBuildException` on any violation.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/OutputPathsTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.service.BriefBuildException;
import dev.theagencyhq.agency.service.OutputPaths;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

@Test
public class OutputPathsTest {
  @DataProvider
  public Object[][] invalidPaths() {
    return new Object[][]{
        {""},
        {"a b.md"},
        {"a\nb.md"},
        {"ab.md"},
        {"/etc/passwd"},
        {"a/../b.md"},
        {"./a.md"},
        {".git/config"},
        {"tools/.git/config"},
        {"tools/.GIT/config"},
        {".handler-manifest"},
        {"a/.handler-manifest"},
        {"a/.HANDLER-MANIFEST"},
        {"a/notes.md.handler-tmp-xyz"}
    };
  }

  @Test
  public void escapeHatchesMapVerbatim() {
    assertEquals(OutputPaths.map("claude/settings.json"), List.of(".claude/settings.json"));
    assertEquals(OutputPaths.map("codex/config.toml"), List.of(".codex/config.toml"));
    assertEquals(OutputPaths.map("codex/rules/a.rule"), List.of(".codex/rules/a.rule"));
  }

  @Test(dataProvider = "invalidPaths")
  public void rejectsInvalidPaths(String path) {
    assertThrows(BriefBuildException.class, () -> OutputPaths.validate(path));
  }

  @Test
  public void sharedDirectoriesMapToEveryAgentType() {
    assertEquals(OutputPaths.map("skills/skill1/SKILL.md"),
        List.of(".claude/skills/skill1/SKILL.md", ".codex/skills/skill1/SKILL.md"));
    assertEquals(OutputPaths.map("rules/rule1.md"), List.of(".claude/rules/rule1.md", ".codex/rules/rule1.md"));
    assertEquals(OutputPaths.map("agents/agent1.md"), List.of(".claude/agents/agent1.md", ".codex/agents/agent1.md"));
  }

  @Test
  public void unmappedRootEntriesProduceNothing() {
    assertEquals(OutputPaths.map("README.md"), List.of());
    assertEquals(OutputPaths.map("the-agency-hq-settings.json"), List.of());
    assertEquals(OutputPaths.map("docs/thing.md"), List.of());
  }

  @Test
  public void validAcceptsOrdinaryPaths() {
    OutputPaths.validate(".claude/skills/skill1/SKILL.md");
    OutputPaths.validate(".codex/config.toml");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.OutputPathsTest`
Expected: FAIL — `OutputPaths` does not exist.

- [ ] **Step 3: Write `BriefBuildException`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

/**
 * Thrown when a Brief cannot be built from a source tree. A build is all-or-nothing — a Brief is never inserted
 * partially — so this always aborts the whole build for that Organization.
 */
public class BriefBuildException extends RuntimeException {
  public BriefBuildException(String message) {
    super(message);
  }

  public BriefBuildException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

- [ ] **Step 4: Write `OutputPaths`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

/**
 * Maps a source-relative path to the Brief output paths it produces, and validates those outputs.
 *
 * <p>The mapping is data, not code: adding an agent type is one entry in {@link #AGENT_TYPES}.
 *
 * <p>The validation rules mirror the Handler's planner exactly. A Brief that violates any of them makes the
 * Handler reject the entire plan for that Location, so publishing one would silently stop the Organization
 * updating on every machine in the fleet.
 */
public final class OutputPaths {
  public static final List<AgentType> AGENT_TYPES = List.of(
      new AgentType("claude", ".claude"),
      new AgentType("codex", ".codex"));
  public static final String MANIFEST_NAME = ".handler-manifest";
  public static final List<String> SHARED_DIRECTORIES = List.of("agents", "rules", "skills");
  public static final String TEMP_INFIX = ".handler-tmp-";

  private OutputPaths() {
  }

  public static List<String> map(String sourceRelativePath) {
    int slash = sourceRelativePath.indexOf('/');
    if (slash < 0) {
      return List.of();
    }

    var top = sourceRelativePath.substring(0, slash);
    var remainder = sourceRelativePath.substring(slash + 1);
    if (remainder.isEmpty()) {
      return List.of();
    }

    if (SHARED_DIRECTORIES.contains(top)) {
      return AGENT_TYPES.stream().map(a -> a.outputRoot() + "/" + top + "/" + remainder).toList();
    }

    return AGENT_TYPES.stream()
                      .filter(a -> a.name().equals(top))
                      .map(a -> a.outputRoot() + "/" + remainder)
                      .toList();
  }

  public static void validate(String outputPath) {
    if (outputPath.isEmpty()) {
      throw new BriefBuildException("A Brief file path is empty");
    }

    // Checked before splitting so a NUL byte surfaces as this failure rather than an InvalidPathException. A
    // newline is the dangerous one: the Handler's manifest and git-exclude writers are both line-oriented and
    // neither escapes, so an embedded newline injects a standalone line into the Handler's own bookkeeping.
    for (int i = 0; i < outputPath.length(); i++) {
      char c = outputPath.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        throw new BriefBuildException("Brief file path [" + outputPath + "] contains a control character");
      }
    }

    if (outputPath.startsWith("/")) {
      throw new BriefBuildException("Brief file path [" + outputPath + "] is absolute");
    }

    for (var segment : outputPath.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new BriefBuildException("Brief file path [" + outputPath + "] has an empty or relative segment");
      }

      // Lowercased, and every segment rather than only the first. macOS APFS is case-insensitive by default, so
      // `.GIT/hooks/pre-commit` IS `.git/hooks/pre-commit`, and a fabricated repository anywhere in the tree gives
      // git a repo-local core.pager / core.fsmonitor / alias.* that executes on the next git invocation.
      var lower = segment.toLowerCase(Locale.ROOT);
      if (lower.equals(".git")) {
        throw new BriefBuildException("Brief file path [" + outputPath + "] contains a [.git] segment");
      }
      if (lower.equals(MANIFEST_NAME)) {
        throw new BriefBuildException("Brief file path [" + outputPath + "] contains a [" + MANIFEST_NAME + "] segment");
      }
      if (lower.contains(TEMP_INFIX)) {
        throw new BriefBuildException("Brief file path [" + outputPath + "] contains the reserved infix [" + TEMP_INFIX + "]");
      }
    }
  }

  public record AgentType(String name, String outputRoot) {
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.OutputPathsTest`
Expected: PASS, 18 tests (14 from the data provider plus 4).

---

### Task 6: `BriefBuilder`

Implements design §8.1, §8.2, §8.5 and §9.2. Pure — no database, no network.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/service/BriefBuilder.java`
- Create: `src/main/java/dev/theagencyhq/agency/api/SourceSettings.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/BriefBuilderTest.java`

**Interfaces:**
- Consumes: `MissionTypeResolver` (Task 4), `OutputPaths` + `BriefBuildException` (Task 5), `BriefContent`, `BriefFile`, `BriefOrganization` (Task 3), `Checksums` (Task 3).
- Produces:
  - `record SourceSettings(String version)` — `@JSON`, parses `the-agency-hq-settings.json`.
  - `new BriefBuilder()`
  - `BriefContent build(BriefOrganization organization, Path sourceRoot)` — throws `BriefBuildException`.
  - `static String checksum(BriefContent content)` — SHA-256 hex of `BriefContentJSON.toJSONBytes(content)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/BriefBuilderTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.api.BriefFile;
import dev.theagencyhq.agency.api.BriefOrganization;
import dev.theagencyhq.agency.service.BriefBuildException;
import dev.theagencyhq.agency.service.BriefBuilder;
import dev.theagencyhq.agency.util.Checksums;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

@Test
public class BriefBuilderTest {
  private static final BriefOrganization ORG = new BriefOrganization("42", "fusionauth");
  private Path root;

  @AfterMethod
  public void afterMethod() throws IOException {
    if (root != null && Files.exists(root)) {
      try (var walk = Files.walk(root)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    root = Files.createDirectories(Path.of("build/test/brief-builder-" + UUID.randomUUID()));
    write("the-agency-hq-settings.json", "{\"version\":\"1.0.0\"}");
  }

  @Test
  public void binaryContentBecomesBase64() throws Exception {
    byte[] binary = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};
    var path = root.resolve("skills/logo.png");
    Files.createDirectories(path.getParent());
    Files.write(path, binary);

    var file = fileAt(new BriefBuilder().build(ORG, root).files(), ".claude/skills/logo.png");
    assertEquals(file.encoding(), "base64");
    assertEquals(Base64.getDecoder().decode(file.content()), binary);
    assertEquals(file.checksum(), Checksums.sha256Hex(binary));
  }

  @Test
  public void checksumIsDeterministicAndContentSensitive() throws Exception {
    write("rules/a.md", "A");
    write("rules/b.md", "B");
    var first = BriefBuilder.checksum(new BriefBuilder().build(ORG, root));
    var second = BriefBuilder.checksum(new BriefBuilder().build(ORG, root));
    assertEquals(first, second);

    write("rules/b.md", "B changed");
    assertNotEquals(BriefBuilder.checksum(new BriefBuilder().build(ORG, root)), first);
  }

  @Test
  public void executableSourceFilesBecomeMode0500() throws Exception {
    var script = write("skills/skill1/scripts/run.sh", "#!/bin/sh\necho hi\n");
    write("skills/skill1/SKILL.md", "skill");
    var permissions = new HashSet<>(Files.getPosixFilePermissions(script));
    permissions.add(PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(script, permissions);

    var files = new BriefBuilder().build(ORG, root).files();
    assertEquals(fileAt(files, ".claude/skills/skill1/scripts/run.sh").mode(), "0500");
    assertEquals(fileAt(files, ".codex/skills/skill1/scripts/run.sh").mode(), "0500");
    assertEquals(fileAt(files, ".claude/skills/skill1/SKILL.md").mode(), "0400");
  }

  @Test
  public void mapsSharedDirectoriesToBothAgentTypes() throws Exception {
    write("skills/skill1/SKILL.md", "skill");
    write("rules/rule1.md", "rule");
    write("agents/agent1.md", "agent");
    write("claude/settings.json", "{}");
    write("codex/config.toml", "x = 1");
    write("README.md", "ignored");

    var paths = new BriefBuilder().build(ORG, root).files().stream().map(BriefFile::path).toList();

    assertEquals(paths, List.of(
        ".claude/agents/agent1.md",
        ".claude/rules/rule1.md",
        ".claude/settings.json",
        ".claude/skills/skill1/SKILL.md",
        ".codex/agents/agent1.md",
        ".codex/config.toml",
        ".codex/rules/rule1.md",
        ".codex/skills/skill1/SKILL.md"));
  }

  @Test
  public void missionTypesAreAttachedToEveryDerivedFile() throws Exception {
    write("skills/.mission-types", "Web\nLibrary\n");
    write("skills/skill1/SKILL.md", "skill");

    var files = new BriefBuilder().build(ORG, root).files();
    assertEquals(fileAt(files, ".claude/skills/skill1/SKILL.md").missionTypes(), List.of("Web", "Library"));
    assertEquals(fileAt(files, ".codex/skills/skill1/SKILL.md").missionTypes(), List.of("Web", "Library"));
  }

  @Test
  public void missionTypeFilesAreNeverEmitted() throws Exception {
    write("skills/.mission-types", "Web\n");
    write("skills/SKILL.md.mission-types", "Library\n");
    write("skills/SKILL.md", "skill");

    var paths = new BriefBuilder().build(ORG, root).files().stream().map(BriefFile::path).toList();
    assertEquals(paths, List.of(".claude/skills/SKILL.md", ".codex/skills/SKILL.md"));
  }

  @Test
  public void rejectsAGitSegmentFromTheEscapeHatch() throws Exception {
    write("claude/.git/config", "[core]\n\tpager = touch /tmp/pwned\n");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void rejectsASymbolicLink() throws Exception {
    Files.createDirectories(root.resolve("rules"));
    Files.createSymbolicLink(root.resolve("rules/link.md"), Path.of("/etc/hosts"));
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void requiresTheSettingsMarker() throws Exception {
    Files.delete(root.resolve("the-agency-hq-settings.json"));
    write("rules/a.md", "x");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void rejectsAnUnsupportedSettingsMajorVersion() throws Exception {
    write("the-agency-hq-settings.json", "{\"version\":\"2.0.0\"}");
    write("rules/a.md", "x");
    assertThrows(BriefBuildException.class, () -> new BriefBuilder().build(ORG, root));
  }

  @Test
  public void textContentAndChecksum() throws Exception {
    write("rules/a.md", "For Claude");

    var file = fileAt(new BriefBuilder().build(ORG, root).files(), ".claude/rules/a.md");
    assertEquals(file.encoding(), "text");
    assertEquals(file.content(), "For Claude");
    assertEquals(file.mode(), "0400");
    assertEquals(file.checksum(), Checksums.sha256Hex("For Claude".getBytes(StandardCharsets.UTF_8)));
    assertTrue(file.missionTypes().isEmpty());
  }

  private BriefFile fileAt(List<BriefFile> files, String path) {
    return files.stream()
                .filter(f -> f.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Brief file at [" + path + "] in "
                                                      + files.stream().map(BriefFile::path).toList()));
  }

  private Path write(String relative, String content) throws IOException {
    var path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    return path;
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.BriefBuilderTest`
Expected: FAIL — `BriefBuilder` does not exist.

- [ ] **Step 3: Write `SourceSettings`**

`src/main/java/dev/theagencyhq/agency/api/SourceSettings.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.api;

import module java.base;
import module org.lattejava.json;

/**
 * The contents of a source repository's {@code the-agency-hq-settings.json}. Currently just the SemVer version of
 * the source layout, which starts at {@code 1.0.0}.
 */
@JSON
public record SourceSettings(String version) {
  public SourceSettings {
    version = version == null ? "" : version.trim();
  }
}
```

- [ ] **Step 4: Write `BriefBuilder`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.api.BriefContent;
import dev.theagencyhq.agency.api.BriefFile;
import dev.theagencyhq.agency.api.BriefOrganization;
import dev.theagencyhq.agency.api.internal.BriefContentJSON;
import dev.theagencyhq.agency.api.internal.SourceSettingsJSON;
import dev.theagencyhq.agency.util.Checksums;

/**
 * Turns a Brief source working tree into a {@link BriefContent}. Pure — it touches no database and no network, and
 * a build is all-or-nothing so a Brief is never inserted partially.
 */
public class BriefBuilder {
  public static final String SETTINGS_FILE = "the-agency-hq-settings.json";
  public static final int SUPPORTED_LAYOUT_MAJOR = 1;
  private static final System.Logger logger = System.getLogger(BriefBuilder.class.getName());

  /**
   * Computes a Brief's content-addressed checksum. {@link BriefContent} excludes both the version and the checksum
   * itself, so identical content always produces an identical checksum no matter how many versions came before it.
   *
   * @param content The content to checksum.
   * @return The SHA-256, hex-encoded lowercase.
   */
  public static String checksum(BriefContent content) {
    return Checksums.sha256Hex(BriefContentJSON.toJSONBytes(content));
  }

  public BriefContent build(BriefOrganization organization, Path sourceRoot) {
    var root = sourceRoot.toAbsolutePath().normalize();
    verifySettings(root);

    var resolver = new MissionTypeResolver(root);
    var files = new ArrayList<BriefFile>();
    var seen = new HashSet<String>();

    for (var top : mappedTopLevelDirectories()) {
      var directory = root.resolve(top);
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }

      collect(root, directory, resolver, files, seen);
    }

    files.sort(Comparator.comparing(BriefFile::path));
    return new BriefContent(organization, List.copyOf(files));
  }

  private static List<String> mappedTopLevelDirectories() {
    var directories = new ArrayList<>(OutputPaths.SHARED_DIRECTORIES);
    OutputPaths.AGENT_TYPES.forEach(a -> directories.add(a.name()));
    return directories;
  }

  private void collect(Path root, Path directory, MissionTypeResolver resolver, List<BriefFile> files,
                       Set<String> seen) {
    try (var stream = Files.newDirectoryStream(directory)) {
      var entries = new ArrayList<Path>();
      stream.forEach(entries::add);
      entries.sort(Comparator.comparing(p -> p.getFileName().toString()));

      for (var entry : entries) {
        // NOFOLLOW throughout: the planner validates path strings and cannot know that `docs -> /etc` exists, so a
        // link is the one construct that turns a valid relative path into a write outside the tree.
        if (Files.isSymbolicLink(entry)) {
          throw new BriefBuildException("The source tree contains a symbolic link [" + root.relativize(entry) + "]. "
                                        + "Links are not supported because they can resolve outside the tree.");
        }

        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
          collect(root, entry, resolver, files, seen);
          continue;
        }

        addFile(root, entry, resolver, files, seen);
      }
    } catch (IOException e) {
      throw new BriefBuildException("Unable to read the source directory [" + directory + "]", e);
    }
  }

  private void addFile(Path root, Path entry, MissionTypeResolver resolver, List<BriefFile> files, Set<String> seen)
      throws IOException {
    var name = entry.getFileName().toString();
    if (name.equals(MissionTypeResolver.DIRECTORY_FILE) || name.endsWith(MissionTypeResolver.SUFFIX)) {
      return;
    }

    var relative = root.relativize(entry).toString();
    var outputs = OutputPaths.map(relative);
    if (outputs.isEmpty()) {
      logger.log(System.Logger.Level.DEBUG, "Ignoring unmapped source file [" + relative + "]");
      return;
    }

    var bytes = Files.readAllBytes(entry);
    var encoded = encode(bytes);
    var checksum = Checksums.sha256Hex(bytes);
    var mode = Files.getPosixFilePermissions(entry, LinkOption.NOFOLLOW_LINKS)
                    .contains(PosixFilePermission.OWNER_EXECUTE) ? "0500" : "0400";
    var missionTypes = resolver.resolve(entry);

    for (var output : outputs) {
      OutputPaths.validate(output);
      if (!seen.add(output)) {
        throw new BriefBuildException("Two source files produce the same Brief file path [" + output + "]");
      }

      files.add(new BriefFile(output, encoded.encoding(), mode, encoded.content(), checksum, missionTypes));
    }
  }

  private Encoded encode(byte[] bytes) {
    // Strict decoding is the point: a lenient decoder replaces invalid bytes with U+FFFD, which would silently
    // corrupt every binary asset rather than routing it through base64.
    var decoder = StandardCharsets.UTF_8.newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return new Encoded(BriefFile.DEFAULT_ENCODING, decoder.decode(ByteBuffer.wrap(bytes)).toString());
    } catch (CharacterCodingException e) {
      return new Encoded(BriefFile.ENCODING_BASE64, Base64.getEncoder().encodeToString(bytes));
    }
  }

  private void verifySettings(Path root) {
    // This marker is the only thing distinguishing a Brief source repository from an arbitrary directory somebody
    // typed into the admin form, so pointing the Agency at the wrong repository must fail loudly here rather than
    // quietly publishing a Brief full of application source code.
    var settingsFile = root.resolve(SETTINGS_FILE);
    if (!Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new BriefBuildException("The source tree [" + root + "] has no [" + SETTINGS_FILE + "] file, so it is "
                                    + "not a Brief source repository");
    }

    String version;
    try {
      version = SourceSettingsJSON.fromJSON(Files.readAllBytes(settingsFile)).version();
    } catch (IOException | RuntimeException e) {
      throw new BriefBuildException("Unable to parse [" + SETTINGS_FILE + "] in [" + root + "]", e);
    }

    int major;
    try {
      major = Integer.parseInt(version.split("\\.")[0]);
    } catch (NumberFormatException e) {
      throw new BriefBuildException("The [" + SETTINGS_FILE + "] version [" + version + "] is not a SemVer version", e);
    }

    if (major != SUPPORTED_LAYOUT_MAJOR) {
      throw new BriefBuildException("The [" + SETTINGS_FILE + "] version [" + version + "] has an unsupported major "
                                    + "version. This Agency supports major version [" + SUPPORTED_LAYOUT_MAJOR + "]");
    }
  }

  private record Encoded(String encoding, String content) {
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.BriefBuilderTest`
Expected: PASS, 11 tests.

---

### Task 7: `GitService`

Implements design §7.1.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/service/GitService.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/GitServiceTest.java`

**Interfaces:**
- Produces:
  - `record GitResult(int exitCode, String output)` with `boolean success()`.
  - `new GitService()`
  - `boolean isWorkTree(Path path)`
  - `GitResult pull(Path path)`
  - `Optional<String> head(Path path)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/GitServiceTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.service.GitService;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test
public class GitServiceTest {
  private Path root;

  @AfterMethod
  public void afterMethod() throws IOException {
    if (root != null && Files.exists(root)) {
      try (var walk = Files.walk(root)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    root = Files.createDirectories(Path.of("build/test/git-service-" + UUID.randomUUID()));
  }

  @Test
  public void headReturnsTheCommitSHA() throws Exception {
    initRepository();
    var head = new GitService().head(root);
    assertTrue(head.isPresent());
    assertEquals(head.get().length(), 40, head.get());
  }

  @Test
  public void headIsEmptyOutsideARepository() {
    assertTrue(new GitService().head(root).isEmpty());
  }

  @Test
  public void isWorkTreeDetectsARepository() throws Exception {
    assertFalse(new GitService().isWorkTree(root));
    initRepository();
    assertTrue(new GitService().isWorkTree(root));
  }

  @Test
  public void pullFailsWithNoRemoteButDoesNotThrow() throws Exception {
    initRepository();
    var result = new GitService().pull(root);
    assertFalse(result.success());
    assertFalse(result.output().isBlank());
  }

  private void initRepository() throws Exception {
    run("git", "init", "-q", "-b", "main");
    run("git", "config", "user.email", "test@theagencyhq.dev");
    run("git", "config", "user.name", "Test");
    run("git", "config", "commit.gpgsign", "false");
    Files.writeString(root.resolve("README.md"), "hello\n");
    run("git", "add", ".");
    run("git", "commit", "-q", "-m", "initial");
  }

  private void run(String... command) throws Exception {
    var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(process.waitFor(), 0, String.join(" ", command) + " -> " + output);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.GitServiceTest`
Expected: FAIL — `GitService` does not exist.

- [ ] **Step 3: Write `GitService`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

/**
 * A thin wrapper around the {@code git} CLI. Every command uses {@code git -C <path>} rather than a working
 * directory, folds stderr into stdout, and is bounded by a timeout.
 *
 * <p>{@code pull} uses {@code --ff-only} deliberately: The Agency must never create a merge commit in a
 * developer's repository. A source that has diverged fails the pull and keeps serving from the current HEAD.
 */
public class GitService {
  public static final Duration PULL_TIMEOUT = Duration.ofSeconds(60);
  public static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);
  private static final System.Logger logger = System.getLogger(GitService.class.getName());

  public Optional<String> head(Path path) {
    var result = run(QUERY_TIMEOUT, path, "rev-parse", "HEAD");
    if (!result.success()) {
      return Optional.empty();
    }

    var sha = result.output().trim();
    return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
  }

  public boolean isWorkTree(Path path) {
    if (!Files.isDirectory(path)) {
      return false;
    }

    var result = run(QUERY_TIMEOUT, path, "rev-parse", "--is-inside-work-tree");
    return result.success() && result.output().trim().equals("true");
  }

  public GitResult pull(Path path) {
    return run(PULL_TIMEOUT, path, "pull", "--ff-only");
  }

  private GitResult run(Duration timeout, Path path, String... arguments) {
    var command = new ArrayList<String>(List.of("git", "-C", path.toString()));
    command.addAll(List.of(arguments));

    Process process = null;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return new GitResult(-1, "The command [" + String.join(" ", command) + "] timed out after [" + timeout + "]");
      }

      return new GitResult(process.exitValue(), output);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (process != null) {
        process.destroyForcibly();
      }
      return new GitResult(-1, "The command [" + String.join(" ", command) + "] was interrupted");
    } catch (IOException e) {
      logger.log(System.Logger.Level.DEBUG, "Unable to run [" + String.join(" ", command) + "]", e);
      return new GitResult(-1, "Unable to run [" + String.join(" ", command) + "]: " + e.getMessage());
    }
  }

  public record GitResult(int exitCode, String output) {
    public boolean success() {
      return exitCode == 0;
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.GitServiceTest`
Expected: PASS, 4 tests.

---

### Task 8: Domain models and database queries

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/model/{Organization,BriefSource,BriefVersion}.java`
- Modify: `src/main/java/dev/theagencyhq/agency/db/DatabaseService.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/BriefRepositoryTest.java`

**Interfaces:**
- Consumes: `SourceStatus` (Task 2), generated jOOQ tables (Task 2).
- Produces on `DatabaseService`:
  - `void deleteOrganization(UUID id)`
  - `Optional<Organization> findOrganization(UUID id)`
  - `Optional<Organization> findOrganizationByName(String name)`
  - `Optional<BriefSource> findSource(UUID organizationId)`
  - `Optional<BriefSource> findSourceByPath(String path)`
  - `Optional<BriefVersion> findLatestBrief(UUID organizationId)`
  - `Optional<BriefVersion> findBrief(UUID organizationId, int version)`
  - `int insertBrief(UUID id, UUID organizationId, String checksum, IntFunction<String> document, String sourceCommit, Instant insertInstant)` — assigns `MAX(version) + 1` and calls `document` with the version actually assigned, so the stored JSON's embedded `version` can never disagree with its row. Returns the assigned version.
  - `void insertOrganization(Organization organization)`
  - `void insertSource(BriefSource source)`
  - `List<Organization> listOrganizations()`
  - `List<BriefSource> listSources()`
  - `List<BriefVersion> listBriefSummaries(UUID organizationId)` — `document` is `""` in these rows
  - `Map<UUID, BriefVersion> latestBriefs()`
  - `void updateSourceStatus(UUID organizationId, String lastBuiltCommit, Instant lastPolledInstant, SourceStatus status, String lastError, String lastPullError)`

- [ ] **Step 1: Write the models**

`src/main/java/dev/theagencyhq/agency/model/Organization.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * An Organization. Names are lowercased on construction because they are unique case-insensitively and
 * first-come-first-serve, per idea.md.
 */
public record Organization(UUID id, String name, Instant insertInstant, Instant updateInstant) {
  public Organization {
    name = name == null ? null : name.trim().toLowerCase(Locale.ROOT);
  }
}
```

`src/main/java/dev/theagencyhq/agency/model/BriefSource.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * The local Git working tree that a Brief is built from. Exactly one per Organization.
 *
 * <p>{@code lastError} and {@code lastPullError} are separate because a cycle can legitimately have both a failed
 * pull and a successful build — the common case being a local test repository with no remote configured at all.
 */
public record BriefSource(UUID id, UUID organizationId, String path, String lastBuiltCommit,
                          Instant lastPolledInstant, SourceStatus lastStatus, String lastError, String lastPullError,
                          Instant insertInstant, Instant updateInstant) {
  public BriefSource {
    path = path == null ? null : path.trim();
  }
}
```

`src/main/java/dev/theagencyhq/agency/model/BriefVersion.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.model;

import module java.base;

/**
 * One immutable row in a Brief's version history. {@code document} is the exact JSON the Briefing API serves; it is
 * empty on rows fetched by a summary query.
 */
public record BriefVersion(UUID id, UUID organizationId, int version, String checksum, String document,
                           String sourceCommit, Instant insertInstant) {
}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/BriefRepositoryTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.BriefVersion;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.SourceStatus;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test
public class BriefRepositoryTest {
  private DatabaseService database;

  @AfterClass
  public void afterClass() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeClass
  public void beforeClass() {
    database = new DatabaseService(new Configuration(
        List.of("db.password", "db.url", "db.username"),
        Path.of("src/test/resources/config.properties"),
        Path.of("src/main/resources/config.properties")));
  }

  @Test
  public void insertsAndReadsTheWholeGraph() {
    var now = Instant.ofEpochMilli(1_700_000_000_000L);
    var organization = new Organization(UUID.randomUUID(), "Acme-" + UUID.randomUUID(), now, now);
    database.insertOrganization(organization);

    assertEquals(database.findOrganization(organization.id()).orElseThrow().name(), organization.name());
    assertEquals(database.findOrganizationByName(organization.name().toUpperCase(Locale.ROOT))
                         .orElseThrow().id(), organization.id());

    var source = new BriefSource(UUID.randomUUID(), organization.id(), "/tmp/" + UUID.randomUUID(), null, null,
        null, null, null, now, now);
    database.insertSource(source);
    assertEquals(database.findSource(organization.id()).orElseThrow().path(), source.path());

    database.updateSourceStatus(organization.id(), "abc123", now, SourceStatus.OK, null, "no remote");
    var updated = database.findSource(organization.id()).orElseThrow();
    assertEquals(updated.lastBuiltCommit(), "abc123");
    assertEquals(updated.lastStatus(), SourceStatus.OK);
    assertEquals(updated.lastPullError(), "no remote");

    assertEquals(database.insertBrief(UUID.randomUUID(), organization.id(), "sum-1",
        v -> "{\"version\":" + v + "}", "abc123", now), 1);
    assertEquals(database.insertBrief(UUID.randomUUID(), organization.id(), "sum-2",
        v -> "{\"version\":" + v + "}", "def456", now), 2);

    var latest = database.findLatestBrief(organization.id()).orElseThrow();
    assertEquals(latest.version(), 2);
    assertEquals(latest.checksum(), "sum-2");
    assertEquals(latest.document(), "{\"version\":2}");

    assertEquals(database.listBriefSummaries(organization.id()).stream().map(BriefVersion::version).toList(),
        List.of(2, 1));
    assertEquals(database.findBrief(organization.id(), 1).orElseThrow().document(), "{\"version\":1}");
    assertTrue(database.latestBriefs().containsKey(organization.id()));

    database.deleteOrganization(organization.id());
    assertTrue(database.findOrganization(organization.id()).isEmpty());
    assertTrue(database.findLatestBrief(organization.id()).isEmpty());
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.BriefRepositoryTest`
Expected: FAIL — the query methods do not exist.

- [ ] **Step 4: Add the queries to `DatabaseService`**

Add these imports (alphabetized with the existing ones):

```java
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.BriefVersion;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.model.SourceStatus;

import static dev.theagencyhq.agency.db.jooq.Tables.BRIEFS;
import static dev.theagencyhq.agency.db.jooq.Tables.BRIEF_SOURCES;
import static dev.theagencyhq.agency.db.jooq.Tables.ORGANIZATIONS;
```

Add these members. Keep the class's ordering convention: static methods before instance methods, each group
alphabetized.

```java
  private static BriefSource toSource(org.jooq.Record record) {
    return new BriefSource(
        record.get(BRIEF_SOURCES.ID),
        record.get(BRIEF_SOURCES.ORGANIZATION_ID),
        record.get(BRIEF_SOURCES.PATH),
        record.get(BRIEF_SOURCES.LAST_BUILT_COMMIT),
        record.get(BRIEF_SOURCES.LAST_POLLED_INSTANT),
        record.get(BRIEF_SOURCES.LAST_STATUS),
        record.get(BRIEF_SOURCES.LAST_ERROR),
        record.get(BRIEF_SOURCES.LAST_PULL_ERROR),
        record.get(BRIEF_SOURCES.INSERT_INSTANT),
        record.get(BRIEF_SOURCES.UPDATE_INSTANT));
  }

  private static Organization toOrganization(org.jooq.Record record) {
    return new Organization(
        record.get(ORGANIZATIONS.ID),
        record.get(ORGANIZATIONS.NAME),
        record.get(ORGANIZATIONS.INSERT_INSTANT),
        record.get(ORGANIZATIONS.UPDATE_INSTANT));
  }

  private static BriefVersion toVersion(org.jooq.Record record, boolean withDocument) {
    return new BriefVersion(
        record.get(BRIEFS.ID),
        record.get(BRIEFS.ORGANIZATION_ID),
        record.get(BRIEFS.VERSION),
        record.get(BRIEFS.CHECKSUM),
        withDocument ? record.get(BRIEFS.DOCUMENT) : "",
        record.get(BRIEFS.SOURCE_COMMIT),
        record.get(BRIEFS.INSERT_INSTANT));
  }

  public void deleteOrganization(UUID id) {
    dsl.deleteFrom(ORGANIZATIONS).where(ORGANIZATIONS.ID.eq(id)).execute();
  }

  public Optional<BriefVersion> findBrief(UUID organizationId, int version) {
    return dsl.selectFrom(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .and(BRIEFS.VERSION.eq(version))
              .fetchOptional(r -> toVersion(r, true));
  }

  public Optional<BriefVersion> findLatestBrief(UUID organizationId) {
    return dsl.selectFrom(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .orderBy(BRIEFS.VERSION.desc())
              .limit(1)
              .fetchOptional(r -> toVersion(r, true));
  }

  public Optional<Organization> findOrganization(UUID id) {
    return dsl.selectFrom(ORGANIZATIONS).where(ORGANIZATIONS.ID.eq(id)).fetchOptional(DatabaseService::toOrganization);
  }

  public Optional<Organization> findOrganizationByName(String name) {
    return dsl.selectFrom(ORGANIZATIONS)
              .where(DSL.lower(ORGANIZATIONS.NAME).eq(name == null ? null : name.trim().toLowerCase(Locale.ROOT)))
              .fetchOptional(DatabaseService::toOrganization);
  }

  public Optional<BriefSource> findSource(UUID organizationId) {
    return dsl.selectFrom(BRIEF_SOURCES)
              .where(BRIEF_SOURCES.ORGANIZATION_ID.eq(organizationId))
              .fetchOptional(DatabaseService::toSource);
  }

  public Optional<BriefSource> findSourceByPath(String path) {
    return dsl.selectFrom(BRIEF_SOURCES).where(BRIEF_SOURCES.PATH.eq(path)).fetchOptional(DatabaseService::toSource);
  }

  /**
   * Inserts a new Brief version, assigning {@code MAX(version) + 1} for the Organization. The unique constraint on
   * {@code (organization_id, version)} means two builds racing to claim the same number cannot both win; the loser
   * gets a constraint violation and its poll cycle fails, which the next cycle retries.
   *
   * <p>{@code document} is a function of the version rather than a string because the version number is embedded
   * in the JSON. Serializing it here, against the number actually assigned, makes it impossible for the stored
   * document's {@code version} to disagree with its row.
   *
   * @param id             The new row's id.
   * @param organizationId The owning Organization.
   * @param checksum       The content-addressed checksum.
   * @param document       Builds the Brief JSON for the version it is given.
   * @param sourceCommit   The Git commit the Brief was built from.
   * @param insertInstant  When the version was created.
   * @return The version that was assigned.
   */
  public int insertBrief(UUID id, UUID organizationId, String checksum, IntFunction<String> document,
                         String sourceCommit, Instant insertInstant) {
    var version = dsl.select(DSL.coalesce(DSL.max(BRIEFS.VERSION), 0).plus(1))
                     .from(BRIEFS)
                     .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
                     .fetchOne(0, int.class);

    dsl.insertInto(BRIEFS)
       .set(BRIEFS.ID, id)
       .set(BRIEFS.ORGANIZATION_ID, organizationId)
       .set(BRIEFS.VERSION, version)
       .set(BRIEFS.CHECKSUM, checksum)
       .set(BRIEFS.DOCUMENT, document.apply(version))
       .set(BRIEFS.SOURCE_COMMIT, sourceCommit)
       .set(BRIEFS.INSERT_INSTANT, insertInstant)
       .execute();

    return version;
  }

  public void insertOrganization(Organization organization) {
    dsl.insertInto(ORGANIZATIONS)
       .set(ORGANIZATIONS.ID, organization.id())
       .set(ORGANIZATIONS.NAME, organization.name())
       .set(ORGANIZATIONS.INSERT_INSTANT, organization.insertInstant())
       .set(ORGANIZATIONS.UPDATE_INSTANT, organization.updateInstant())
       .execute();
  }

  public void insertSource(BriefSource source) {
    dsl.insertInto(BRIEF_SOURCES)
       .set(BRIEF_SOURCES.ID, source.id())
       .set(BRIEF_SOURCES.ORGANIZATION_ID, source.organizationId())
       .set(BRIEF_SOURCES.PATH, source.path())
       .set(BRIEF_SOURCES.LAST_BUILT_COMMIT, source.lastBuiltCommit())
       .set(BRIEF_SOURCES.LAST_POLLED_INSTANT, source.lastPolledInstant())
       .set(BRIEF_SOURCES.LAST_STATUS, source.lastStatus())
       .set(BRIEF_SOURCES.LAST_ERROR, source.lastError())
       .set(BRIEF_SOURCES.LAST_PULL_ERROR, source.lastPullError())
       .set(BRIEF_SOURCES.INSERT_INSTANT, source.insertInstant())
       .set(BRIEF_SOURCES.UPDATE_INSTANT, source.updateInstant())
       .execute();
  }

  /**
   * @return The latest Brief version for every Organization that has one, keyed by Organization id. Documents are
   *     included, because this is what the Briefing API serves.
   */
  public Map<UUID, BriefVersion> latestBriefs() {
    var latest = new HashMap<UUID, BriefVersion>();
    dsl.selectFrom(BRIEFS)
       .orderBy(BRIEFS.ORGANIZATION_ID, BRIEFS.VERSION.desc())
       .fetch()
       .forEach(r -> latest.putIfAbsent(r.get(BRIEFS.ORGANIZATION_ID), toVersion(r, true)));
    return latest;
  }

  public List<BriefVersion> listBriefSummaries(UUID organizationId) {
    return dsl.select(BRIEFS.ID, BRIEFS.ORGANIZATION_ID, BRIEFS.VERSION, BRIEFS.CHECKSUM, BRIEFS.SOURCE_COMMIT,
                  BRIEFS.INSERT_INSTANT)
              .from(BRIEFS)
              .where(BRIEFS.ORGANIZATION_ID.eq(organizationId))
              .orderBy(BRIEFS.VERSION.desc())
              .fetch(r -> toVersion(r, false));
  }

  public List<Organization> listOrganizations() {
    return dsl.selectFrom(ORGANIZATIONS).orderBy(ORGANIZATIONS.NAME).fetch(DatabaseService::toOrganization);
  }

  public List<BriefSource> listSources() {
    return dsl.selectFrom(BRIEF_SOURCES).fetch(DatabaseService::toSource);
  }

  public void updateSourceStatus(UUID organizationId, String lastBuiltCommit, Instant lastPolledInstant,
                                 SourceStatus status, String lastError, String lastPullError) {
    dsl.update(BRIEF_SOURCES)
       .set(BRIEF_SOURCES.LAST_BUILT_COMMIT, lastBuiltCommit)
       .set(BRIEF_SOURCES.LAST_POLLED_INSTANT, lastPolledInstant)
       .set(BRIEF_SOURCES.LAST_STATUS, status)
       .set(BRIEF_SOURCES.LAST_ERROR, lastError)
       .set(BRIEF_SOURCES.LAST_PULL_ERROR, lastPullError)
       .set(BRIEF_SOURCES.UPDATE_INSTANT, Instant.now())
       .where(BRIEF_SOURCES.ORGANIZATION_ID.eq(organizationId))
       .execute();
  }
```

`listBriefSummaries` selects explicit columns and omits `document` because a version list page must not pull every
Brief's full JSON into memory.

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.BriefRepositoryTest`
Expected: PASS.

---

### Task 9: `BriefingService` — the pure decision

Implements design §10.2. Every branch here is load-bearing; read that section before writing code.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/service/BriefingOutcome.java`
- Create: `src/main/java/dev/theagencyhq/agency/service/BriefingService.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/BriefingServiceTest.java`

**Interfaces:**
- Consumes: `Organization`, `BriefVersion` (Task 8), `CurrentVersion` (Task 3).
- Produces:
  - `sealed interface BriefingOutcome` with `record NotModified()` and `record Updated(List<String> organizationIds, List<String> documents)`.
  - `BriefingOutcome decide(List<Organization> entitled, Map<UUID, BriefVersion> latest, List<CurrentVersion> asserted)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/BriefingServiceTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.agency.api.CurrentVersion;
import dev.theagencyhq.agency.model.BriefVersion;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.service.BriefingOutcome;
import dev.theagencyhq.agency.service.BriefingService;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test
public class BriefingServiceTest {
  private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
  private static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

  @Test
  public void aDeletedOrganizationForcesA200() {
    // The Handler still asserts ORG_B, but it is no longer entitled or deliverable. Without the set comparison
    // this would 304 and the Handler would keep serving a Brief forever.
    var outcome = new BriefingService().runBriefing(
        List.of(org(ORG_A, "a")),
        Map.of(ORG_A, brief(ORG_A, 1, "sum-a", "{\"a\":1}")),
        List.of(new CurrentVersion(ORG_A.toString(), 1, "sum-a"), new CurrentVersion(ORG_B.toString(), 3, "sum-b")));

    assertTrue(outcome instanceof BriefingOutcome.Updated);
    assertEquals(((BriefingOutcome.Updated) outcome).organizationIds(), List.of(ORG_A.toString()));
    assertTrue(((BriefingOutcome.Updated) outcome).documents().isEmpty());
  }

  @Test
  public void aaColdStoreReceivesEverything() {
    var outcome = new BriefingService().runBriefing(
        List.of(org(ORG_A, "a")),
        Map.of(ORG_A, brief(ORG_A, 1, "sum-a", "{\"a\":1}")),
        List.of());

    assertEquals(((BriefingOutcome.Updated) outcome).documents(), List.of("{\"a\":1}"));
    assertEquals(((BriefingOutcome.Updated) outcome).organizationIds(), List.of(ORG_A.toString()));
  }

  @Test
  public void anUnbuiltOrganizationDoesNotBlockA304() {
    // ORG_B is entitled but has no Brief, so it is not deliverable and must not make a 304 impossible forever.
    var outcome = new BriefingService().runBriefing(
        List.of(org(ORG_A, "a"), org(ORG_B, "b")),
        Map.of(ORG_A, brief(ORG_A, 1, "sum-a", "{\"a\":1}")),
        List.of(new CurrentVersion(ORG_A.toString(), 1, "sum-a")));

    assertTrue(outcome instanceof BriefingOutcome.NotModified, outcome.toString());
  }

  @Test
  public void currentEverythingIsNotModified() {
    var outcome = new BriefingService().runBriefing(
        List.of(org(ORG_A, "a")),
        Map.of(ORG_A, brief(ORG_A, 7, "sum-a", "{\"a\":1}")),
        List.of(new CurrentVersion(ORG_A.toString(), 7, "sum-a")));

    assertTrue(outcome instanceof BriefingOutcome.NotModified);
  }

  @Test
  public void unbuiltOrganizationsStillAppearInOrganizationIds() {
    var outcome = new BriefingService().runBriefing(
        List.of(org(ORG_A, "a"), org(ORG_B, "b")),
        Map.of(),
        List.of());

    assertEquals(((BriefingOutcome.Updated) outcome).organizationIds(),
        List.of(ORG_A.toString(), ORG_B.toString()));
  }

  @Test
  public void unparseableAssertedIdIsTreatedAsUnknown() {
    var outcome = new BriefingService().runBriefing(
        List.of(org(ORG_A, "a")),
        Map.of(ORG_A, brief(ORG_A, 1, "sum-a", "{\"a\":1}")),
        List.of(new CurrentVersion("not-a-uuid", 1, "x")));

    assertTrue(outcome instanceof BriefingOutcome.Updated);
  }

  @Test
  public void wrongChecksumResends() {
    var outcome = new BriefingService().runBriefing(
        List.of(org(ORG_A, "a")),
        Map.of(ORG_A, brief(ORG_A, 7, "sum-a", "{\"a\":1}")),
        List.of(new CurrentVersion(ORG_A.toString(), 7, "corrupt")));

    assertEquals(((BriefingOutcome.Updated) outcome).documents(), List.of("{\"a\":1}"));
  }

  @Test
  public void wrongVersionResends() {
    var outcome = new BriefingService().runBriefing(
        List.of(org(ORG_A, "a")),
        Map.of(ORG_A, brief(ORG_A, 7, "sum-a", "{\"a\":1}")),
        List.of(new CurrentVersion(ORG_A.toString(), 6, "sum-old")));

    assertEquals(((BriefingOutcome.Updated) outcome).documents(), List.of("{\"a\":1}"));
  }

  private BriefVersion brief(UUID organizationId, int version, String checksum, String document) {
    return new BriefVersion(UUID.randomUUID(), organizationId, version, checksum, document, "abc", NOW);
  }

  private Organization org(UUID id, String name) {
    return new Organization(id, name, NOW, NOW);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.BriefingServiceTest`
Expected: FAIL — `BriefingService` does not exist.

- [ ] **Step 3: Write `BriefingOutcome`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

/**
 * What the Briefing API should tell a Handler. Sealed so no caller can forget a case.
 */
public sealed interface BriefingOutcome {
  /**
   * Every version and checksum the Handler asserted is current, and its entitled set is unchanged. Answer 304.
   */
  record NotModified() implements BriefingOutcome {
  }

  /**
   * Answer 200. {@code organizationIds} is the complete entitled set — not a delta — because any Organization the
   * Handler holds but which is absent from it is treated as revoked and torn down. {@code documents} carries only
   * the Briefs that are stale, as their exact stored JSON text.
   */
  record Updated(List<String> organizationIds, List<String> documents) implements BriefingOutcome {
  }
}
```

- [ ] **Step 4: Write `BriefingService`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.api.CurrentVersion;
import dev.theagencyhq.agency.model.BriefVersion;
import dev.theagencyhq.agency.model.Organization;

/**
 * Decides what a Handler is told. A pure function over what the Handler asserted and what the database holds — no
 * I/O, no state — so the whole §10.2 matrix is testable without a database or a socket.
 */
public class BriefingService {
  /**
   * @param entitled The Organizations this Handler may receive Briefs for.
   * @param latest   The latest Brief version for every Organization that has one, keyed by Organization id.
   * @param asserted What the Handler says it currently holds.
   * @return The outcome.
   */
  public BriefingOutcome decide(List<Organization> entitled, Map<UUID, BriefVersion> latest,
                                List<CurrentVersion> asserted) {
    var entitledIds = entitled.stream().map(Organization::id).toList();

    // Deliverable = entitled AND has at least one built Brief. The distinction matters twice below.
    var deliverable = entitledIds.stream().filter(latest::containsKey).sorted().toList();

    var assertedByOrganization = new HashMap<UUID, CurrentVersion>();
    var assertedIds = new HashSet<UUID>();
    for (var current : asserted) {
      // An unparseable id cannot name an Organization we know about. Treating it as unknown rather than throwing
      // keeps a malformed assertion from failing the whole request; the set comparison below then forces a 200.
      UUID id;
      try {
        id = UUID.fromString(current.organizationId());
      } catch (IllegalArgumentException _) {
        assertedIds.add(new UUID(0L, 0L));
        continue;
      }

      assertedByOrganization.put(id, current);
      assertedIds.add(id);
    }

    var documents = new ArrayList<String>();
    for (var id : deliverable) {
      var brief = latest.get(id);
      var current = assertedByOrganization.get(id);
      if (current == null || current.version() != brief.version() || !current.checksum().equals(brief.checksum())) {
        documents.add(brief.document());
      }
    }

    // The set comparison is what makes revocation self-healing: without it, an Organization deleted from the
    // Agency while nothing else changed would 304 forever and the Handler would keep serving its Brief.
    if (documents.isEmpty() && assertedIds.equals(new HashSet<>(deliverable))) {
      return new BriefingOutcome.NotModified();
    }

    // organizationIds is `entitled`, not `deliverable`: a registered-but-unbuilt Organization is still one the
    // Handler is entitled to, and omitting it would make the Handler tear that Location down.
    return new BriefingOutcome.Updated(
        entitledIds.stream().map(UUID::toString).sorted().toList(),
        List.copyOf(documents));
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.BriefingServiceTest`
Expected: PASS, 8 tests.

---

### Task 10: `BriefingController` and the API route

Implements design §10.1 and §10.3.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/controller/BriefingController.java`
- Modify: `src/main/java/dev/theagencyhq/agency/Main.java`
- Modify: `src/main/java/module-info.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/BriefingAPITest.java`

**Interfaces:**
- Consumes: `BriefingService`, `BriefingOutcome` (Task 9), `DatabaseService` (Task 8), `BriefingRequest` (Task 3).
- Produces: `new BriefingController(DatabaseService, BriefingService, Set<String> tokens)` and
  `void briefing(HTTPRequest, HTTPResponse, BriefingRequest)`.

**Note:** `Services` does not exist until Task 11. Wire the controller in `Main` by constructing a `DatabaseService`
directly for now; Task 11 replaces that with `Services`.

- [ ] **Step 1: Write the shared test server**

Only one test class may bind port 8080, so the server lives in one place and every HTTP test uses it. Create
`src/test/java/dev/theagencyhq/agency/tests/TestServer.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import dev.theagencyhq.agency.Main;

/**
 * The single application instance shared by every HTTP test. TestNG runs test classes in one JVM and only one of
 * them can bind the port, so the server is started once, lazily, and torn down by a shutdown hook.
 */
public final class TestServer {
  private static Main main;

  private TestServer() {
  }

  public static synchronized Main start() {
    if (main == null) {
      var started = new Main(true);
      started.main();
      Runtime.getRuntime().addShutdownHook(new Thread(started::close));
      main = started;
    }

    return main;
  }
}
```

`MainTest` must also be changed to use it: delete its `@BeforeSuite`/`@AfterSuite` and its `main` field, and have
it call `TestServer.start()` from a `@BeforeClass`.

- [ ] **Step 2: Write the failing test**

The `WebTest` fluent API is: `withX(...)` methods return the `WebTest` and accumulate request state, the verb
method (`post(path)`) executes and returns a `WebTestAsserter`, and `reset()` clears the accumulated state for the
next call. `StringBodyAsserter` exposes `contains`, `doesNotContain`, `equalTo` and `matches`.

Create `src/test/java/dev/theagencyhq/agency/tests/BriefingAPITest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.Main;
import dev.theagencyhq.agency.model.Organization;

@Test
public class BriefingAPITest {
  public StringBodyAsserter string = new StringBodyAsserter();
  public WebTest test = new WebTest(Main.PORT);
  private Organization organization;

  @BeforeClass
  public void beforeClass() {
    var main = TestServer.start();
    var now = Instant.ofEpochMilli(1_700_000_000_000L);
    organization = new Organization(UUID.randomUUID(), "brief-api-" + UUID.randomUUID(), now, now);
    main.databaseService().insertOrganization(organization);
    main.databaseService().insertBrief(UUID.randomUUID(), organization.id(), "sum-1",
        v -> "{\"checksum\":\"sum-1\",\"organization\":{\"id\":\"" + organization.id() + "\",\"name\":\""
             + organization.name() + "\"},\"version\":" + v + ",\"files\":[]}", "abc", now);
  }

  @Test
  public void coldStoreReceivesTheBrief() {
    test.withHeader("Authorization", "Bearer test-token")
        .withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[]}")
        .post("/api/v1/briefing")
        .assertStatus(200)
        .assertBodyAs(string, b -> b.contains("\"checksum\":\"sum-1\"").contains("organizationIds"))
        .reset();
  }

  @Test
  public void currentVersionIsNotModified() {
    test.withHeader("Authorization", "Bearer test-token")
        .withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[{\"organizationId\":\"" + organization.id()
                  + "\",\"version\":1,\"checksum\":\"sum-1\"}]}")
        .post("/api/v1/briefing")
        .assertStatus(304)
        .reset();
  }

  @Test
  public void duplicateAssertionIsRejected() {
    var entry = "{\"organizationId\":\"" + organization.id() + "\",\"version\":1,\"checksum\":\"sum-1\"}";
    test.withHeader("Authorization", "Bearer test-token")
        .withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[" + entry + "," + entry + "]}")
        .post("/api/v1/briefing")
        .assertStatus(400)
        .reset();
  }

  @Test
  public void missingTokenIsUnauthorized() {
    test.withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[]}")
        .post("/api/v1/briefing")
        .assertStatus(401)
        .reset();
  }

  @Test
  public void unknownTokenIsUnauthorized() {
    test.withHeader("Authorization", "Bearer nope")
        .withHeader("Content-Type", "application/json")
        .withBody("{\"currentVersions\":[]}")
        .post("/api/v1/briefing")
        .assertStatus(401)
        .reset();
  }
}
```

`Main` needs a `public DatabaseService databaseService()` accessor for this test to seed data — Step 5 adds it.

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.BriefingAPITest`
Expected: FAIL — the route does not exist.

- [ ] **Step 4: Write `BriefingController`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.controller;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import dev.theagencyhq.agency.api.BriefingRequest;
import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.service.BriefingOutcome;
import dev.theagencyhq.agency.service.BriefingService;

/**
 * {@code POST /api/v1/briefing} — the endpoint every Handler polls.
 */
public class BriefingController {
  private static final String BEARER = "Bearer ";
  private static final System.Logger logger = System.getLogger(BriefingController.class.getName());
  private final BriefingService briefingService;
  private final DatabaseService databaseService;
  private final Set<String> tokens;

  public BriefingController(DatabaseService databaseService, BriefingService briefingService, Set<String> tokens) {
    this.briefingService = briefingService;
    this.databaseService = databaseService;
    this.tokens = Set.copyOf(tokens);
  }

  public void briefing(HTTPRequest req, HTTPResponse res, BriefingRequest body) throws Exception {
    if (!authenticated(req)) {
      res.setStatus(401);
      return;
    }

    var asserted = body == null ? List.<dev.theagencyhq.agency.api.CurrentVersion>of() : body.currentVersions();

    // Two contradictory assertions for one Organization cannot both be honored and picking a winner would silently
    // serve one of them, so the request is rejected instead.
    var ids = asserted.stream().map(c -> c.organizationId()).toList();
    if (new HashSet<>(ids).size() != ids.size()) {
      res.setStatus(400);
      return;
    }

    var outcome = briefingService.runBriefing(databaseService.listOrganizations(), databaseService.latestBriefs(), asserted);
    switch (outcome) {
      case BriefingOutcome.NotModified _ -> res.setStatus(304);
      case BriefingOutcome.Updated updated -> write(res, updated);
    }
  }

  /**
   * Compares against every configured token without short-circuiting, so neither the comparison nor the loop leaks
   * timing information about which token was close.
   */
  private boolean authenticated(HTTPRequest req) {
    var header = req.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      return false;
    }

    var presented = header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
    boolean matched = false;
    for (var token : tokens) {
      matched |= MessageDigest.isEqual(presented, token.getBytes(StandardCharsets.UTF_8));
    }

    return matched;
  }

  /**
   * Writes the envelope by concatenating the stored Brief documents rather than parsing and re-serializing them.
   * That avoids a full rebuild of every Brief on every changed cycle and guarantees the bytes the Handler stores
   * are exactly the bytes recorded when the version was built. The inputs are safe by construction: the ids are
   * UUIDs read from a uuid column and the documents are Agency-generated JSON.
   */
  private void write(HTTPResponse res, BriefingOutcome.Updated updated) throws IOException {
    var body = new StringBuilder("{\"organizationIds\":[");
    for (int i = 0; i < updated.organizationIds().size(); i++) {
      body.append(i == 0 ? "" : ",").append('"').append(updated.organizationIds().get(i)).append('"');
    }
    body.append("],\"briefs\":[");
    for (int i = 0; i < updated.documents().size(); i++) {
      body.append(i == 0 ? "" : ",").append(updated.documents().get(i));
    }
    body.append("]}");

    var bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    res.setStatus(200);
    res.setContentType("application/json");
    res.setContentLength(bytes.length);
    res.getOutputStream().write(bytes);

    logger.log(System.Logger.Level.DEBUG, "Briefing response with [" + updated.documents().size() + "] Briefs");
  }
}
```

- [ ] **Step 5: Wire the route in `Main`**

Add fields and construction, and register the route. Add to `Main`:

```java
  public final DatabaseService databaseService;
```

In the constructor, after `config` is built:

```java
    this.databaseService = new DatabaseService(config);
```

Add an accessor:

```java
  public DatabaseService databaseService() {
    return databaseService;
  }
```

In `main()`, register the route before `.start(PORT)`:

```java
    var briefing = new BriefingController(databaseService, new BriefingService(), tokens(config));
    web.post("/api/v1/briefing", briefing::briefing, BodySupplier.of(BriefingRequestJSON::fromJSON));
```

And add the helper:

```java
  private static Set<String> tokens(Configuration config) {
    var tokens = Arrays.stream(config.get("handler.tokens").split(","))
                       .map(String::trim)
                       .filter(t -> !t.isEmpty())
                       .collect(Collectors.toSet());
    if (tokens.isEmpty()) {
      // A server that accepts nothing is indistinguishable at runtime from a wrong token, so fail at startup
      throw new IllegalStateException("The [handler.tokens] configuration is empty, so no Handler could ever "
                                      + "authenticate");
    }

    return tokens;
  }
```

Add `close()` handling so the pool shuts down:

```java
  public void close() {
    databaseService.close();
    web.close();
  }
```

- [ ] **Step 6: Update `module-info.java`**

Add in alphabetical position:

```java
  exports dev.theagencyhq.agency.controller;
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.agency.tests.BriefingAPITest`
Expected: PASS, 5 tests.

---

### Task 11: Services, validation, and the poller

Implements design §7.2, §7.3, §9.3 and §11's validation rules.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/error/ValidationException.java`
- Create: `src/main/java/dev/theagencyhq/agency/service/validation/OrganizationValidator.java`
- Create: `src/main/java/dev/theagencyhq/agency/service/OrganizationService.java`
- Create: `src/main/java/dev/theagencyhq/agency/service/PollerService.java`
- Create: `src/main/java/dev/theagencyhq/agency/service/Services.java`
- Modify: `src/main/java/dev/theagencyhq/agency/Main.java`
- Modify: `src/main/java/module-info.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/PollerServiceTest.java`

**Interfaces:**
- Produces:
  - `class ValidationException extends RuntimeException` with `List<String> errors()`.
  - `OrganizationValidator.validate(String name, String path, DatabaseService, GitService)` — throws `ValidationException`.
  - `OrganizationService.create(String name, String path) -> Organization`
  - `PollerService.pollOnce(BriefSource) -> SourceStatus`, `PollerService.pollAll()`, `start()`, `shutdown()`
  - `Services.initialize(Configuration)`, `Services.shutdown()`, and static accessors `briefBuilder()`, `briefingService()`, `databaseService()`, `gitService()`, `organizationService()`, `pollerService()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/PollerServiceTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.model.SourceStatus;
import dev.theagencyhq.agency.service.BriefBuilder;
import dev.theagencyhq.agency.service.GitService;
import dev.theagencyhq.agency.service.OrganizationService;
import dev.theagencyhq.agency.service.PollerService;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test
public class PollerServiceTest {
  private DatabaseService database;
  private OrganizationService organizations;
  private PollerService poller;
  private Path root;

  @AfterClass
  public void afterClass() {
    if (database != null) {
      database.close();
    }
  }

  @AfterMethod
  public void afterMethod() throws IOException {
    if (root != null && Files.exists(root)) {
      try (var walk = Files.walk(root)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @BeforeClass
  public void beforeClass() {
    var config = new Configuration(
        List.of("db.password", "db.url", "db.username"),
        Path.of("src/test/resources/config.properties"),
        Path.of("src/main/resources/config.properties"));
    database = new DatabaseService(config);
    var git = new GitService();
    organizations = new OrganizationService(database, git);
    poller = new PollerService(database, git, new BriefBuilder(), 3600);
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    root = Files.createDirectories(Path.of("build/test/poller-" + UUID.randomUUID()).toAbsolutePath());
    Files.writeString(root.resolve("the-agency-hq-settings.json"), "{\"version\":\"1.0.0\"}");
    Files.createDirectories(root.resolve("rules"));
    Files.writeString(root.resolve("rules/a.md"), "first\n");
    initRepository();
  }

  @Test
  public void buildsThenSkipsThenVersionsOnlyOnContentChange() throws Exception {
    var organization = organizations.create("poller-" + UUID.randomUUID(), root.toString());
    var source = database.findSource(organization.id()).orElseThrow();

    // First poll builds version 1
    assertEquals(poller.pollOnce(source), SourceStatus.OK);
    assertEquals(database.findLatestBrief(organization.id()).orElseThrow().version(), 1);

    // Same commit -> no work at all
    assertEquals(poller.pollOnce(database.findSource(organization.id()).orElseThrow()), SourceStatus.UNCHANGED);

    // A new commit that does not change the Brief's content -> new commit, but NO new version
    Files.writeString(root.resolve("README.md"), "unrelated\n");
    commit("readme");
    assertEquals(poller.pollOnce(database.findSource(organization.id()).orElseThrow()), SourceStatus.UNCHANGED);
    assertEquals(database.findLatestBrief(organization.id()).orElseThrow().version(), 1);

    // A commit that does change the content -> version 2
    Files.writeString(root.resolve("rules/a.md"), "second\n");
    commit("rule change");
    assertEquals(poller.pollOnce(database.findSource(organization.id()).orElseThrow()), SourceStatus.OK);
    assertEquals(database.findLatestBrief(organization.id()).orElseThrow().version(), 2);
  }

  @Test
  public void buildFailureDoesNotAdvanceTheCommit() throws Exception {
    var organization = organizations.create("poller-" + UUID.randomUUID(), root.toString());
    assertEquals(poller.pollOnce(database.findSource(organization.id()).orElseThrow()), SourceStatus.OK);

    Files.delete(root.resolve("the-agency-hq-settings.json"));
    commit("break it");

    assertEquals(poller.pollOnce(database.findSource(organization.id()).orElseThrow()), SourceStatus.BUILD_FAILED);
    var after = database.findSource(organization.id()).orElseThrow();
    assertTrue(after.lastError() != null && !after.lastError().isBlank());

    // The previous version is still live and serving
    assertEquals(database.findLatestBrief(organization.id()).orElseThrow().version(), 1);
  }

  @Test
  public void failedPullStillBuilds() throws Exception {
    // This repository has no remote at all, so `git pull` always fails. The build must happen anyway.
    var organization = organizations.create("poller-" + UUID.randomUUID(), root.toString());
    assertEquals(poller.pollOnce(database.findSource(organization.id()).orElseThrow()), SourceStatus.OK);

    var source = database.findSource(organization.id()).orElseThrow();
    assertTrue(source.lastPullError() != null && !source.lastPullError().isBlank());
    assertEquals(database.findLatestBrief(organization.id()).orElseThrow().version(), 1);
  }

  private void commit(String message) throws Exception {
    run("git", "add", "-A");
    run("git", "commit", "-q", "-m", message);
  }

  private void initRepository() throws Exception {
    run("git", "init", "-q", "-b", "main");
    run("git", "config", "user.email", "test@theagencyhq.dev");
    run("git", "config", "user.name", "Test");
    run("git", "config", "commit.gpgsign", "false");
    commit("initial");
  }

  private void run(String... command) throws Exception {
    var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(process.waitFor(), 0, String.join(" ", command) + " -> " + output);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.PollerServiceTest`
Expected: FAIL — `OrganizationService` and `PollerService` do not exist.

- [ ] **Step 3: Write `ValidationException`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.error;

import module java.base;

/**
 * Thrown when user-supplied input fails validation. Carries every error so a form can show them all at once.
 */
public class ValidationException extends RuntimeException {
  private final List<String> errors;

  public ValidationException(List<String> errors) {
    super(String.join(" ", errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }
}
```

- [ ] **Step 4: Write `OrganizationValidator`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service.validation;

import module java.base;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.error.ValidationException;
import dev.theagencyhq.agency.service.BriefBuilder;
import dev.theagencyhq.agency.service.GitService;

/**
 * Validates a new Organization and its source Path. Names are first-come-first-serve like NPM, so the character
 * set is restricted for the same reason theirs is.
 */
public final class OrganizationValidator {
  public static final Pattern NAME = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$");

  private OrganizationValidator() {
  }

  public static void validate(String name, String path, DatabaseService database, GitService git) {
    var errors = new ArrayList<String>();

    var normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      errors.add("A name is required.");
    } else if (!NAME.matcher(normalized).matches()) {
      errors.add("The name [" + normalized + "] must be 1-64 characters of lowercase letters, digits and hyphens, "
                 + "starting and ending with a letter or digit.");
    } else if (database.findOrganizationByName(normalized).isPresent()) {
      errors.add("The name [" + normalized + "] is already registered.");
    }

    var trimmed = path == null ? "" : path.trim();
    if (trimmed.isEmpty()) {
      errors.add("A source path is required.");
    } else {
      Path resolved = null;
      try {
        resolved = Path.of(trimmed);
      } catch (InvalidPathException _) {
        errors.add("The path [" + trimmed + "] is not a valid path.");
      }

      if (resolved != null) {
        if (!resolved.isAbsolute()) {
          errors.add("The path [" + trimmed + "] must be absolute.");
        } else if (!Files.isDirectory(resolved)) {
          errors.add("The path [" + trimmed + "] is not an existing directory.");
        } else if (!git.isWorkTree(resolved)) {
          errors.add("The path [" + trimmed + "] is not a Git repository.");
        } else if (!Files.isRegularFile(resolved.resolve(BriefBuilder.SETTINGS_FILE))) {
          errors.add("The path [" + trimmed + "] has no [" + BriefBuilder.SETTINGS_FILE + "] file, so it is not a "
                     + "Brief source repository.");
        } else if (database.findSourceByPath(trimmed).isPresent()) {
          errors.add("The path [" + trimmed + "] is already registered to another Organization.");
        }
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }
}
```

- [ ] **Step 5: Write `OrganizationService`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.Organization;
import dev.theagencyhq.agency.service.validation.OrganizationValidator;

/**
 * Creates and deletes Organizations along with their single Brief source.
 */
public class OrganizationService {
  private final DatabaseService database;
  private final GitService git;

  public OrganizationService(DatabaseService database, GitService git) {
    this.database = database;
    this.git = git;
  }

  public Organization create(String name, String path) {
    OrganizationValidator.validate(name, path, database, git);

    var now = Instant.now();
    var organization = new Organization(UUID.randomUUID(), name, now, now);
    database.insertOrganization(organization);
    database.insertSource(new BriefSource(UUID.randomUUID(), organization.id(), path.trim(), null, null, null, null,
        null, now, now));
    return organization;
  }

  public void delete(UUID organizationId) {
    database.deleteOrganization(organizationId);
  }
}
```

- [ ] **Step 6: Write `PollerService`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;

import dev.theagencyhq.agency.api.Brief;
import dev.theagencyhq.agency.api.BriefContent;
import dev.theagencyhq.agency.api.BriefOrganization;
import dev.theagencyhq.agency.api.internal.BriefJSON;
import dev.theagencyhq.agency.db.DatabaseService;
import dev.theagencyhq.agency.model.BriefSource;
import dev.theagencyhq.agency.model.SourceStatus;

/**
 * Pulls each registered source and rebuilds its Brief when the content changes.
 *
 * <p>A per-Organization lock excludes the scheduled cycle and a manual "Rebuild now" from ever building the same
 * Organization twice concurrently.
 */
public class PollerService {
  public static final int MINIMUM_INTERVAL_SECONDS = 5;
  private static final System.Logger logger = System.getLogger(PollerService.class.getName());
  private final BriefBuilder builder;
  private final DatabaseService database;
  private final GitService git;
  private final int intervalSeconds;
  private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("the-agency-poller").daemon().factory());

  public PollerService(DatabaseService database, GitService git, BriefBuilder builder, int intervalSeconds) {
    this.builder = builder;
    this.database = database;
    this.git = git;
    this.intervalSeconds = Math.max(MINIMUM_INTERVAL_SECONDS, intervalSeconds);
  }

  public void pollAll() {
    var sources = database.listSources();
    var counts = new EnumMap<SourceStatus, Integer>(SourceStatus.class);

    // Bounded fan-out: one source must never be able to stall the rest of the cycle.
    try (var executor = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())) {
      var futures = sources.stream().map(s -> executor.submit(() -> pollOnce(s))).toList();
      for (var future : futures) {
        try {
          var status = future.get();
          if (status != null) {
            counts.merge(status, 1, Integer::sum);
          }
        } catch (ExecutionException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          logger.log(System.Logger.Level.ERROR, "A poll cycle task failed", e);
        }
      }
    }

    logger.log(System.Logger.Level.INFO, "Poll cycle complete [" + counts + "]");
  }

  /**
   * Runs one full cycle for one source: pull, detect a change, rebuild, and version the result if the content
   * actually changed.
   *
   * @param source The source to poll.
   * @return The status recorded on the source, or {@code null} if a build was already running for it.
   */
  public SourceStatus pollOnce(BriefSource source) {
    var lock = locks.computeIfAbsent(source.organizationId(), _ -> new ReentrantLock());
    if (!lock.tryLock()) {
      logger.log(System.Logger.Level.DEBUG, "Skipping source [" + source.path() + "]; a build is already running");
      return null;
    }

    try {
      return poll(source);
    } catch (RuntimeException e) {
      logger.log(System.Logger.Level.ERROR, "Unable to poll source [" + source.path() + "]", e);
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, e.getMessage(), null);
    } finally {
      lock.unlock();
    }
  }

  public void shutdown() {
    scheduler.shutdownNow();
  }

  public void start() {
    scheduler.scheduleWithFixedDelay(() -> {
      // An uncaught exception would silently cancel every future run, which is the one failure mode a scheduled
      // task must never have.
      try {
        pollAll();
      } catch (Throwable t) {
        logger.log(System.Logger.Level.ERROR, "The poll cycle threw", t);
      }
    }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
  }

  private SourceStatus poll(BriefSource source) {
    var path = Path.of(source.path());
    if (!git.isWorkTree(path)) {
      return record(source, source.lastBuiltCommit(), SourceStatus.NOT_A_REPOSITORY,
          "The path [" + source.path() + "] is missing or is not a Git repository", null);
    }

    // A failed pull is never fatal: it is what lets a purely local repository with no remote work unchanged.
    var pull = git.pull(path);
    var pullError = pull.success() ? null : pull.output().trim();
    if (pullError != null) {
      logger.log(System.Logger.Level.WARNING, "Unable to pull [" + source.path() + "]: " + pullError);
    }

    var head = git.head(path).orElse(null);
    if (head == null) {
      return record(source, source.lastBuiltCommit(), SourceStatus.NOT_A_REPOSITORY,
          "Unable to read HEAD in [" + source.path() + "]", pullError);
    }

    var latest = database.findLatestBrief(source.organizationId());
    if (head.equals(source.lastBuiltCommit()) && latest.isPresent()) {
      return record(source, head, SourceStatus.UNCHANGED, null, pullError);
    }

    var organization = database.findOrganization(source.organizationId()).orElse(null);
    if (organization == null) {
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED,
          "The Organization [" + source.organizationId() + "] no longer exists", pullError);
    }

    String checksum;
    BriefContent content;
    try {
      content = builder.build(new BriefOrganization(organization.id().toString(), organization.name()), path);
      checksum = BriefBuilder.checksum(content);
    } catch (BriefBuildException e) {
      logger.log(System.Logger.Level.ERROR, "Unable to build the Brief for [" + organization.name() + "]", e);
      // The commit is deliberately not advanced, so the next cycle retries and a fixed repository recovers itself.
      return record(source, source.lastBuiltCommit(), SourceStatus.BUILD_FAILED, e.getMessage(), pullError);
    }

    if (latest.isPresent() && latest.get().checksum().equals(checksum)) {
      // Identical content: advance the commit so the work is not repeated, but do NOT create a version. Without
      // this, an unrelated README commit would force every Handler on every machine to re-download.
      return record(source, head, SourceStatus.UNCHANGED, null, pullError);
    }

    // The document is serialized against whatever version the insert actually assigns, so a concurrent build
    // cannot leave a document whose embedded version disagrees with its row.
    var assigned = database.insertBrief(UUID.randomUUID(), organization.id(), checksum,
        v -> BriefJSON.toJSON(new Brief(checksum, content.organization(), v, content.files())), head, Instant.now());

    logger.log(System.Logger.Level.INFO, "Built Brief for [" + organization.name() + "] version [" + assigned + "]");
    return record(source, head, SourceStatus.OK, null, pullError);
  }

  private SourceStatus record(BriefSource source, String commit, SourceStatus status, String error, String pullError) {
    database.updateSourceStatus(source.organizationId(), commit, Instant.now(), status, error, pullError);
    return status;
  }
}
```

**Note to the implementer:** the version-race branch above throws after already inserting the row, which leaves a
document whose embedded `version` disagrees with its row. Replace that whole `if (assigned != version)` block with
the correct fix: compute the version *inside* `DatabaseService.insertBrief` (it already does), have `insertBrief`
accept the document as a `java.util.function.IntFunction<String>` so the document is serialized with the version
actually assigned, and delete the race branch entirely. Update Task 8's `insertBrief` signature to
`int insertBrief(UUID id, UUID organizationId, String checksum, IntFunction<String> document, String sourceCommit, Instant insertInstant)`
and adjust `BriefRepositoryTest` accordingly.

- [ ] **Step 7: Write `Services`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.service;

import module java.base;
import module org.lattejava.web;

import dev.theagencyhq.agency.db.DatabaseService;

/**
 * A simple service registry. Every service is a singleton created in {@link #initialize(Configuration)}.
 */
public class Services {
  private static BriefBuilder briefBuilder;
  private static BriefingService briefingService;
  private static DatabaseService databaseService;
  private static GitService gitService;
  private static OrganizationService organizationService;
  private static PollerService pollerService;

  public static BriefBuilder briefBuilder() {
    return briefBuilder;
  }

  public static BriefingService briefingService() {
    return briefingService;
  }

  public static DatabaseService databaseService() {
    return databaseService;
  }

  public static GitService gitService() {
    return gitService;
  }

  public static void initialize(Configuration config) {
    // The database service owns the pool and must exist before anything that uses it.
    databaseService = new DatabaseService(config);
    briefBuilder = new BriefBuilder();
    briefingService = new BriefingService();
    gitService = new GitService();
    organizationService = new OrganizationService(databaseService, gitService);
    pollerService = new PollerService(databaseService, gitService, briefBuilder,
        config.getInteger("poller.intervalSeconds", 60));
    pollerService.start();
  }

  public static OrganizationService organizationService() {
    return organizationService;
  }

  public static PollerService pollerService() {
    return pollerService;
  }

  public static void shutdown() {
    if (pollerService != null) {
      pollerService.shutdown();
    }
    if (databaseService != null) {
      databaseService.close();
    }
  }
}
```

- [ ] **Step 8: Switch `Main` to `Services`**

Replace the direct `DatabaseService` construction from Task 10 with `Services.initialize(config)` at the top of
`main()`, have `databaseService()` return `Services.databaseService()`, build the `BriefingController` from
`Services`, and make `close()` call `Services.shutdown()` then `web.close()`.

- [ ] **Step 9: Update `module-info.java`**

Add in alphabetical position:

```java
  exports dev.theagencyhq.agency.error;
  exports dev.theagencyhq.agency.service.validation;
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `latte test --test=dev.theagencyhq.agency.tests.PollerServiceTest`
Expected: PASS, 3 tests.

Run: `latte test`
Expected: every test passes.

---

### Task 12: Admin UI

Implements design §11. Plain server-rendered JTE with a small embedded stylesheet — no Tailwind, no build step,
no JavaScript.

**Files:**
- Create: `src/main/java/dev/theagencyhq/agency/controller/OrganizationController.java`
- Create: `src/main/java/dev/theagencyhq/agency/model/view/{OrganizationsView,OrganizationDetailView,BriefVersionView,BriefFileView}.java`
- Create: `web/templates/layout/main.jte`
- Create: `web/templates/pages/{organizations,new,detail,version,file}.jte`
- Create: `web/static/css/app.css`
- Modify: `src/main/java/dev/theagencyhq/agency/Main.java`
- Modify: `src/main/java/module-info.java`
- Create: `src/test/java/dev/theagencyhq/agency/tests/AdminUITest.java`

**Interfaces:**
- Consumes: `Services` (Task 11), `BriefJSON` and the `api` records (Task 3), `DatabaseService` (Task 8).
- Produces the routes in the table below.

| Route                                                                          | Handler method     |
|--------------------------------------------------------------------------------|--------------------|
| `GET  /`                                                                       | 303 → `/app/organizations/` |
| `GET  /app/organizations/`                                                     | `list`             |
| `GET  /app/organizations/new`                                                  | `newForm`          |
| `POST /app/organizations/`                                                     | `create`           |
| `GET  /app/organizations/{organizationId}`                                     | `detail`           |
| `POST /app/organizations/{organizationId}/rebuild`                             | `rebuild`          |
| `GET  /app/organizations/{organizationId}/versions/{version}`                  | `version`          |
| `GET  /app/organizations/{organizationId}/versions/{version}/files/{index}`    | `file`             |

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/agency/tests/AdminUITest.java` covering, at minimum:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.agency.tests;

// Imports as in BriefingAPITest.

@Test
public class AdminUITest {
  public StringBodyAsserter string = new StringBodyAsserter();
  public WebTest test = new WebTest(Main.PORT);

  @BeforeClass
  public void beforeClass() {
    TestServer.start();
  }

  @Test
  public void createsAnOrganizationAndShowsItsVersions() {
    // 1. GET /app/organizations/new  -> 200, contains a form posting to /app/organizations/
    // 2. POST /app/organizations/ with name + path of a temporary Brief source repo -> 303 to the detail page
    // 3. POST /app/organizations/{id}/rebuild -> 303, and the detail page then lists version 1
    // 4. GET /app/organizations/{id}/versions/1 -> 200, lists ".claude/rules/a.md" with mode 0400
    // 5. GET /app/organizations/{id}/versions/1/files/0 -> 200, shows the file's text content
  }

  @Test
  public void rejectsAnInvalidPath() {
    // POST /app/organizations/ with a path that is not a git repository -> 200 re-rendering the form with the
    // validation message, and no Organization is created.
  }

  @Test
  public void rootRedirectsToTheListing() {
    // GET / -> 303 with Location /app/organizations/
  }
}
```

Write these out fully using the `WebTest` API documented in Task 10 Step 2 — `withX(...)` accumulates, the verb
method executes, `reset()` clears between calls, and `assertRedirect(303, "/app/organizations/")` checks a
redirect in one call. Build the temporary Brief source repository with the same `initRepository`/`commit` helpers
used in `PollerServiceTest`, and register it by POSTing the form rather than calling `OrganizationService`
directly, so the controller's validation path is what is under test.

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.agency.tests.AdminUITest`
Expected: FAIL — the routes do not exist.

- [ ] **Step 3: Write the view models**

Records in `dev.theagencyhq.agency.model.view`, holding only what a template renders:

```java
public record OrganizationsView(List<Row> rows) {
  public record Row(UUID id, String name, String path, SourceStatus status, String error, String pullError,
                    Integer latestVersion, Instant lastPolledInstant) {
  }
}

public record OrganizationDetailView(Organization organization, BriefSource source, List<BriefVersion> versions) {
}

public record BriefVersionView(Organization organization, int version, String checksum, String sourceCommit,
                               Instant insertInstant, List<Entry> entries) {
  public record Entry(int index, String path, String encoding, String mode, int size, List<String> missionTypes) {
  }
}

public record BriefFileView(Organization organization, int version, BriefFile file, boolean text, int size) {
}
```

Add the copyright header and `import module java.base;` plus the model imports to each file.

- [ ] **Step 4: Write `OrganizationController`**

The controller reads a version's `document` and parses it with `BriefJSON.fromJSON` to build the file list — the
document is the authoritative record of what was published, so the UI must show exactly it rather than rebuilding
from the source tree.

Key points to implement:

- `list` — `Services.databaseService().listOrganizations()`, joined with `listSources()` and `latestBriefs()`.
- `newForm` — renders an empty form. `create` catches `ValidationException` and re-renders the form with
  `e.errors()` and the submitted values, returning `200`; on success `res.sendRedirect("/app/organizations/" + id, 303)`.
- `rebuild` — `Services.pollerService().pollOnce(source)` then redirect back to the detail page with `303`.
- `version` / `file` — parse the `version` and `index` path parameters with `Integer.parseInt` inside a try/catch
  that returns `404`; a missing Organization, version, or out-of-range index is also `404`.
- `file` — text files render escaped in a `<pre>`; base64 files render size plus a link to the same route with
  `?download=true`, which writes the decoded bytes with
  `res.setHeader("Content-Disposition", "attachment; filename=\"...\"")`. Use the filename's last path segment and
  strip any quote characters from it.

Path parameters are read with `(String) req.getAttribute("organizationId")`.

- [ ] **Step 5: Write the templates and stylesheet**

`web/templates/layout/main.jte` provides the page chrome (doctype, `<title>`, a link to `/static/css/app.css`, a
header linking to `/app/organizations/`) and takes `@param String pageTitle` and `@param gg.jte.Content content`.
Each page template calls it as `@template.layout.main(pageTitle="...", content=@`...`)`.

`web/static/css/app.css` is a small hand-written stylesheet: a readable max-width container, a system font stack,
simple table and form styling, and a monospace `pre` with `overflow-x: auto`. Roughly 60 lines. No framework.

Every value interpolated into a template must go through JTE's default escaping — never `$unsafe{...}`.

- [ ] **Step 6: Register the routes in `Main`**

```java
    var organizations = new OrganizationController(templates);
    web.get("/", (_, res) -> res.sendRedirect("/app/organizations/", 303))
       .prefix("/app", app -> app.prefix("/organizations", orgs -> {
         orgs.get("/", organizations::list);
         orgs.get("/new", organizations::newForm);
         orgs.post("/", organizations::create);
         orgs.get("/{organizationId}", organizations::detail);
         orgs.post("/{organizationId}/rebuild", organizations::rebuild);
         orgs.get("/{organizationId}/versions/{version}", organizations::version);
         orgs.get("/{organizationId}/versions/{version}/files/{index}", organizations::file);
       }));
```

Remove the old `.get("/", templates::html)` route and delete `web/templates/index.jte`. Update `MainTest` — its
`getSlash` test asserted the old body; change it to assert a `303` to `/app/organizations/`.

- [ ] **Step 7: Update `module-info.java`**

Add in alphabetical position:

```java
  exports dev.theagencyhq.agency.model.view;
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `latte test --test=dev.theagencyhq.agency.tests.AdminUITest`
Expected: PASS.

---

### Task 13: End-to-end verification

**Files:**
- Create: `src/test/java/dev/theagencyhq/agency/tests/PipelineIntegrationTest.java`

**Interfaces:**
- Consumes: everything.

- [ ] **Step 1: Write the integration test**

Create `src/test/java/dev/theagencyhq/agency/tests/PipelineIntegrationTest.java`. Build a temporary Brief source
repository under `build/test/`, register it through `OrganizationService`, and drive the whole pipeline. Cover
exactly these scenarios (each an `@Test`):

1. `registerCommitAndPollProducesVersionOne` — a source with `skills/skill1/SKILL.md`, `rules/rule1.md` and
   `claude/settings.json` yields version 1 whose file list is the eight expected output paths with mode `0400`.
2. `contentChangeProducesVersionTwo` — editing `rules/rule1.md` and committing yields version 2.
3. `unrelatedCommitProducesNoNewVersion` — adding `README.md` and committing leaves the latest version at 2.
4. `handlerColdStoreReceivesEveryBrief` — `POST /api/v1/briefing` with `{"currentVersions":[]}` and a valid token
   returns `200` and a body containing the Organization id and the Brief.
5. `repeatedRequestIsNotModified` — echoing the received `version` and `checksum` back returns `304`.
6. `corruptChecksumForcesAResend` — the same request with a wrong `checksum` returns `200` and the Brief.
7. `deletingAnOrganizationForcesA200` — after `OrganizationService.delete`, a Handler still asserting that
   Organization gets `200` with an `organizationIds` list that no longer contains it.
8. `buildFailureLeavesThePreviousVersionServing` — deleting `the-agency-hq-settings.json` and committing leaves
   the latest version unchanged and still served by the API.

Parse response bodies with `BriefingResponse`-shaped assertions using plain `String.contains` checks on the
Organization id, `"version":N` and `"checksum":"..."` — the wire contract is already unit-tested in Task 3, so
these assertions only need to prove the pipeline is connected.

- [ ] **Step 2: Run the whole suite**

Run: `latte clean && latte test`
Expected: BUILD SUCCESS, every test passing.

- [ ] **Step 3: Boot the app and smoke-test it by hand**

Run: `latte main-database` then `latte run`, and in another shell:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/app/organizations/     # expect 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/v1/briefing \
  -H 'Content-Type: application/json' -d '{"currentVersions":[]}'                     # expect 401
curl -s -X POST http://localhost:8080/api/v1/briefing \
  -H 'Authorization: Bearer dev-token' -H 'Content-Type: application/json' \
  -d '{"currentVersions":[]}'                                                          # expect 200 JSON
```

Stop the server when done.

- [ ] **Step 4: Report, do not commit**

Leave every change in the working tree. Report what was built, the final test count, and anything skipped.

---

## Self-Review

**Spec coverage:**

| Spec section                         | Task    |
|--------------------------------------|---------|
| §4 Handler contract                  | 3, 10, 13 |
| §5 Component map                     | all     |
| §6 Configuration                     | 1       |
| §7.1 `GitService`                    | 7       |
| §7.2 `PollerService` scheduling+locks| 11      |
| §7.3 The poll cycle                  | 11      |
| §8.1 Layout marker                   | 6       |
| §8.2 Walking, symlinks               | 6       |
| §8.3 Mission Types                   | 4       |
| §8.4 Path mapping                    | 5       |
| §8.5 Encoding, mode, checksum, sort  | 6       |
| §8.6 Output path validation          | 5       |
| §9.1 Wire records                    | 3       |
| §9.2 Brief checksum                  | 6       |
| §9.3 Version assignment + dedup      | 8, 11   |
| §10.1 Authentication                 | 10      |
| §10.2 The decision                   | 9       |
| §10.3 Response assembly              | 10      |
| §11 Admin UI                         | 12      |
| §12 Data model                       | 2, 8    |
| §13 Error handling and logging       | throughout |
| §14 Testing                          | every task, plus 13 |
| §15 Build and tooling                | 1, 2    |

§10.4 (what changes when entitlements arrive) is documentation, not code. §13's `ExceptionHandler` middleware is
folded into Task 12's `create` handler, which catches `ValidationException` directly — a middleware would be
indirection for one call site in milestone 1.

**Type consistency:** `insertBrief` takes `(UUID, UUID, String, IntFunction<String>, String, Instant)` in Task 8's
interface block, its implementation, `BriefRepositoryTest`, `PollerService` and `BriefingAPITest`. `BriefBuilder`
is `build(BriefOrganization, Path)` plus the static `checksum(BriefContent)` everywhere it appears.
`PollerService.pollOnce` returns `SourceStatus` (nullable, meaning "already building") in Task 11's interface
block, its implementation and `PollerServiceTest`. `MissionTypeResolver.DIRECTORY_FILE` and `SUFFIX` are
referenced by `BriefBuilder`. `OutputPaths.map`/`validate` are static and used as such.

**Things the implementer must decide, because they cannot be settled from a plan:**

1. Task 12's exact JTE syntax for the layout `@param gg.jte.Content content` pattern — model it on
   `~/dev/latte-java/app/web/pages/groups/list.jte`, which uses the same JTE 3.2.1.
2. Task 12's `AdminUITest` bodies are specified as scenarios, not code. Write them against the documented
   `WebTest` API.
3. Whether `latte build`'s `dependsOn: ["codegen"]` is tolerable in the loop. It guarantees the generated classes
   always match the migrations, which is the failure most likely to waste time; keep it unless it proves slow.
