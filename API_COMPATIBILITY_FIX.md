# CommandPanels shared API loading fix

## Root cause

`PanelService` exists at the exact imported package in `PlugModCore-API`. CommandPanels uses the correct `provided` API dependency; the core shades that API into its plugin JAR. The module loader resolves `org.wannes.plugModCore.api.*` exclusively through the core parent classloader, preserving one authoritative shared API definition.

The deployed `D:/wanne/Desktop/DEV server/plugins/plugmodcore-1.0-SNAPSHOT-shaded.jar` was inspected directly: it contains neither `PanelService.class` nor `ServerTransferService.class`. The repository's rebuilt API and core contain both. This deployed core predates CommandPanels' shared contracts, despite using the same `1.0-SNAPSHOT` version label. Replacing only CommandPanels cannot fix that runtime mismatch. Adding `PlugModCore` as a module dependency would also be incorrect: it is the host plugin, not a separately loaded module.

## Changes

- `module.info.yml` declares CommandPanels' required shared API classes.
- `ModuleInfo` and `ModuleManager` read and validate these requirements through the core classloader before module initialization. Missing shared API linkage errors also get a targeted diagnostic naming the module, class, API version and runtime core location, with full-restart instructions. Failed state and cleanup/recovery remain intact.
- Two core regression tests cover pre-initialization failure, retained failed state, authoritative PanelService identity and rejection of an old parent lacking that class.
- `tools/package-commandpanels.py` checks all API class bytes against the core, rejects duplicate API classes in CommandPanels, and creates a matched deployment ZIP with SHA-256 checksums and instructions.
- The opt-in Paper harness can additionally enable existing modules; its test-only status reader tolerates Windows' brief lock during atomic snapshot replacement.

No panel schemas, saved data, gameplay features or unrelated module implementations were changed. Both core and CommandPanels must be rebuilt and deployed together.

## Safe deployment and recovery

Use `artifacts/commandpanels-compatible.zip` after stopping Paper fully. Back up current JARs and configuration, replace the core plugin and module with the supplied pair, and keep exactly one PlugModCore plugin JAR in `plugins`. Remove obsolete differently named core copies from that directory. Do not install the standalone API artifact as a Bukkit plugin. Preserve all configuration and module-data directories.

Start Paper normally. Core API classes cannot be replaced by module reload or Bukkit `/reload`. If needed, use `/module list` and `/module enable CommandPanels`. A failed module with retained resources must be unloaded before loading/enabling again; a full restart with the corrected artifacts also resets the failed runtime. Never repeatedly retry a missing-class failure against the old core.

Production/dev server files were inspected read-only; testing uses an isolated temporary Paper server. The repaired pair is supplied for deployment rather than silently replacing that server's files.

## Verification completed

- Full reactor `clean verify` succeeded; a subsequent full `verify` also passed after strengthening the duplicate-API regression fixture. Core: 33 tests passed. CommandPanels: 13 tests passed. Existing module suites passed; two existing opt-in PlaceholderAPI tests remain skipped.
- Six CommandPanels JavaScript tests passed.
- API artifact and shaded core contain all required contracts; every shared API class matches byte-for-byte. CommandPanels bundles no copy of the shared API. The deployment ZIP matches the final built JARs.
- Final isolated Paper 1.21.11 run: 54 checks passed with an actual connected protocol client. These cover initialization, native command/inventory actions, item protection, permissions, optional placeholders, save/import/export, three module reload cycles, disable/re-enable and full restart persistence.
- The actual WebUI created and saved a new Compass panel, then reopened it successfully. That browser-created configuration was tested on Paper after restart and its configured message reached the connected player.
- Existing PlaceholderAPI, ScoreBoard, PluginManager, CustomCommands and TemplateModule all reached ENABLED on the repaired core; this verifies loading, not every feature of those modules.
- Temporary Paper listeners, protocol clients, browser gateway and browser tab were closed after verification.

Evidence: `commandpanels-compat-build.log`, `commandpanels-compat-js.log`, `commandpanels-compat-paper.log`, `commandpanels-compat-final-paper.log`, and `Modules/CommandPanels/target/compatibility-editor.png`. Final Paper fixture: `C:/Users/wanne/AppData/Local/Temp/commandpanels-paper-1m88o7nm`.

No human graphical Minecraft-client test or live proxy transfer was performed. Those existing integration limitations remain unchanged.
