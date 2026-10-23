# Third-party notices

The original project code is licensed under [MIT](LICENSE). Dependency licenses remain applicable to their own code.

## Bundled runtime dependencies

**Gson 2.13.2** (`com.google.code.gson:gson`) — Google Gson contributors. Source headers include Copyright (C) 2008 Google Inc. Licensed under the Apache License, Version 2.0. Gson is bundled unmodified for JSON parsing and formatting.

- [Project](https://github.com/google/gson)
- [Upstream version license](https://github.com/google/gson/blob/gson-parent-2.13.2/LICENSE)
- [Local Apache-2.0 license text](docs/third-party-licenses/Apache-2.0.txt)

**Error Prone Annotations 2.41.0** (`com.google.errorprone:error_prone_annotations`) — The Error Prone Authors. The annotations module's POM carries Copyright 2015 The Error Prone Authors and declares Apache License, Version 2.0. It is an unmodified transitive dependency of Gson and its annotation classes are included in the distributable JAR; the Error Prone compiler/checker is not included.

- [Project](https://github.com/google/error-prone)
- [Versioned module POM and license declaration](https://github.com/google/error-prone/blob/v2.41.0/annotations/pom.xml)
- [Local Apache-2.0 license text](docs/third-party-licenses/Apache-2.0.txt)

## Build and test components

- **Burp Suite Montoya API 2026.7**, PortSwigger: compile-only API dependency; provided at runtime by Burp and excluded from the distributable JAR. See [upstream license](https://github.com/PortSwigger/burp-extensions-montoya-api/blob/main/LICENSE).
- **JUnit 5.13.4**: test-only dependency; excluded from the distributable JAR. [Eclipse Public License 2.0](https://github.com/junit-team/junit5/blob/r5.13.4/LICENSE.md).
- **Gradle 8.14.3 wrapper**: build tooling under Apache-2.0; its wrapper files are part of the source project. [Gradle license](https://github.com/gradle/gradle/blob/v8.14.3/LICENSE).
