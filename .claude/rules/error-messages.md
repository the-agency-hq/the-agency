---
paths:
  - "**/*.java"
---

# Error Message Formatting

Runtime values in exception messages, log messages, and any error strings must be wrapped in square brackets `[value]` — not single quotes `'value'` and not double quotes `"value"`.

## Why

Square brackets clearly delimit runtime values from surrounding prose, never clash with contractions or punctuation, render cleanly in stack traces and logs, and don't require escaping inside Java string literals.

## Examples

```java
// Wrong
throw new IllegalArgumentException("pathSpec must start with '/': \"" + pathSpec + "\"");
throw new IllegalArgumentException("Invalid parameter name '" + name + "'");

// Right
throw new IllegalArgumentException("pathSpec must start with [/]: [" + pathSpec + "]");
throw new IllegalArgumentException("Invalid parameter name [" + name + "]");
```

## Scope

**Applies to:**
- `IllegalArgumentException`, `IllegalStateException`, and any other exception messages
- Log messages at any level
- `toString()` output that includes field values
- Error strings returned from validators

**Does not apply to:**
- Literal prose text that happens to mention a name (no value interpolation)
- Javadoc comments
- Static documentation strings
