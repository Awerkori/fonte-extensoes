# Extension Development Guide

This guide defines the working rules for adding or changing sources and multi-source themes in
this repository. [`CONTRIBUTING.md`](../../CONTRIBUTING.md) is the authoritative reference: read it
in full before making a change, and follow it if this guide ever differs from it.

Keep changes small and site-specific. Do not change shared build logic, `core/`, `compiler/`,
`common/`, or Gradle infrastructure unless the task explicitly requires it.

## 1. Start with the right module

- For a new non-multisrc source, use `ext-bootstrap.py`; do not hand-create the module structure.
  For example: `python ext-bootstrap.py -n "My Source" -l en -u https://mysource.com`.
- Use the `-m <theme>` option when the site belongs to an existing multi-source theme. Do not
  duplicate a theme implementation in a standalone source.
- A standalone extension belongs in `src/<lang>/<source>/`, where `<source>` contains only
  lowercase ASCII letters and digits. Its package is
  `eu.kanade.tachiyomi.extension.<lang>.<source>`.
- Use an ISO language code (`all` is allowed where appropriate). Do not add a language suffix to
  the displayed source name; the app groups sources by language.
- Keep optional support files concise and descriptive: use `Dto.kt` and `Filters.kt`, not
  `MySourceDto.kt` or `MySourceFilters.kt`.
- Before writing a custom implementation, check `lib/` and the shared utilities. Reuse an existing
  library or helper when it covers the site requirement.

## 2. Gradle metadata and generated source configuration

New standalone sources use the extension plugin and `libVersion = "1.6"`:

```kotlin
import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "My Source"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://example.com"
    }
}
```

- Set `contentWarning` explicitly to `SAFE`, `MIXED`, or `NSFW`.
- Every extension needs at least one `source {}` block. Add multiple blocks when one class serves
  multiple sources; do not use `SourceFactory`.
- The DSL owns source metadata. It generates the source name, language, ID, and base URL and
  injects them through KSP.
- Use `baseUrl` as a normal fixed URL, `baseUrl { mirrors(...) }` for supported mirrors, and
  `baseUrl { custom(...) }` for a user-provided domain. Never implement these base-URL features
  with manual preferences or `ListPreference`.
- Keep the generated source ID stable. Only set an explicit `id` to preserve an existing generated
  ID when a source name or language has to change. Do not rename sources casually.
- Increment `source { versionId = ... }` only when old source URLs fundamentally cannot be made to
  work. It forces users to migrate bookmarks.
- Increment the extension `versionCode` for source changes. A multi-source theme uses
  `baseVersionCode`, which must be incremented for theme changes.

## 3. Source classes: use KeiSource and KSP

New standalone sources must extend `KeiSource`, never `HttpSource` directly:

```kotlin
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource

@Source
abstract class MySource : KeiSource() {
    // Site-specific implementation.
}
```

- Do not manually declare or override `name`, `lang`, `id`, or `baseUrl` in an `@Source` class.
  KSP injects them from `build.gradle.kts`.
- Do not recreate the old request/parse split (`popularMangaRequest`, `popularMangaParse`,
  `pageListRequest`, and similar). Each `KeiSource` suspend function makes its request and parses
  its response together.
- `KeiSource` automatically handles URL searches: a search query that is an `HttpUrl` is routed to
  `getMangasByUrl`/`getMangaByUrl`. Do not reproduce this routing manually.
- `headersBuilder()` already supplies `Referer` and `Origin` derived from `baseUrl`. Add headers by
  overriding `Headers.Builder.configureHeaders()`, rather than replacing `headersBuilder()`.
- For remote filter data, set `supportsFilterFetching = true`, implement `fetchFilterData()` and
  the pure synchronous `getFilterList(data)`. The framework handles retrying and caching.
- If a method genuinely does not apply, leave it inherited when possible. If an inherited method
  must be overridden but has no valid implementation, throw `UnsupportedOperationException()`;
  never return a dummy empty value.

## 4. Shared libraries and utilities

The `core` module provides built-in utilities in `keiyoushi.utils.*`, `keiyoushi.network.*`, and `keiyoushi.zip.*`. These are automatically available to every extension. Always reuse these core helpers rather than writing custom parsing, hashing, or network logic:

