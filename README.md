# sync4j-web

A web server to run and monitor [sync4j](https://github.com/fathzer/sync4j) synchronizations, with a single page UI.

It is a reusable component: an application provides a configuration (the *libraries* to synchronize) and, optionally, plugins that add what is specific to the application (for instance, asking a photo server to scan a library when a synchronization is over).

- Runs the synchronization of a library (one at a time per library), can cancel it, and keeps the status of the last one.
- Sends the progress to the web page through a WebSocket.
- Single port for HTTP and WebSocket, [Javalin](https://javalin.io/) based.
- Optional login (JWT in a cookie). The users are checked by an `Authenticator` provided by the application.
- Providers are pluggable: a provider library ships its own factory (sync4j provides `local` and `memory`, sync4j-pcloud provides `"provider": "pCloud"`).
- Extensible with plugins.

## Requirements

- Java 21
- sync4j 0.0.2 and [sync4j-config](https://github.com/sync4j/sync4j-config) 0.0.1 (the reading of the `synchronization` part of the configuration is done by sync4j-config, please see its documentation for the format of the libraries and the providers)

## Usage

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

### Plugins

A plugin implements `SyncPlugin`:

```java
public class MyPlugin implements SyncPlugin {
    public String id() { return "myplugin"; }

    public void init(PluginContext context) throws IOException {
        // context.config(): the configuration of the plugin
        // context.routes(): the routes of the Javalin app, to register the ones of the plugin (only here: Javalin needs them before the server starts).
        // context.loginManager() protects them
    }

    public List<String> check(Library library) throws IOException {
        // The problems found in the library (an empty list if there is none), displayed to the user
        return List.of();
    }

    public void beforeSync(SyncRequest request) throws IOException {
        // Throw an IOException to refuse the synchronization (the message is sent to the user)
    }

    public void afterSync(SyncRequest request, SyncStatus status) {
        // Called when a synchronization is over, resources are released.
        // It should return quickly: use another thread for long jobs and report with context.publish(...)
    }
}
```

- `check(library)` lets a plugin tell that it can't do its job for a library (for instance, because a server it uses does not know the library). The web page asks it when the user selects a library, displays the problems in a box and offers to check again (the plugin is asked each time, so the result should not be cached for long and it should return quickly). If it throws an `IOException`, the message is displayed as a problem. These problems only inform the user: a plugin that wants to prevent a synchronization has to refuse it in `beforeSync`.
- `SyncServer.Builder.plugin(plugin)` always activates a plugin.
- A plugin can also be provided by a `SyncPluginFactory` that is discovered (see below). It is activated only if the configuration has a section for its id. A section that no plugin uses is reported in the log (it can be a typo or a plugin that could not be loaded).
- `PluginContext.publish(libraryId, data)` sends `{"type":"PLUGIN","plugin":"id","library":"libraryId","data":...}` to the clients that watch the library. The web page shows what the plugin does in **a card of its own** (for the end user) when `data` has a `state` (`RUNNING`, `DONE`, `FAILED` or `UNKNOWN`, displayed as a badge), `details` (an array of sentences) or a `progress` (`{"done":1,"total":2}`, a progress bar). `label` is the title of the card (the id of the plugin by default) and `message` is its sentence. The texts (`label`, `message` and the items of `details`) are strings, or `Message`s that the page translates (see "Translations" below). The messages are also added to the technical log (see below). The last message that has a `state` is sent again to the clients that connect later (for instance, a user that loads the page again), until the next synchronization of the library starts. This state is also saved in the status file (`SyncServer.Builder.statusFile`, `syncStatuses.json` by default), next to the statuses of the libraries, so it survives a restart of the server. A `RUNNING` state that is loaded is changed to `UNKNOWN`, because nobody monitors the plugin anymore.

### Discovery of plugins and providers

The `SyncPluginFactory` implementations (and the `FileProviderFactory` ones, see sync4j-config) are discovered with [plugin-loader](https://github.com/fathzer/plugin-loader):

- on the classpath of the application,
- and in the jar files of a directory, if one is given (`SyncServer.Builder.pluginsDirectory(dir)` and `ProviderRegistry.withDefaults(dir)`). It allows to add a plugin or a provider to an application without rebuilding it.

As for `java.util.ServiceLoader`, an implementation is declared in the `META-INF/services/<full name of the interface>` file of its jar (one class name per line) and needs a public constructor without argument.

An implementation that can't be loaded (missing class, wrong type, unreadable jar, ...) is logged and ignored: it does not prevent the other ones to be discovered. The classes of a jar of the directory can use the classes of the application.

If the application is packaged in a single jar (for instance with the Maven shade plugin), the `META-INF/services` files of its dependencies have to be merged (`ServicesResourceTransformer`).

### Authentication and the secret

When the security is enabled, a user has to log in to use the application: the API of the libraries (`/api/libraries/**`, to read as well as to change something) and the WebSocket refuse the users that are not logged in (HTTP 401, the WebSocket is not even opened). Only the web page and its files, the login API (`/api/login`) and the title and the translations of the page (`/api/ui`, `/api/i18n`) can be used without being logged in, because the page has to be displayed to show the login form. The page loads its data after the login, and shows the login form again if the session expires. The routes added by a plugin are protected by the plugin (see `PluginContext.loginManager()`).

How the login works:

1. The user types a name and a password in the web page. The server gives them to the `Authenticator` of the application, that decides if they are valid. It can delegate to any service (a photo server, an LDAP, ...): it returns the identifier of the user, or throws an `AuthenticationFailedException` to reject the credentials (the HTTP status and the body of the answer are sent to the client).
2. If they are valid, the server builds a small *ticket* that says "this is *user*, valid for 7 days". The ticket is signed, so it can't be modified: it is a [JWT](https://jwt.io/) (JSON Web Token). The server sends it to the browser in a cookie.
3. The browser sends the cookie back with every request. The server only has to check the signature of the ticket to know who is calling: it does not ask the password again, and it does not store anything about the session.

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
| GET | `/api/i18n` | The language chosen for the user and its translations: `{"language": "fr", "messages": {...}}`. Public too |
| GET | `/api/libraries/checks/{id}` | The problems found by the plugins in a library: `[{"plugin": "id", "message": {"key": ..., "fallback": "...", "args": [...]}}]` (empty if none, see `Message`) |
| WS | `/ws/sync/{libraryId}` | `STATUS`, `PROGRESS` and `PLUGIN` messages |
| POST/GET/DELETE | `/api/login` | Log in (`{"user", "password"}`), check the login (`{"user"}` or 401), log out. The only routes of the API that don't need a login |

All the routes of the table, except `/api/ui`, `/api/i18n` and `/api/login`, need the user to be logged in if the security is enabled (otherwise, they answer 401).

### The web page

- It has a card to start a synchronization, a card with its progress, whose badge (*In progress*, *Done*, *Failed*, *Cancelled*) and colored edge tell the state, and that folds itself when the synchronization succeeds (the user can unfold it), one card per plugin (see above) and a **technical log** card, collapsed by default.
- The title of the page is `SyncServer.Builder.title(...)` ("Synchronization Dashboard" by default). The page reads it from `/api/ui`, that does not need a login (the login form is displayed with the title).
- It follows the system theme: dark if the system asks for a dark one (`prefers-color-scheme`).
- On a phone, a single button is displayed to save some height: *Start Synchronization*, replaced by *Stop Synchronization* while the library is synchronizing.
- It is translated (see below).
- To customize the page more, pass a directory to `SyncServer.Builder.staticFiles`: its files replace the default ones.

### Translations

The page is displayed in the language of the user: the first of the languages of the browser (the `Accept-Language` header, that is the language of the system) that has translations, English otherwise. French is provided.

- The English texts are in the page and in the scripts: they are the fallbacks. A translation is a UTF-8 `.properties` file named `sync4j-web/i18n/messages_<language>.properties` (the keys are the ones of `index.html` and of the scripts: `data-i18n` attributes and `i18n.t(key, fallback, args...)`). `{0}`, `{1}`... are replaced by the arguments (there is no other special character: an apostrophe is an apostrophe).
- `GET /api/i18n` (public, as the login form is translated) gives `{"language": "fr", "messages": {...}}`. `?lang=fr` forces a language.
- A plugin translates its texts with `Message.of(key, "English text with {0}", args...)` (use `Message.text` for a text that can't be translated) and gives the translations of a language with `SyncPlugin.translations(language)`, for instance `Translations.load(getClass().getClassLoader(), "my-plugin/messages", language)` that reads `my-plugin/messages_fr.properties`. Keys are shared by the page and all the plugins: start the ones of a plugin with its id. `SyncPlugin.check` returns `Message`s too.
- The application can add (or replace) translations with `SyncServer.Builder.translations(language -> map)`. The key `app.title` is the title of the page, unless `Builder.title(...)` is used.
- What is not translated: the error messages that the server answers to the requests (for instance a refusal of a plugin in `beforeSync`) and the names of the libraries.

## Known limitations

- The tests of the web page need a browser, see below: they are skipped if it is not available.

## Running the tests

`mvn test` runs all the tests. The tests of the web page (`WebPageBrowserTest`) use a real browser: they start a server, drive a headless Chrome with the Chrome DevTools Protocol, and check what the page does (the login, the data that is loaded, the WebSocket, a synchronization, the expiry of the session, ...).

- They are skipped if Chrome can't be found. It is searched in the `PATH` (`google-chrome`, `chromium`, ...). Use the `sync4j.chrome` system property (or the `CHROME_BIN` environment variable) to give its path: `mvn test -Dsync4j.chrome=/opt/chrome/chrome`.
- Use `-Dsync4j.browser.tests=false` to skip them.
- The browser is started with `--no-sandbox`, so it can run in a container.

