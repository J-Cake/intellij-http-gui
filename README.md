# intellij-http-gui

**HTTP Request Designer** is an IntelliJ plugin that adds a graphical **Designer** tab to IntelliJ HTTP Client files
(`.http` / `.rest`). The designer edits the same document as the text editor: edits in the GUI rewrite only the
affected request, and changes made anywhere else (the text tab, VCS, external tools) show up in the GUI live.

## Features

| | |
|---|---|
| **Requests** | Request list per file (add, duplicate, delete). Method picker with the standard methods (plus `GRAPHQL` / `WEBSOCKET`) that also accepts any custom method. URL, query parameter, header and body editors. |
| **OpenAPI routes** | The *Routes* button (and the import button in the request list) offers routes from the IDE's **Endpoints** view (`com.intellij.microservices.endpointsProvider`, which the OpenAPI/Swagger and framework plugins feed) and from OpenAPI/Swagger files in the project. Picking a route fills in method, path (`{id}` → `{{id}}`), query parameters, content type, an example body and a default status test. |
| **Environment completion** | Type `{{` in the URL, header and parameter fields to complete variables from `http-client.env.json` / `http-client.private.env.json` (including `$shared`), in-file `@variables` and the built-in dynamic variables (`$uuid`, `$timestamp`, `$random.*`, `$auth.token(...)`, ...). The selected environment shows a resolved preview of the URL and flags undefined variables. Environment files are reloaded when they change. |
| **Auth dashboard** | No auth, Basic, Digest, Bearer, API key (header or query), OAuth 2.0 and custom `Authorization` values. OAuth 2.0 uses `{{$auth.token("id")}}` and can create or edit the `Security.Auth` configuration in the environment files (secrets go to the private file). |
| **Tests** | Build checks (status, header, JSON path, body, content type × equals / contains / matches / exists / `<` / `>` ...) that are generated as `client.test(...)` blocks in the response handler, with quick-add presets. New requests start with a `status == 200` test. Hand-written handler code is kept in a separate script editor, and generated checks are recognised again when the file is re-read. |
| **More** | Pre-request scripts, request tags (`@no-redirect`, `@no-cookie-jar`, `@no-log`, `@timeout`, ...), HTTP version, and *Open in Text Editor* to jump to (and run) the request. |

## Project layout

- `core/` — plain Kotlin, no IntelliJ dependency: `.http` parser/serializer, query-string handling, test-script
  generation/recognition, auth header codec, environment files (JSON) and OpenAPI route extraction. Unit tested.
- `src/` — the IntelliJ plugin: `HttpGuiEditorProvider` (registers the tab), `HttpGuiFileEditor` (document sync and
  live reload), `ui/` (forms) and `services/` (environment files, routes).
- `samples/` — an `.http` file, environment file and OpenAPI spec to try the designer with.

## Building

Requires JDK 21.

```bash
./gradlew :core:test      # unit tests for the format logic
./gradlew buildPlugin     # plugin ZIP in build/distributions
./gradlew runIde          # sandbox IDE with the plugin installed
```

The IDE version used for compiling is `platformVersion` in `gradle.properties`; the plugin supports builds from
`pluginSinceBuild` (2024.3) on. The HTTP Client itself is part of IntelliJ IDEA Ultimate (and other paid JetBrains IDEs),
so that's where the requests can be run. The designer doesn't depend on it.
