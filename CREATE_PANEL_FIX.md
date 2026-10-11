# Create Panel HTTP compatibility fix

The existing button was correctly connected to `create()`, but that function called `crypto.randomUUID()` before rendering the editor. That method is restricted to secure browser contexts. Ordinary HTTP access through a server hostname/IP lacks it, throwing `TypeError: crypto.randomUUID is not a function`. The synchronous click handler did not report that error. Localhost testing had missed this because browsers treat loopback origins as trustworthy.

The existing editor now generates a version-4 draft UUID with `crypto.getRandomValues`, which is available on HTTP, and initializes the same form. No duplicate editor or backend-only mock was introduced. The backend continues generating the authoritative stable UUID when it saves a new panel, validating commands and permissions, atomically persisting the snapshot and registering enabled opening commands.

Initialization errors are logged with operation context and displayed in the WebUI. API requests time out after 15 seconds with instructions to check the list before retrying uncertain saves. Failed saves/list refreshes retain the draft and dirty state; submission controls are unlocked in `finally`. List/editor state changes only after successful backend confirmation and list retrieval.

Files changed: `src/main/resources/web/app.js`, `src/test/js/editor.test.cjs`, and the opt-in `src/test/paper/run-integration.py` browser verification harness. Other module implementations and persisted schemas are unchanged.

Browser API reference: [W3C Crypto interface](https://www.w3.org/TR/WebCryptoAPI/#crypto-interface).

Deployment: replace `plugins/PlugModCore/modules/CommandPanels.jar` with the newly built `Modules/CommandPanels/target/CommandPanels.jar`, reload the module through PlugModCore and refresh the WebUI so it loads the new script. The core compatibility requirements from the preceding fix still apply. `artifacts/commandpanels-compatible.zip` has been refreshed with the audited matching pair. Preserve existing configuration/data.

Verification completed:

- Full reactor Maven `verify`: BUILD SUCCESS; 13 CommandPanels and 33 core tests passed, along with existing module suites. Two pre-existing opt-in PlaceholderAPI tests were skipped.
- 11 JavaScript tests passed, including an HTTP-like runtime without `randomUUID`, UUID format/uniqueness, creation failure reporting, draft preservation after backend/list failures and request abort diagnostics.
- Actual browser on isolated Paper: Create opened the original editor. Empty name, invalid command and duplicate backend command produced useful errors while preserving form data. A valid Compass panel with a message action saved, appeared in the list and remained after page reload/refresh.
- Real Paper 1.21.11 with connected protocol client: 49 checks passed. The exact newly browser-created panel opened via `/createfixed`, executed its message action, and retained its identity/configuration and opened after another full server restart. Existing editing, duplicate, toggle, delete, inventory protection, permissions, lifecycle and reload checks also passed.
- Packaged `web/app.js` matches the tested source. The temporary server, client, gateway and browser tab were stopped; no dev/production server was changed.

Evidence: workspace `commandpanels-create-build.log`, `commandpanels-create-js.log`, `commandpanels-create-paper.log`; screenshot `Modules/CommandPanels/target/create-panel-verified.png`; Paper fixture `C:/Users/wanne/AppData/Local/Temp/commandpanels-paper-dz_o95oc`.

The secure-context restriction was reproduced automatically with an HTTP-like browser API surface; the interactive browser verification used loopback HTTP. No human graphical Minecraft-client or live proxy transfer testing is claimed.
