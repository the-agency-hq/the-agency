---
paths:
  - "**/*.java"
---

# Code Conventions

## Indentation

All source files should be indented with 2 spaces. Continuation indents should be 4 spaces.

## Acronym Naming

Always use full uppercase for acronyms in Java identifiers — class names, method names, field names, local variables. Do NOT use title-case (camelCase-with-only-the-first-letter-uppercase) for acronyms. If a field or method name starts with an acronym, lowercase the entire acronym.

### Examples

| Wrong              | Right              |
|--------------------|--------------------|
| `JsonBodySupplier` | `JSONBodySupplier` |
| `XmlParser`        | `XMLParser`        |
| `HttpRequest`      | `HTTPRequest`      |
| `ApiClient`        | `APIClient`        |
| `JwtToken`         | `JWTToken`         |
| `UrlBuilder`       | `URLBuilder`       |
| `SqlQuery`         | `SQLQuery`         |
| `parseXml()`       | `parseXML()`       |
| `toJson()`         | `toJSON()`         |
| `getHttpStatus()`  | `getHTTPStatus()`  |
| `jSONProcess()`    | `jsonProcess()`    |
| `JSONProcess()`    | `jsonProcess()`    |

### Scope

Applies to:
- Class and interface names
- Method and constructor names
- Field and local variable names where the acronym appears
- Enum constants (usually fine — they're already uppercase)

Package names remain all-lowercase per standard Java convention (e.g., `org.lattejava.web.json`, not `org.lattejava.web.JSON`).

### Why

Matches the convention already used throughout Latte Java (`HTTPRequest`, `HTTPResponse`, `HTTPServer`, `HTTPMethod`, etc.) and the JDK (`URL`, `URI`, `HTTPServer`). Title-cased acronyms (`Http`, `Json`) are easy to mis-read and inconsistent with the rest of the codebase.

## Alphabetization

Alphabetize whenever ordering is not otherwise meaningful. Default to alphabetical order for any list-style construct where the elements are peers.

### Applies to

- Field declarations within a class (grouped by visibility, then alphabetized)
- Method declarations within a class (alphabetized within their visibility/kind group)
- Import statements (alphabetized within each import group)
- `requires` clauses in `module-info.java`
- `exports` clauses in `module-info.java`
- `opens` clauses in `module-info.java` (and the targets after `to` are alphabetized too)
- Entries in enum declarations (unless order carries semantic meaning)
- Constants grouped together
- Dependencies in `project.latte` within a group

### Does not apply when

- Ordering carries semantic meaning (e.g., middleware execution order, route registration order, pipeline stages)
- Dependency groups where transitive-dependency ordering matters (rare)
- Constructor parameter order (typically dictated by usage patterns, not alphabetic)

### Example

```java
// Wrong
module org.lattejava.web {
  requires java.net.http;
  requires org.lattejava.http;
  requires com.fasterxml.jackson.databind;
}

// Right
module org.lattejava.web {
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires org.lattejava.http;
}
```

## Blank lines

Remove blank lines between fields.

### Example

```java
// Wrong
public class Example {
  private final String field1;
  
  private final String field2;
}

// Right
public class Example {
  private final String field1;
  private final String field2;
}
```

Add blank lines between logical groups of operations in methods.

## Imports

Prefer module imports over class imports. If a class is using class imports, attempt to replace as many as possible with module imports.

### Example

```java
// Before
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// After
import module java.base;
```

Add a blank line between import groups.

## Order inside classes and interfaces (not records)

Inside a class, the order should always be:

- Static fields (ordered by visibility then alphabetically)
- Instance fields (ordered by visibility then alphabetically)
- Constructors (ordered by number of parameters)
- Static methods (ordered by visibility then alphabetically)
- Instance methods (ordered by visibility then alphabetically)
- Inner classes (ordered by visibility then alphabetically)
- Enum constants (ordered alphabetically)
- Nested classes (ordered alphabetically)

## Javadoc

When Javadoc is written, use sentence structure, punctuation, and capitalization of common American English publications. This rule applies to @param, @return, @throws, and the main comment block.

## Line length and wrapping

120 characters is the target line length. *DO NOT* wrap lines before 120 characters. If a line is longer than 120 characters, but keeping it on one line makes it easier to read, do not wrap it. 

When wrapping lines, break on logical boundaries and avoid excessive wrapping. If breaking in an if-statement, break after the logical operator (`&&` or `||`). If breaking on chained method calls, break before the dot and align the dots with the dot of the previous line.