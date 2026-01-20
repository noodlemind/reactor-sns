---
status: pending
priority: p2
issue_id: "009"
tags: [code-review, security, dependencies]
dependencies: []
---

# Outdated Dependencies with Potential Vulnerabilities

## Problem Statement

The project uses outdated versions of AWS SDK (2.20.0 from Feb 2023) and Spring Boot (3.1.0 from June 2023). These versions may contain known security vulnerabilities and miss performance improvements.

**Why it matters:**
- Security patches in newer versions
- Bug fixes and performance improvements
- Potential CVEs in old versions

## Findings

**Location:** `pom.xml` (Lines 10-11)

```xml
<aws.java.sdk.version>2.20.0</aws.java.sdk.version>
<spring.boot.version>3.1.0</spring.boot.version>
```

**Current vs Latest:**
| Dependency | Current | Latest Stable |
|------------|---------|---------------|
| AWS SDK | 2.20.0 | 2.25.x+ |
| Spring Boot | 3.1.0 | 3.2.x+ |

**Additional Issues:**
- Hardcoded mockito/byte-buddy versions that should be managed by Spring Boot BOM
- `logback-classic` should be test-scoped (consuming apps bring their own logging)

**Evidence:**
- Agent: security-sentinel, architecture-strategist

## Proposed Solutions

### Option 1: Update to Latest Stable (Recommended)

```xml
<aws.java.sdk.version>2.25.0</aws.java.sdk.version>
<spring.boot.version>3.2.0</spring.boot.version>
```

And remove explicit versions for test dependencies:
```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <!-- Remove version, let BOM manage -->
    <scope>test</scope>
</dependency>
```

**Pros:** Security patches, bug fixes, performance
**Cons:** May require code changes for breaking changes
**Effort:** Medium (need to test)
**Risk:** Low-Medium

### Option 2: Update Incrementally

Update to next minor versions, test, repeat.

**Pros:** Lower risk per update
**Cons:** Takes longer
**Effort:** Medium
**Risk:** Low

### Option 3: Pin and Document

Keep current versions but document the security posture.

**Pros:** No immediate work
**Cons:** Technical debt, security risk
**Effort:** Trivial
**Risk:** High - not recommended

## Recommended Action

_To be filled during triage_

## Technical Details

**Affected files:**
- `pom.xml`

**Components:** Maven dependencies

## Acceptance Criteria

- [ ] AWS SDK updated to 2.25.x+
- [ ] Spring Boot updated to 3.2.x+
- [ ] Test dependencies managed by BOM
- [ ] All tests pass with new versions
- [ ] `logback-classic` scoped to test

## Work Log

| Date | Action | Learnings |
|------|--------|-----------|
| 2026-01-20 | Created from code review | Identified by security-sentinel |

## Resources

- [AWS SDK Changelog](https://github.com/aws/aws-sdk-java-v2/blob/master/CHANGELOG.md)
- [Spring Boot Releases](https://spring.io/projects/spring-boot#support)
