# sync4j-web

[![Maven Central](https://img.shields.io/maven-central/v/com.fathzer/sync4j-web)](https://search.maven.org/artifact/com.fathzer/sync4j-web)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Javadocs](https://www.javadoc.io/badge/com.fathzer/sync4j-web.svg)](https://www.javadoc.io/doc/com.fathzer/sync4j-web)
[![SonarCloud](https://sonarcloud.io/api/project_badges/measure?project=sync4j_sync4j-web&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=sync4j_sync4j-web)

A web server to run and monitor [sync4j](https://github.com/sync4j/sync4j) synchronizations, with a single page UI.

It is a reusable component: an application provides a configuration (the *libraries* to synchronize) and, optionally, plugins that add what is specific to the application (for instance, asking a photo server to scan a library when a synchronization is over).

- Runs the synchronization of a library (one at a time per library), can cancel it, and keeps the status of the last one.
- Sends the progress to the web page through a WebSocket.
- Single port for HTTP and WebSocket, [Javalin](https://javalin.io/) based.
- Optional login (JWT in a cookie). The users are checked by an `Authenticator` provided by the application.
- Providers are pluggable: a provider library ships its own factory (sync4j provides `local` and `memory`, sync4j-pcloud provides `"provider": "pCloud"`).
- Extensible with plugins.

## Table of contents

- [Requirements](#requirements)
- [Usage](#usage)
  - [Configuration](#configuration)
  - [Providers](#providers)
  - [Synchronizations](#synchronizations)
- [The web page](#the-web-page)
  - [Installing the page as an application](#installing-the-page-as-an-application)
  - [Deployment behind a reverse proxy](#deployment-behind-a-reverse-proxy)
- [Plugins](#plugins)
  - [Writing a plugin](#writing-a-plugin)
  - [What a plugin displays in the page](#what-a-plugin-displays-in-the-page)
  - [Activation of the plugins](#activation-of-the-plugins)
  - [Discovery of plugins and providers](#discovery-of-plugins-and-providers)
- [Translations](#translations)
  - [Choice of the language](#choice-of-the-language)
  - [Who translates what](#who-translates-what)
  - [Translation files](#translation-files)
  - [Adding a language](#adding-a-language)
  - [Translating a plugin](#translating-a-plugin)
  - [What is not translated](#what-is-not-translated)
- [Authentication and the secret](#authentication-and-the-secret)
- [API](#api)
- [Known limitations](#known-limitations)
- [Running the tests](#running-the-tests)

## Requirements

- Java 21

## Usage

Add the dependency to your project (it brings sync4j, [sync4j-config](https://github.com/sync4j/sync4j-config), Javalin and the JWT libraries):

```xml
<dependency>
    <groupId>com.fathzer</groupId>
    <artifactId>sync4j-web</artifactId>
    <version>0.0.1</version>
</dependency>
```

Then build and start the server:

```java
SyncWebConfig config = ConfigLoader.load(Paths.get("config.json"), ProviderRegistry.withDefaults());
try (SyncServer server = SyncServer.builder(config)
        .authenticator((user, password) -> checkCredentials(user, password)) // Required if the configuration has a secret
        .plugin(new MyPlugin())                                              // Optional
        .build()) {
    server.start(); // Port is the value of the server.port system property, 8080 by default
    Thread.currentThread().join();
}
```

### Configuration

The `synchronization` part of the configuration is read by sync4j-config: please see its documentation for the format of the libraries and the providers.

```json
{
  "secret": "${JWT_SECRET}",
  "secureCookie": true,
  "synchronization": {
    "libraries": [
      {
        "id": "photos",
        "label": "My photos",
        "source": { "provider": "pCloud", "path": "/Photos", "config": { "zone": "us", "token": "${PCLOUD_TOKEN}" } },
        "destination": { "provider": "local", "path": "${LOCAL_PATH_PREFIX}/photos" },
        "filters": ["- *.xmp"]
      }
    ],
    "filters": ["+ /important/**.db", "- *.tmp", "- *.db*"]
  },
  "plugins": {
    "myplugin": { "any": "configuration of the plugin" }
  }
}
```

- `secret`: the key that signs the login tickets (see [Authentication and the secret](#authentication-and-the-secret)). **If it is missing, the security is disabled.**
- `secureCookie`: `true` by default. The session cookie of the login is then sent by browsers only over https (they also accept it for `localhost`). If the server is used over http, for instance on a local network, the browser silently ignores the cookie: the login seems to work but the user is logged out at the next click. Set it to `false` in that case (a warning is logged at startup, because anybody who listens to the network can then steal a login).
- `filters`: the rules that tell which entries are synchronized: `- pattern` excludes the entries that match the glob pattern, `+ pattern` includes them. The first rule that matches an entry decides and an entry that no rule matches is synchronized, so an exception goes before the general rule (`["+ /important/**.db", "- *.db*"]` excludes the `.db` files except the ones of `/important`). A pattern that starts with `/` is anchored to the root of the library, any other one can match at any level (`*.tmp` matches the `.tmp` files of all the folders). The rules of `synchronization` apply to all the libraries, the optional ones of a library are tried **before** them, so a library can override a global rule. See `FilterRules` in sync4j for the details (`**`, folders, limits). An invalid rule is an error, and so is the old `excludePatterns`.
- `id`: only alphanumeric characters, `-` and `_`. `label` is optional (the id by default).
- `${VARIABLE}` is replaced by the value of the environment variable in `secret` and `path` and in the configuration of the providers (it is an error if the variable is not defined).
- The configuration of a plugin is the value of its id in `plugins`. The root of the file can only have `secret`, `synchronization` and `plugins`: any other property (for instance a plugin section that is not in `plugins`, or a typo) is an error.

### Providers

The `provider` attribute of a source or destination is the type of a `FileProviderFactory` (defined in sync4j). The factories are managed by the `ProviderRegistry` of sync4j-config:

- `local` and `memory` are provided by sync4j.
- A provider library that ships its factory is available as soon as it is on the classpath of the application: for instance, add sync4j-pcloud to use `"provider": "pCloud"`.
- Other ones can be registered with `registry.register(factory)`, or found in the jar files of a directory with `ProviderRegistry.withDefaults(pluginsDirectory)` (see [Discovery of plugins and providers](#discovery-of-plugins-and-providers)).

### Synchronizations

- The libraries are synchronized with 4 walk threads, 8 comparison threads and 5 copy threads.
- An error does not cancel the synchronization: the other entries are synchronized, and the status of the library is `FAILED` if some errors remain after the retries of sync4j.
- Everything the synchronization opens (providers, thread pools) is released when it is over.

## The web page

The page is served by the server itself (it is in the jar of sync4j-web):

- It has a card to start a synchronization, a card with its progress, one card per plugin (see [What a plugin displays in the page](#what-a-plugin-displays-in-the-page)) and a **technical log** card, collapsed by default.
- The badge of the card of the synchronization (*In progress*, *Done*, *Failed*, *Cancelled*) and its colored edge tell the state. The card folds itself when the synchronization succeeds (the user can unfold it).
- The title of the page is `SyncServer.Builder.title(...)`, or the translation of `app.title` (see [Who translates what](#who-translates-what)), "Synchronization Dashboard" by default. The page reads it from `/api/ui`, that does not need a login (the login form is displayed with the title).
- It follows the system theme: dark if the system asks for a dark one (`prefers-color-scheme`).
- On a phone, a single button is displayed to save some height: *Start Synchronization*, replaced by *Stop Synchronization* while the library is synchronizing.
- It is displayed in the language of the user (see [Translations](#translations)).
- To customize the page more, an application can ship files in its jar and give their folder to `SyncServer.Builder.staticResources("/my-app/www")`: they replace the default files that have the same name (its icons, for instance). The files of the directory given to `SyncServer.Builder.staticFiles` replace both (for an administrator that wants to change something without rebuilding the application).

### Installing the page as an application

The page is a Progressive Web App (PWA): the browser can install it as an application (an icon on the home screen or on the desktop, a window of its own).

- The browsers only propose it when the page is served over **https**, with a certificate they trust (or from `localhost`). See [Deployment behind a reverse proxy](#deployment-behind-a-reverse-proxy).
- The application does not need to know its domain (the URLs of its manifest are relative), but each address is a different application for the browser (`https://nas.example.org` and `https://192.168.1.10` are installed separately).
- The manifest (`/manifest.webmanifest`) is built by the server: the name is the title of the page (see above), the short name, displayed under the icon, is the translation of `app.shortName` (or the title, if `Builder.title(...)` is used).
- The icons are `icons/icon-192.png`, `icons/icon-512.png`, `icons/icon-maskable-512.png` (whose drawing stays in the inner 80%, as the system can cut the corners) and `icons/apple-touch-icon.png` (180x180, for iOS). To use other ones, put files with the same names in the `staticResources` folder of the application (or in the `staticFiles` directory).
- A service worker (`sw.js`) caches the files of the page, so the application opens even if the server is not available: it then says that the server can't be reached (in the language of the user: the translations are cached too), and loads again by itself when the server is back. The API (except the title and the translations) and the WebSocket are never cached.
- On iOS, the installed application does not share the cookies of Safari: the user has to log in once in the application.

### Deployment behind a reverse proxy

The server speaks http. To serve the page over https (needed to install it as an application, and recommended as soon as others can reach it, because of the login), put a reverse proxy with a certificate trusted by the devices in front of it. The proxy has to:

- serve the page at the root of an address (`https://sync.example.org/`, not in a sub-path): the page calls `/api/...` and `/ws/...`,
- forward the WebSockets (`/ws/...`).

## Plugins

A plugin adds to the server what is specific to an application (for instance, asking a photo server to scan a library after a synchronization).

### Writing a plugin

A plugin implements `SyncPlugin`:

```java
public class MyPlugin implements SyncPlugin {
    public String id() { return "myplugin"; }

    public void init(PluginContext context) throws IOException {
        // context.config(): the configuration of the plugin
        // context.routes(): the routes of the Javalin app, to register the ones of the plugin (only here: Javalin needs them before the server starts).
        // context.loginManager() protects them
    }

    public List<Message> check(Library library) throws IOException {
        // The problems found in the library (an empty list if there is none), displayed to the user
        return List.of();
    }

    public void beforeSync(SyncRequest request) throws IOException {
        // Throw a SyncRefusedException to refuse the synchronization: its message (a Message or a string) is sent to the user.
        // Any other exception is an error of the server: it is logged, and the user only receives a reference to it.
    }

    public void afterSync(SyncRequest request, SyncStatus status) {
        // Called when a synchronization is over, resources are released.
        // It should return quickly: use another thread for long jobs and report with context.publish(...)
    }

    public Map<String, String> translations(String language) {
        // The translations of the texts of the plugin (see "Translating a plugin")
        return Translations.load(getClass().getClassLoader(), "myplugin/messages", language);
    }
}
```

- `check(library)` lets a plugin tell that it can't do its job for a library (for instance, because a server it uses does not know the library). The web page asks it when the user selects a library, displays the problems in a box and offers to check again (the plugin is asked each time, so the result should not be cached for long and it should return quickly). If it throws an `IOException`, its message is displayed as a problem. These problems only inform the user: a plugin that wants to prevent a synchronization has to refuse it in `beforeSync`.

### What a plugin displays in the page

`PluginContext.publish(libraryId, data)` sends `{"type":"PLUGIN","plugin":"id","library":"libraryId","data":...}` to the clients that watch the library.

- The web page shows what the plugin does in **a card of its own** (for the end user) when `data` has a `state` (`RUNNING`, `DONE`, `FAILED` or `UNKNOWN`, displayed as a badge), `details` (an array of sentences) or a `progress` (`{"done":1,"total":2}`, a progress bar). `label` is the title of the card (the id of the plugin by default) and `message` is its sentence.
- These texts (`label`, `message` and the items of `details`) are strings, or `Message`s that the page translates (see [Translating a plugin](#translating-a-plugin)).
- `message` is also added to the technical log.
- The last message that has a `state` is sent again to the clients that connect later (for instance, a user that loads the page again), until the next synchronization of the library starts. It is also saved in the status file (`SyncServer.Builder.statusFile`, `syncStatuses.json` by default), next to the statuses of the libraries, so it survives a restart of the server. A `RUNNING` state that is loaded is changed to `UNKNOWN`, because nobody monitors the plugin anymore.

### Activation of the plugins

- `SyncServer.Builder.plugin(plugin)` always activates a plugin.
- A plugin can also be provided by a `SyncPluginFactory` that is discovered (see below). It is activated only if the configuration has a section for its id. A section that no plugin uses is reported in the log (it can be a typo or a plugin that could not be loaded).

### Discovery of plugins and providers

The `SyncPluginFactory` implementations (and the `FileProviderFactory` ones, see sync4j-config) are discovered with [plugin-loader](https://github.com/fathzer/plugin-loader):

- on the classpath of the application,
- and in the jar files of a directory, if one is given (`SyncServer.Builder.pluginsDirectory(dir)` and `ProviderRegistry.withDefaults(dir)`). It allows to add a plugin or a provider to an application without rebuilding it.

As for `java.util.ServiceLoader`, an implementation is declared in the `META-INF/services/<full name of the interface>` file of its jar (one class name per line) and needs a public constructor without argument.

An implementation that can't be loaded (missing class, wrong type, unreadable jar, ...) is logged and ignored: it does not prevent the other ones to be discovered. The classes of a jar of the directory can use the classes of the application.

If the application is packaged in a single jar (for instance with the Maven shade plugin), the `META-INF/services` files of its dependencies have to be merged (`ServicesResourceTransformer`).

## Translations

### Choice of the language

The page is displayed in the language of the user: the first of the languages of the browser (the `Accept-Language` header, that is the language of the system) that has a translation of the page (a `sync4j-web/i18n/messages_<language>.properties` file, see [Adding a language](#adding-a-language)), English otherwise. sync4j-web provides English and French.

`GET /api/i18n` gives the chosen language and all its translations (`{"language": "fr", "messages": {...}}`), `?lang=fr` forces a language. It does not need a login, as the login form is translated too.

### Who translates what

The texts of the page come from three places, merged in this order (a place can replace the texts of the previous ones):

| Texts | Translated by | Files |
|---|---|---|
| The texts of sync4j-web: the page itself (labels, buttons, statuses, technical log, ...) and the few messages that its Java code sends to the page (the title, a `check` that failed, a plugin interrupted by a restart) | sync4j-web | `sync4j-web/i18n/messages_<language>.properties`, in the jar of sync4j-web |
| The texts that a plugin sends to the page (its card, the problems found by `check`) | The plugin, see [Translating a plugin](#translating-a-plugin) | The files of the plugin |
| Anything else the application wants to change, for instance its title (`app.title`) | The application, with `SyncServer.Builder.translations(language -> map)` | Up to the application |

The title set with `SyncServer.Builder.title(...)` is not translated.

### Translation files

A translation file is a UTF-8 `.properties` file named `<base>_<language>.properties` (`en`, `fr`, `es`, ...), read with `Translations.load(classLoader, base, language)`. `{0}`, `{1}`... are replaced by the arguments of the text. There is no other special character: an apostrophe is an apostrophe (unlike `java.text.MessageFormat`).

**The English file is the reference**: it has all the keys, and the other files have the same keys (a test checks it for the files of sync4j-web). It also completes the other languages: a key that is missing in a translation is displayed in English. A key that has no translation at all is displayed as is (so a missing translation is visible).

The English texts of the page are also written in `index.html` and in the scripts (`data-i18n` attributes, `i18n.t(key, englishText, args...)`), where they are used if the translations can't be loaded. A test checks that they are the same as the ones of the English file: when a text of the page changes, change both.

### Adding a language

To translate sync4j-web in Spanish, for instance:

1. Copy [`messages_en.properties`](src/main/resources/sync4j-web/i18n/messages_en.properties) to `messages_es.properties` and translate its texts (keep the keys and the `{0}`, `{1}`...).
2. Then, either:
   - add it to sync4j-web, in `src/main/resources/sync4j-web/i18n/` (contributions are welcome), so every application has it;
   - or put it in the resources of your application, as `sync4j-web/i18n/messages_es.properties`: it is found on the classpath, so the page is available in Spanish without changing sync4j-web.
3. The plugins are translated by their own files (see below): a plugin that has no Spanish file is displayed in English.

### Translating a plugin

A plugin that is not translated sends simple strings. To translate its texts:

- It sends `Message`s instead of strings: a key and arguments (`Message.of("myplugin.left", 3)`, an argument can be a `Message` too). The page translates them in the language of the user, so the same message (for instance the state saved in the status file) is displayed in French to a French user and in English to an English one.
- It returns the translations of a language from `SyncPlugin.translations(language)`, usually with `Translations.load(getClass().getClassLoader(), "myplugin/messages", language)`, that reads `myplugin/messages_en.properties`, `myplugin/messages_fr.properties`... **The English file is required**, and the other ones have the same keys (see [Translation files](#translation-files)).
- The keys are shared by the page and all the plugins: start the ones of a plugin with its id.
- `Message.format(translations)` gives the text in a language, for what the page does not display: for instance `message.format(translations("en"))` for a log or the message of an exception.

### What is not translated

- The error of a failed synchronization (the `errorMessage` of its status).
- A refusal of a synchronization by a plugin (`SyncRefusedException`) with a string instead of a `Message`.
- The names of the libraries.

## Authentication and the secret

When the security is enabled, a user has to log in to use the application: the API of the libraries (`/api/libraries/**`, to read as well as to change something) and the WebSocket refuse the users that are not logged in (HTTP 401, the WebSocket is not even opened). Only the web page and its files, the login API (`/api/login`), the title of the page (`/api/ui`) and its translations (`/api/i18n`) can be used without being logged in, because the page has to be displayed to show the login form. The page loads its data after the login, and shows the login form again if the session expires. The routes added by a plugin are protected by the plugin (see `PluginContext.loginManager()`).

How the login works:

1. The user types a name and a password in the web page. The server gives them to the `Authenticator` of the application, that decides if they are valid. It can delegate to any service (a photo server, an LDAP, ...): it returns the identifier of the user, or throws an `AuthenticationFailedException` to reject the credentials (the HTTP status and the body of the answer are sent to the client).
2. If they are valid, the server builds a small *ticket* that says "this is *user*, valid for 7 days". The ticket is signed, so it can't be modified: it is a [JWT](https://jwt.io/) (JSON Web Token). The server sends it to the browser in a cookie.
3. The browser sends the cookie back with every request. The server only has to check the signature of the ticket to know who is calling: it does not ask the password again, and it does not store anything about the session.

**Attempts to guess a password are slowed down**: after 3 failed logins of a user (an `AuthenticationFailedException` with the status 401 or 403), the next ones are refused for 1 second, then 2, 4, ... up to 5 minutes (HTTP 429, with a `Retry-After` header), without calling the `Authenticator`. A successful login, or an hour without failure, forgets the failures. They are counted by user name (case insensitive), not by address, because behind a reverse proxy all the requests come from the address of the proxy: someone who knows the name of a user can delay his logins, but not for more than 5 minutes at a time.

The **secret** is the key that signs the tickets (and checks their signature). Only the server knows it, so nobody else can build a valid ticket. It means that:

- **It must stay private.** Do not commit it, put it in an environment variable instead (`"secret": "${JWT_SECRET}"`). Whoever knows it can build a ticket for any user.
- **It is not a password.** Nobody types it, and it can't be a word you chose: it has to be random, written in base64, and encode at least 32 bytes (a generated one has 64). The server refuses to start with a secret that does not fit.
- **Changing it logs everybody out**, because the existing tickets are no more valid. As the server stores no session, it is also the only way to cancel the tickets that were already issued (logging out only removes the cookie of the browser).
- **Without a secret, there is no login at all**: everybody is considered as logged in and can start synchronizations (a warning is logged at startup).

To generate a secret:

- With the jar of this library, that only needs Java: `java -jar sync4j-web-0.0.1.jar`. It prints `Secret: ` followed by the secret (copy the text that follows `Secret: `).
- With the code: `JwtLoginManager.buildSecret()` (or `SecretGenerator.generate()`). An application can also provide its own command line for that.
- With another tool: `openssl rand -base64 64 | tr -d '\n'` (`tr` removes the line break that openssl adds in the middle of the text).

See also `secureCookie` in the configuration: it is about the cookie that carries the ticket.

## API

| Method | Path | |
|---|---|---|
| GET | `/api/libraries` | The libraries (`id`, `label`) |
| GET | `/api/libraries/sync` | The status of all the libraries |
| GET | `/api/libraries/sync/{id}` | The status of a library |
| POST | `/api/libraries/sync/{id}` | Starts a synchronization. Body: `{"suffixPath": "optional/sub/folder", "useChecksum": false}` |
| DELETE | `/api/libraries/sync/{id}` | Cancels a synchronization |
| GET | `/api/ui` | The title of the page: `{"title": "..."}`. Public, as the page and the login API |
| GET | `/manifest.webmanifest` | The manifest of the application (see [Installing the page as an application](#installing-the-page-as-an-application)). Public |
| GET | `/api/i18n` | The language chosen for the user and its translations: `{"language": "fr", "messages": {...}}` (see [Translations](#translations)). Public too |
| GET | `/api/libraries/checks/{id}` | The problems found by the plugins in a library: `[{"plugin": "id", "message": {"key": "...", "args": [...]}}]` (empty if none, see [Translating a plugin](#translating-a-plugin)) |
| WS | `/ws/sync/{libraryId}` | `STATUS`, `PROGRESS` and `PLUGIN` messages |
| POST/GET/DELETE | `/api/login` | Log in (`{"user", "password"}`), check the login (`{"user"}` or 401), log out. Public |

All the routes of the table, except `/api/ui`, `/api/i18n` and `/api/login`, need the user to be logged in if the security is enabled (otherwise, they answer 401).

When a request fails, the answer is `{"error": ...}`, where the error is a `Message` (`{"key": "...", "args": [...]}`, translated by the page) or a string. An unexpected error of the server (500) does not give its details, that can reveal the paths of the server for instance: the user receives a reference (`error.server`, with 6 hexadecimal digits), and the log of the server has the same reference with the error.

## Known limitations

- The tests of the web page need a browser, see below: they are skipped if it is not available.
- The page has to be served at the root of an address, not in a sub-path (see [Deployment behind a reverse proxy](#deployment-behind-a-reverse-proxy)).

## Running the tests

`mvn test` runs all the tests. The tests of the web page (`WebPageBrowserTest`) use a real browser: they start a server, drive a headless Chrome with the Chrome DevTools Protocol, and check what the page does (the login, the data that is loaded, the WebSocket, a synchronization, the expiry of the session, ...).

- They are skipped if Chrome can't be found. It is searched in the `PATH` (`google-chrome`, `chromium`, ...). Use the `sync4j.chrome` system property (or the `CHROME_BIN` environment variable) to give its path: `mvn test -Dsync4j.chrome=/opt/chrome/chrome`.
- Use `-Dsync4j.browser.tests=false` to skip them.
- The browser is started with `--no-sandbox`, so it can run in a container.

