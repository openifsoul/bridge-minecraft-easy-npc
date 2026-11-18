# IfSoul Easy NPC bridge

Bridge for Minecraft Java 1.21.1, NeoForge 21.1.235,
JDK 21 and [Easy NPC 6.0.2](https://modrinth.com/mod/easy-npc/version/elDSzpA0).
The operator selects one player and one NPC. Right-click the selected NPC
and use `/ifsoul-easynpc <text>` to send a private line.

A compatible NeoForge client and the unchanged bridge JAR were used to
verify pairing, private dialogue delivered in game and recorded in App,
native approach, cancellation and stop, grant revocation, and pairing
again after restart without replay. The test used an isolated offline
player and a deterministic provider.

## Optional movement

Enable `game-actions=true` and issue a new App movement grant for the selected
player and NPC. The player must own the NPC. The NPC must have a configured
Easy NPC travel objective such as Random Stroll, and ground navigation must
be idle at the first interaction for movement to appear as active in App.
Re-pair after changing the NPC setup.

The approach uses one temporary MOVE goal and the host's pathfinding. Stock
safety, combat and return-home goals retain precedence. The bridge reports
arrival, interruption or expiry and removes its goal. Use
`/ifsoul-easynpc stop` or `/ifsoul-easynpc wait` to cancel a pending or running approach.

During movement, the bridge normally re-checks the App grant once per
second. The service round trip adds to that interval. A short movement can
finish before the next check, and App rejects outcomes sent with a revoked
grant.

## Build

Use the official verified NeoForge 21.1.235 installer to prepare an isolated
server directory, without starting the game. Set `JAVA_HOME` to JDK 21,
`IFSOUL_NEOFORGE_ROOT` to that directory and `IFSOUL_NEOFORGE_UPSTREAM` to
a directory containing the separately downloaded upstream mod saved as
`easy-npc.jar`. Run `./check.sh` to verify inputs, compile the bridge and
test the device dialogue client. The artifact is `build/ifsoul-easy-npc.jar`.
The output contains original bridge code. Upstream jars and assets remain
separately installed.

## Configure

With the required upstream mods separately installed, write
`config/ifsoul-easynpc.properties` in the server directory:

```properties
service-origin=http://127.0.0.1:8788
player-uuid=<selected authenticated player UUID>
npc-id=<selected NPC entity UUID>
pairing-code=<fresh one-use IfSoul device code>
```

Protect the config directory. Use HTTPS for a remote service. The bearer
credential is held in process memory and expires with the service grant.
Renew the code after restart or grant expiration.

## Capabilities and scope

| Operation | Behavior |
| --- | --- |
| Bind | One service grant for the exact server-authoritative player and Easy NPC NPC. |
| Interact | Right-click the selected NPC, then use `/ifsoul-easynpc <text>` in the same world within eight blocks. The bridge checks the target again before replying. |
| Reply | Bounded private text to the approved player. Disconnect, world change and server shutdown invalidate pending delivery. |
| Game control | Easy NPC owns movement, pathfinding and combat. The opt-in action is one bounded approach to the selected player’s location. |

In IfSoul **Games → Configure Minecraft**, choose the matching server software
and bridge. Select the character, memory and provider and enter the player and
NPC identity before issuing a code. A Desktop user can choose **Install from
local JAR** and a stopped server directory for a matching pinned IfSoul JAR;
operators can also copy the JAR to the server's plugin or mod directory. Install
upstream dependencies separately. Enter the five-minute code in the bridge
configuration and start the server. Refresh status in Games to confirm the
handshake. Use **Revoke** to stop the service grant. If pairing fails, recheck the
exact profile, both IDs, service reachability and the unused code. For a native
server on another machine, use the service's reachable HTTPS origin.

The server bridge uses the App device API directly. SDK integrators can use
the [host integration guide](https://github.com/openifsoul/sdk/blob/main/docs/mineflayer-bridge.md)
for bounded host adapters. The Java bridge does not require the JavaScript SDK
at runtime.

## App capability discovery

The bridge reports one bounded approach using the configured NPC’s owned native ground navigation as `minecraft.come` when native game actions are enabled.
Open the paired connection in App and allow the individual capability before
the NPC can execute it. The player must still use the existing private NPC
interaction and satisfy the game host's native conditions. Discovery and an
App grant leave native game permissions in force. Offline or ineligible NPCs
appear unavailable. A completed action is recorded from the host result.

This bridge provides no resource collection, inventory or chest transfers,
crafting, cooking, combat commands or building actions.

App controls listening, reacting and speaking independently for the bridge's
private channel. The selected Minecraft player remains the only reply
recipient.


### Shared dialogue channels

Set `group-player-uuids` in the bridge properties to a comma-separated
allowlist of player UUIDs to expose group dialogue. The approved player is
always included. Set `public-dialogue=true` to expose world dialogue. Each
channel also requires its own App permissions. Select the configured NPC
using its existing interaction, then use `/ifsoul-easynpc-group <text>` or
`/ifsoul-easynpc-public <text>`. The command is available to the bound player.

Group output is sent to online allowlisted players in the same world. Public
output is sent to current players in that world. Shared turns produce dialogue
only. The host checks App delivery authorization before sending and keeps the
destination on the requested channel.

For an isolated native check, connect the approved player, an allowlisted
listener and an unlisted observer in the same world. Select the NPC and enable
the group controls in App. Check that only the first two clients receive the
group reply. Turn on group Listen alone and check the App transcript for input
without an NPC reply. Revoke Speak during a pending turn and check that the
reply is withheld. Enable the public host switch and App permissions, then
send a public turn while a fourth client is in another dimension. Check same-world
delivery and confirm that a private-only test phrase is absent from shared replies.