- **JSON (`keiyoushi.utils.Json`):** `response.parseAs<T>()`, `toJsonString()`, and `toJsonRequestBody()`. Streams and parses using the shared `Json` configuration.
- **Protobuf (`keiyoushi.utils.Protobuf`):** `parseAsProto<T>()`, `toRequestBodyProto()`, `decodeProto()`, and `encodeProto()`.
- **Dates (`keiyoushi.utils.Date`):** `Instant.tryParse(dateStr)` for strict ISO-8601, and `tryParseDate(dateStr, zoneId)`, `tryParseDateTime(dateStr, zoneId)`, `tryParseZonedDateTime(dateStr)` with `java.time.format.DateTimeFormatter` for site-specific formats. Never use `SimpleDateFormat` in new code.
- **Cryptography & Hashing (`keiyoushi.utils.Crypto`):** `md5()`, `sha1()`, `sha256()`, and cipher/AES helper extensions.
- **Binary Conversions (`keiyoushi.utils.Binary`):** `decodeBase64()`, `encodeBase64()`, `decodeHex()`, `encodeHex()`, and byte array transformations.
- **Decompression (`keiyoushi.utils.Inflater`):** `inflate()` and zlib/deflate stream decompression.
- **HTTP & URLs (`keiyoushi.network.OkHttp`):** Suspend helpers `client.get`, `client.post`, `client.put`, `client.head`, `response.asJsoup()`, `setUrlWithoutDomain()`, `absUrl()`, and `HttpUrl` accessors.
- **Rate Limiting (`keiyoushi.network.RateLimit`):** `clientBuilder.rateLimit(permits, period, unit)`.
- **WebView (`keiyoushi.utils.WebView`):** `runWebView` (suspending), `runWebViewBlocking` (non-suspending only), and `getLocalStorage`.
- **Next.js & React Flight (`keiyoushi.utils.NextJs`, `keiyoushi.utils.reactFlight.*`):** `extractNextJs()`, `extractNextJsRsc()`, and React Server Components data extractors (`ReactFlightBigInt`, `ReactFlightDate`, `ReactFlightNumber`).
- **GraphQL (`keiyoushi.utils.GraphQL`):** `graphQLPost()`, `graphQLGet()`, `parseGraphQLAs()`, and persisted-query helpers.
- **Filter Inspection (`keiyoushi.utils.Collections`):** `firstInstance<T>()` and `firstInstanceOrNull<T>()`.
- **Preferences (`keiyoushi.utils.Preferences`):** `getPreferences()` and `getPreferencesLazy()`.
- **Dynamic JSON (`keiyoushi.utils.JsonElement`):** Null-safe `JsonElement` property and primitive accessors (`string`, `int`, `boolean`, `jsonObject`, `jsonArray`).
- **Archive Streaming (`keiyoushi.zip.Zip`):** `readZipDirectory()` and `readZipEntry()` for memory-efficient zip stream processing.
- **Application Context (`keiyoushi.utils.Context`):** `appContext` for components that legitimately require an Android `Context`.

Do not create a local `Json` instance for standard parsing. `parseAs` uses the shared instance;
create a custom instance only when a real custom configuration or serializer requires it.

Check `lib/` before implementing specialized behavior. Examples include `lib-cookieinterceptor` for
cookies, `lib-cryptoaes` for CryptoJS-compatible AES, `lib-dataimage` for `data:image` URLs,
`lib-e4p`, `lib-i18n`, `lib-lzstring`, `lib-randomua`, `lib-synchrony`, `lib-textinterceptor`,
`lib-unpacker`, and `lib-zipinterceptor`. Add an extension dependency with:

```kotlin
dependencies {
    implementation(project(":lib:<name>"))
}
```

Use `api()` instead of `implementation()` for a library dependency that a multi-source theme must
expose transitively to its child extensions. Consult `gradle/libs.versions.toml` before adding an
external dependency; use `compileOnly` for compatible dependencies already supplied by the app and
`compileOnlyApi` when that dependency must also be visible to module consumers.

## 5. DTOs, JSON, and Protobuf

- Model JSON and Protobuf payloads with `@Serializable` regular `class` declarations, not
  `data class` declarations. This avoids unnecessary bytecode.
- Keep only fields the source uses. Make fields private where the mapping helper does not need
  them, and place DTO-to-model mappers in the DTO file.
- Use `@SerialName` only when the wire key differs from the idiomatic camelCase property name.
- Do not assign fake defaults to mandatory remote data merely to avoid a parse failure. Missing
  IDs, titles, and other mandatory fields should fail early.
- Prefer typed DTOs plus `parseAs<T>()` over walking `JsonObject`/`JsonArray` manually.
- Prefer a serializable request DTO plus `toJsonRequestBody()` over `buildJsonObject` or hand-built
  JSON strings.
- Do not read an entire response into a string for ordinary JSON parsing. `response.parseAs<T>()`
  streams, parses, and closes the body correctly.
- Use the Protobuf helpers rather than a private codec instance or manual response-body handling.

## 6. HTTP, cookies, and site protection

