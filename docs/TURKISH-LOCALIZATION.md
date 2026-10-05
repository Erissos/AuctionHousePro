# Turkish presentation and default upgrades

The Turkish catalog uses UTF-8 names and concise listing lore that matches the actual left/right/shift/middle-click actions. Standard item names in commands, listing previews and notifications follow the recipient's selected plugin language. Custom `displayName` and `itemName` values take priority and are inserted as literal text in messages. Listing previews are clones; stored and delivered item metadata is unchanged.

On reload, `lang-legacy/tr-polish.yml` identifies the previous bundled Turkish defaults. Only a string or lore list that still exactly matches that known default is replaced in memory with the corrected bundled value. Customized values are preserved, and the existing language file is not rewritten. Technical material IDs, database values, filters and command identifiers remain unchanged.

Language selection uses `/ah language <code>` and its existing aliases; the listing browser does not contain a language-selection slot.
