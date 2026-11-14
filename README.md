# IfSoul Easy NPC bridge

Bridge for Minecraft Java 1.21.1, NeoForge 21.1.235,
JDK 21 and [Easy NPC 6.0.2](https://modrinth.com/mod/easy-npc/version/elDSzpA0). Native private dialogue and the Desktop pairing handshake were verified on the listed NeoForge/Easy NPC versions. The operator selects one
player and one NPC. A right-click followed by `/ifsoul-easynpc <text>`
sends a private line. The host mod retains all NPC mechanics.

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
| Game control | NPC life, movement, pathfinding, combat and game permissions stay with the host. Group dialogue, autonomous actions and public chat are outside this bridge's scope. |

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