- In suspending `KeiSource` code, use `client.get`/`post`/`put`/`head`; do not call
  `client.newCall(...).execute()` and block a thread.
- Use `GET()`/`POST()` only when a `Request` object itself is needed. Always supply `headers` to
  those builders so app defaults, including the User-Agent, are retained.
- Always close manually consumed response bodies, normally with `use { }`. Helpers that consume a
  response already document their own lifecycle.
- When a root-site `Referer` is needed, use `"$baseUrl/"` with the trailing slash.
- Use string interpolation for static URLs. Use `HttpUrl` builders only for encoded or conditional
  query parameters, pass an `HttpUrl` directly to request helpers, and use `HttpUrl` accessors for
  URL parsing instead of string splits or regexes.
- Do not hard-code a User-Agent unless the site demonstrably requires a distinct browser or mobile
  layout. The default headers already include the app User-Agent.
- Build a custom client from `network.client.newBuilder()`, not the deprecated
  `network.cloudflareClient`.
- Use `keiyoushi.network.rateLimit` on the client builder; never use `Thread.sleep()` for rate
  limiting.
- Use `lib-cookieinterceptor` for custom cookies. Manually adding a `Cookie` header can overwrite
  existing app, WebView, or Cloudflare cookies.
- Use GraphQL helper functions and Kotlin multi-dollar raw strings (`$$"""`) for GraphQL query
  text. Do not hand-build GraphQL JSON.
- Keep proxy settings and trust-all SSL code local to debugging. Remove them before submitting.

## 7. HTML, images, and memory use

- Parse standard HTML responses with `response.asJsoup()`.
- Parse HTML embedded in a JSON field with `Jsoup.parseBodyFragment(html, baseUrl)` so relative
  links resolve correctly.
- Jsoup's `text()` and `ownText()` already normalize and trim whitespace. Do not follow them with
  `trim()` or use `isBlank()` when `isNotEmpty()` expresses the check.
- Use stable, specific selectors and let required selectors fail instead of hiding a site break with
  placeholder data. Use safe calls for optional fields and `mapNotNull` for lists where an invalid
  optional entry should not discard the rest.
- Store manga and chapter URLs as IDs, slugs, or relative URLs when possible. Avoid absolute URLs so
  a future domain migration works. Use `setUrlWithoutDomain` carefully, encoding spaces when needed.
- Use `Page(index, imageUrl = url)` rather than legacy empty-string positional arguments. Fill all
  image URLs in `getPageList` when possible; override `getImageUrl` only for genuinely lazy image
  resolution.
- Process image descrambling, stitching, and decryption as streams. Avoid loading whole image
  bodies into `ByteArray`; use response streams, Okio buffers, and cipher sources.
- Declare reusable `Regex` and date formatters at class scope. Do not retain unbounded or large
  fetched lists, DTOs, or page lists in a long-lived source instance.
- Prefer `buildString { }` to constructing a `StringBuilder` manually. Do not pass the default
  `", "` separator to `joinToString()`.

## 8. Implement the source call flow completely

Implement the `KeiSource` entry points that the site supports, in this logical order: Popular,
Latest, Search, Details, Chapters, Pages, Filters, then utilities.

- `getPopularManga(page)` returns `MangasPage`. Set each list entry's `url`, `title`, and
  `thumbnail_url`; support pagination until `hasNextPage` is false.
- `getLatestUpdates(page)` follows the same pattern. If the site has only one listing that is
  appropriate for latest updates, use it for popular and set `supportsLatest = false`.
- `getSearchMangaList(query, page, filters)` implements normal text search. If the source cannot
  search, return `MangasPage(emptyList(), false)`. Build a `FilterList` only for filters the site
  supports and derive request values from each filter's `state`.
- `getMangaDetails` supplies the full `SManga`; `getChapterList` supplies chapters. A manga's
  `title` and `url`, and a chapter's `name`, are mandatory. Do not substitute `"Untitled"`,
  `"Unknown"`, or empty strings for broken required data.
- `SChapter.date_upload` is milliseconds since the Unix epoch. Use `Instant.tryParse(dateStr)` for
  ISO-8601 dates, and `DateTimeFormatter` with `tryParseDate`, `tryParseDateTime`, or
  `tryParseZonedDateTime` for site-specific formats. Avoid `SimpleDateFormat`.
- If details and chapters come from the same response, `fetchMangaUpdate` should fetch and parse it
  once and return both. If they require different endpoints, respect the requested flags and fetch
  the two concurrently when both are needed.
- `getPageList(chapter)` returns a sorted list of pages. Page indexes are ignored by the app; sort
  the returned list yourself. `Page.url` and `Page.imageUrl`, when present, must be absolute.
