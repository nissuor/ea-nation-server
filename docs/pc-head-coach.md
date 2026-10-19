# PC NFL Head Coach: experimental multiplayer

The `PC_COACH06` configuration is disabled by default. Enable it explicitly for
testing, with the existing development profile or your own database configuration.
It uses TCP port 31320 and the normal configured buddy service. The tested client
connected using plain Aries; this is not a claim of stock-client compatibility.

## Server fixes

- Preserve the socket identifier during authentication. Session lookup and cleanup
  use this exact key; rewriting loopback to a LAN address loses the match.
- Parse protocol lines without treating ISO-8859-1 byte `0x85` in `USERPARAMS`
  as a newline. Preserve embedded equals signs and distinguish empty from absent
  values.
- Apply all fields of a Coach `gset` before broadcasting its game snapshot. A
  packet can contain player flags/settings and game flags/settings together.
- Mark only the receiving Coach player's own `+usr` record with `F=U`. Marking
  opponents as self overwrites the client's local identity, preventing both
  players from agreeing on Ready. Use the subject player's address in `A`/`LA`.
- Avoid announcing the placeholder roster download to PC Coach. The tested
  clients explicitly retained their installed roster through a separate patch.
- Load the built-in career statbook through
  `VIEW0=Career,"Local Career",:statbook/careerStatbook`. The third CSV field is a
  style/movie path, not a numeric flag. An empty or invalid path leaves the parent
  screen waiting for a child that never enables its navigation.
- Answer `sviw` and `sdta` with a positive field count, complete field metadata,
  and the requested `SLOT`. The page states that local records are not tracked and
  original EA history is unavailable. It does not invent career statistics.
- Read HTTP content resources through Spring's resource stream API so the
  development content endpoints also work from a packaged JAR.

## Validation

Build and run the server regression tests with JDK 21:

```sh
mvn package -pl gameserver -am
```

Manual testing used two Windows clients on one PC with distinct sample accounts.
They logged in, entered the lobby, exchanged a direct invitation, agreed on Ready,
advanced through Game Setup, and reached field/gameplay. The tester reported
working gameplay after adding client background-execution fixes. My Career and
Opponent's Career loaded and returned successfully after the server statbook fix.

The custom test clients also included endpoint/plain-Aries changes, a local-account
record fix, installed-roster retention, separate instance/save identifiers,
complementary gameplay UDP ports 3658/3659, disabled voice, and background/focus
handling changes. These executable patches and game assets are not included here.
This PR addresses the server side of that test setup; it does not provide a
complete client installation procedure.

A completed full match, separate-PC/LAN or Internet play, NAT traversal, voice,
and other games have not been verified with these changes. Persistent Coach career
totals, leaderboards, and roster hosting remain unimplemented.
