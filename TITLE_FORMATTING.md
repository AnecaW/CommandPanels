# Inventory title formatting

The title field now mounts the existing shared Minecraft text editor in single-line mode, with its compatible colour/style toolbar and a live formatted preview. No alignment or multiline controls are added. The title editor has its own lifecycle handle, so rebuilding item/lore editors does not dispose it or change its value.

The existing Paper 1.21.11 renderer already passes an Adventure Component to `Bukkit.createInventory`; it continues using the existing legacy/hex formatting decoder and player-context placeholder handling. No backend renderer, persistence schema, panel workflow, permissions, action logic, core, ScoreBoard or nametag implementation was changed. The existing one-line/128-character serialized-title validation remains enforced by the backend. Old title strings are not normalized into configuration merely by opening the editor; only an actual title edit changes the stored value.

Production changes are confined to `src/main/resources/web/app.js` and title-specific styling in `web/style.css`. Tests were extended in `src/test/js/editor.test.cjs` and the opt-in Paper probe/harness to inspect native title components and received inventory-title packets.

Verification completed:

- Full Maven reactor `verify` passed, including 13 CommandPanels and 33 core tests; existing module suites also passed. Two existing opt-in PlaceholderAPI tests remained skipped.
- 12 JavaScript tests passed, including single-line shared-editor mounting, untouched legacy-title preservation, title preview updates and independence from item-editor disposal.
- Actual browser testing covered creation, gold/bold title styling, Enter suppression, save, refresh, reopening the existing panel, and saving an item-only change. Its stored title stayed byte-for-byte identical during that item edit. The toolbar offers no alignment/multiline controls.
- 52 checks passed on isolated real Paper 1.21.11 with a connected protocol client. The browser-created title resolved to `Servers PanelTest`; both the native Adventure component and the client's inventory-open packet retained gold colour and bold styling. The title/configuration survived a full restart. Existing panel opening, saving, item actions and lifecycle checks also passed.

The received title packet contained `color: gold`, `bold: 1`, `italic: 0`, and `text: Servers PanelTest`. This was server/protocol-client verification; no human graphical Minecraft client test is claimed.

Evidence: `commandpanels-title-build.log`, `commandpanels-title-js.log`, `commandpanels-title-paper.log`, and `target/title-editor-verified.png`. Isolated Paper fixture: `C:/Users/wanne/AppData/Local/Temp/commandpanels-paper-3xn0tglc`. Temporary server, client, gateway and browser tab were stopped after testing.

Deploy the updated `Modules/CommandPanels/target/CommandPanels.jar`, reload CommandPanels through PlugModCore and refresh the WebUI. Preserve all existing data. This enhancement requires no additional core source changes.