- When image URLs are unavailable until a later request, leave `imageUrl` empty and resolve it in
  `getImageUrl(page)`. URL fragments can safely carry local metadata for an image request because
  OkHttp does not send fragments to the server.
- Use `UpdateStrategy.ONLY_FETCH_ONCE` only for titles known to have a fixed, permanently complete
  chapter list. The default is `ALWAYS_UPDATE`.

## 9. Preferences, deeplinks, and source lifecycle

- For mirrors and custom domains, use the generated `baseUrl` DSL described above. Do not write
  manual `SharedPreferences` migration or base-URL selection code.
- For unrelated settings such as image quality or language, implement `ConfigurableSource` and use
  `getPreferences()` or `getPreferencesLazy()`.
- Declare URL handling with one or more `deeplink {}` blocks in `build.gradle.kts`. Each block needs
  at least one `path()` pattern; omit `host()` when deriving it from `baseUrl` is sufficient.
- Never add a manual `AndroidManifest.xml`, intent filter, or `UrlActivity.kt` for a source.
- Avoid hard-coded host comparisons during URL search. Compare against the current `baseUrl` so
  mirrors and custom domains remain valid.
- Preserve source IDs during renames and language changes, and use the repository's documented
  migration procedure rather than creating a duplicate source identity.

## 10. Multi-source themes

- Create themes under `lib-multisrc/<theme>/`. A new theme uses the multisrc plugin,
  `baseVersionCode`, and the current library version:

  ```kotlin
  plugins {
      alias(kei.plugins.multisrc)
  }

  keiyoushi {
      baseVersionCode = 1
      libVersion = "1.6"
  }
  ```

- The theme main class is an abstract `KeiSource` class (or the version used by an existing legacy
  theme). Do not put injected source metadata in its constructor or class body.
- Theme-level deeplink path patterns may omit a host so each consuming extension derives it from its
  own `baseUrl`.
- Child extensions declare their own source metadata in `source {}` blocks and inherit shared
  behavior from the theme. Export theme dependencies with `api()` where necessary.
- Do not change a multi-source theme when the requested fix is isolated to a single child source.

## 11. Local validation and submission

- Test changes by compiling and running the touched extension in Android Studio on a device or
  emulator. An untested extension is not ready for review.
- Format only the module changed, for example:

  ```console
  ./gradlew :src:en:mysource:spotlessApply
  ./gradlew :lib-multisrc:mytheme:spotlessApply
  ```

- Build a single source from the command line with
  `./gradlew :src:<lang>:<source>:assembleDebug` when appropriate.
- Use Logcat (the `OkHttpClient` tag), Android Studio's Network Inspector, or a temporary local
  proxy to investigate network failures. Remove proxy and insecure SSL debugging code afterward.
- Keep the generated extension icon in the repository pattern: a rounded-square icon. Remove
  `web_hi_res_512.png` from a new extension before submission.
- Before opening a pull request, update the relevant `versionCode` (and `baseVersionCode` for a
  changed theme), set the right content warning, preserve names and IDs, reference related issues,
  and complete the repository PR checklist.
- If an AI opens the pull request, its description must end with a `🤖` note stating what it was
  asked to do and that an AI agent opened the PR. The human reviewer must still check the
  AI-assisted checklist item after reviewing the changes.

## 12. Hard rejections

Reject or correct these patterns during implementation and review:

- A new source extending `HttpSource` directly, using `libVersion = "1.4"`, or implementing
  `SourceFactory`.
- Manual `name`, `lang`, `id`, or `baseUrl` declarations in an `@Source` class.
- Base-URL/mirror/custom-domain preferences implemented outside the `baseUrl` DSL.
- Manual manifest deeplinks or `UrlActivity` code.
- `data class` DTOs, manual standard `Json` instances, hand-walked JSON where typed DTOs fit, or
  manually read JSON response bodies.
- Using `SimpleDateFormat` in new code or manual try-catch date parsing instead of the shared
  `tryParse` helpers.
- Blocking network calls in suspending source code, `Thread.sleep()`, hard-coded cookies, or
  deprecated `network.cloudflareClient`.
- Empty placeholders or generic fallbacks for mandatory manga or chapter data.
- Long-lived caches of large response data, image `ByteArray` processing, unnecessary URL builders,
  redundant Jsoup string parsing, or an unnecessary `getImageUrl` override.
- Committing temporary proxy, trust-all SSL, or other debugging-only network code.

When a site requires behavior not covered here, verify the current `CONTRIBUTING.md`, inspect a
nearby modern implementation, and make the smallest change that satisfies the site requirement.
