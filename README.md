# CommandPanels

Independent Java 21 / Paper 1.21.11 PlugModCore module for protected inventory menus. Build from the repository root with `mvn verify`. Install `target/CommandPanels.jar` in PlugModCore's `modules` directory and enable it from the module dashboard. This release requires the accompanying updated PlugModCore core/API: dynamic command handles and optional parent-owned service contracts were added without changing existing command or permission modules.

Open the CommandPanels page from the module dashboard. Create a panel with a name, opening command (without `/`), title and inventory size. Select a slot, add an item, search the server's valid materials, and configure its display name/lore using the shared Minecraft text editor. Copy/paste, move/swap and empty-slot filler operations are available. Shift-click grid slots to select filler targets. Fillers copy appearance with an empty action list. Save Panel explicitly; navigation warns about unsaved changes, failed saves retain the draft, and revision checks reject stale overwrites.

Each enabled panel owns its native opening command. Conflicts with plugins/modules are rejected. Advanced panel access and per-action permissions use native `Player.hasPermission`, so existing permission attachments and permission plugins apply. Console actions require explicit WebUI administrator authorization on save/import and execute under server authority. Player commands use the real player sender and native command checks. The core authenticates WebUI requests; module mutations also require a session capability. Current shared-token authentication identifies a WebUI administrator, not an individual account.

Actions run in configured order for Any, Left, Right, Shift-left or Shift-right clicks: messages, player/console commands, open panel, close inventory, sound, explicit teleport or proxy server transfer. Built-in substitutions are `{player}`, `{uuid}`, `{world}`. The current optional `PlaceholderService` is resolved on each render/action; compatible `%identifier_parameter%` placeholders work when its provider and expansion are active. Unknown display placeholders remain visible as configured. Unresolved command placeholders, controls/newlines and substitutions changing the command name are rejected.

Menus cancel clicks throughout their view and drags touching top slots, including shift transfer, hotbar swaps, drop and collect interactions. Only the four configured click types execute actions. Actions run after the event, with duplicate pending clicks guarded, current permissions/revision checked, and disconnected players ignored. Opening a panel does not automatically execute its actions, so self-navigation cannot trigger an automatic recursive loop. Editing closes this module's open menus safely; new opens use the saved definition. Disabling/reloading releases inventories, dynamic commands, listeners, messaging registrations, services, pending tasks and the storage worker.

## Storage and interchange

All data remains within `plugins/PlugModCore/module-data/CommandPanels`:

- `config/panels.json`: schema version 1, revision, proxy setting, stable panel IDs and full item/action definitions.
- `config/panels.previous.json`: previous committed snapshot.

The existing core serves the website directly from the module JAR; there is no duplicate website deployment.

Writes run on one bounded worker and use a forced temporary file plus atomic replacement. Loading invalid/unsupported data fails with a diagnostic and retains the original file. Import/export uses a versioned JSON download/upload; administrators never need to edit raw JSON. Imports validate types, properties and actions, regenerate panel identity, remap self-navigation, reject duplicate opening commands, and require renewed authorization for console actions. Duplicates get an unused command and start disabled. Delete confirmation removes only that panel from the snapshot.

## Proxy integration

Ordinary menus need no proxy. Enable proxy discovery only on a securely configured BungeeCord-compatible backend. Velocity must enable `bungee-plugin-message-channel`; Paper must have the appropriate proxy forwarding/security configured independently. Backend access must be restricted to trusted proxies. The module uses the public Bukkit BungeeCord plugin-message channel. A connected player's menu open requests `GetServers`; only a recent matching response for that player's connection permits `Connect` for a returned server name. No response produces an actionable error. The player sees **transfer requested**, never an unconfirmed claim of successful arrival. Discovery is cached per player for 30 seconds and repeated when opening a menu after expiry. An online player is necessary for plugin messages; the WebUI reports this. Protocol references: [Paper plugin messaging](https://docs.papermc.io/paper/dev/plugin-messaging/), [Velocity compatibility and security](https://docs.papermc.io/velocity/dev/plugin-messaging/).

Another compatible module/plugin can expose the parent-owned `ServerTransferService` contract for its actual proxy bridge. This is an integration contract, not a simulated proxy. Its accepted request also does not prove player arrival.

## Cross-module use

Declare `commandpanels` in a consuming module's dependencies, then resolve the shared API for each operation on the server thread:

```java
PanelService panels = context.getService(PanelService.class);
if (panels != null) panels.open(player, "servers");
```

Existing custom commands can open a panel by executing its opening command as the player. No custom-command module changes are required. Optional integrations use `context.getOptionalService(...)` with a core-owned API interface and handle provider absence/reload. Required services retain normal explicit dependency checks. Revoked provider handles cannot execute after unload.

## Supported item properties and limits

Actual item materials and stack limits, single-line legacy/hex names and lore, custom model data, item model key, item flags and glint override are supported through the target Paper APIs. The WebUI material symbol is a schematic preview, not a texture rendered from a player's resource pack. Custom models require a client resource pack supplying that model. Teleports require an existing world and an already-loaded destination chunk; the module avoids synchronous chunk loading during clicks. Panels are chest inventories of 9–54 slots; limits are 100 panels, 20 lore lines per item, 16 actions per item and a 2 MB configuration snapshot. Existing open menus are closed on edits, rather than refreshed on a repeating timer.

## Tests

`src/test/java` tests cover typed configuration validation, state revisions/identity, atomic persistence/backups, retained malformed files, action order/click/permission/failure/disconnect handling, proxy message encoding/discovery gates, and legacy/hex formatting. Core tests cover dynamic command cleanup and optional shared-service reloads. `node --test Modules/CommandPanels/src/test/js/*.cjs` covers editor validation, independent copies, unsaved guards, control locking and HTML attribute escaping.

The opt-in isolated Paper harness requires a built reactor, `JAVA_HOME`, a Paper JAR, a generated integration classpath, a Minecraft protocol client and explicit EULA acceptance. It uses a real server, native inventories/services/events and a connected player; it also sends actual inventory click packets. It never touches a production server.

```text
mvn -f Modules/CommandPanels/pom.xml dependency:build-classpath -Dmdep.outputFile=target/integration-classpath.txt
python Modules/CommandPanels/src/test/paper/run-integration.py --accept-eula --paper <Paper jar> --protocol-client <minecraft-protocol package>
```

Proxy packet tests validate the public message protocol using a test double, not a live proxy network. A real BungeeCord/Velocity network and its forwarding/firewall configuration still need end-to-end transfer testing. No human graphical Minecraft-client or custom resource-pack appearance testing is claimed.
