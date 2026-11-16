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
        "destination": { "provider": "local", "path": "${LOCAL_PATH_PREFIX}/photos" }
      }
    ],
    "excludePatterns": ["*.tmp", "*.db*"]
  },
  "plugins": {
    "myplugin": { "any": "configuration of the plugin" }
  }
}
```

- `secret`: the key that signs the login tickets (see [Authentication and the secret](#authentication-and-the-secret)). **If it is missing, the security is disabled.**
- `secureCookie`: `true` by default. The session cookie of the login is then sent by browsers only over https (they also accept it for `localhost`). If the server is used over http, for instance on a local network, the browser silently ignores the cookie: the login seems to work but the user is logged out at the next click. Set it to `false` in that case (a warning is logged at startup, because anybody who listens to the network can then steal a login).
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
        // context.app(): the Javalin app, to register routes (context.loginManager() protects them)
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

- `SyncServer.Builder.plugin(plugin)` always activates a plugin.
- A plugin can also be provided by a `SyncPluginFactory` that is discovered (see below). It is activated only if the configuration has a section for its id. A section that no plugin uses is reported in the log (it can be a typo or a plugin that could not be loaded).
- `PluginContext.publish(libraryId, data)` sends `{"type":"PLUGIN","plugin":"id","library":"libraryId","data":...}` to the clients that watch the library. The web page displays `data.message` in its log and `data.progress` (`{"done":1,"total":2}`) as a progress bar titled `data.label`.

### Discovery of plugins and providers

The `SyncPluginFactory` implementations (and the `FileProviderFactory` ones, see sync4j-config) are discovered with [plugin-loader](https://github.com/fathzer/plugin-loader):

- on the classpath of the application,
- and in the jar files of a directory, if one is given (`SyncServer.Builder.pluginsDirectory(dir)` and `ProviderRegistry.withDefaults(dir)`). It allows to add a plugin or a provider to an application without rebuilding it.

As for `java.util.ServiceLoader`, an implementation is declared in the `META-INF/services/<full name of the interface>` file of its jar (one class name per line) and needs a public constructor without argument.

An implementation that can't be loaded (missing class, wrong type, unreadable jar, ...) is logged and ignored: it does not prevent the other ones to be discovered. The classes of a jar of the directory can use the classes of the application.

If the application is packaged in a single jar (for instance with the Maven shade plugin), the `META-INF/services` files of its dependencies have to be merged (`ServicesResourceTransformer`).

### Authentication and the secret

When the security is enabled, a user has to log in to use the application: the API of the libraries (`/api/libraries/**`, to read as well as to change something) and the WebSocket refuse the users that are not logged in (HTTP 401, the WebSocket is not even opened). Only the web page and its files, and the login API (`/api/login`), can be used without being logged in, because the page has to be displayed to show the login form. The page loads its data after the login, and shows the login form again if the session expires. The routes added by a plugin are protected by the plugin (see `PluginContext.loginManager()`).

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
| WS | `/ws/sync/{libraryId}` | `STATUS`, `PROGRESS` and `PLUGIN` messages |
| POST/GET/DELETE | `/api/login` | Log in (`{"user", "password"}`), check the login (`{"user"}` or 401), log out. The only routes of the API that don't need a login |

All the routes above the login one need the user to be logged in if the security is enabled (otherwise, they answer 401).

To customize the web page, pass a directory to `SyncServer.Builder.staticFiles`: its files replace the default ones.

## Known limitations

- The tests of the web page need a browser, see below: they are skipped if it is not available.

## Running the tests

`mvn test` runs all the tests. The tests of the web page (`WebPageBrowserTest`) use a real browser: they start a server, drive a headless Chrome with the Chrome DevTools Protocol, and check what the page does (the login, the data that is loaded, the WebSocket, a synchronization, the expiry of the session, ...).

- They are skipped if Chrome can't be found. It is searched in the `PATH` (`google-chrome`, `chromium`, ...). Use the `sync4j.chrome` system property (or the `CHROME_BIN` environment variable) to give its path: `mvn test -Dsync4j.chrome=/opt/chrome/chrome`.
- Use `-Dsync4j.browser.tests=false` to skip them.
- The browser is started with `--no-sandbox`, so it can run in a container.

