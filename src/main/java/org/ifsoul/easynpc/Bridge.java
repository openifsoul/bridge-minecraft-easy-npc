package org.ifsoul.easynpc;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import java.util.EnumSet;

@Mod("ifsoul_easynpc")
public final class Bridge {
    private final boolean actionsEnabled;
    private final UUID playerId;
    private final String npcId;
    private final DialogueService service;
    private final Set<UUID> groupMembers;
    private final Set<String> sharedChannels;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService leaseWorker = Executors.newSingleThreadExecutor();
    private UUID selected;
    private long until;
    private long revision;
    private boolean inFlight;
    private ActiveMovement movement;
    private boolean checkingAction;
    private long nextCheck;

    private record ActiveMovement(DialogueService.Decision decision, Entity npc, ServerPlayer player,
                                  GoalSelector selector, ApproachGoal goal, Vec3 destination, long deadline) {}

    private static final class ApproachGoal extends Goal {
        private final PathNavigation navigation;
        private final Vec3 destination;
        private net.minecraft.world.level.pathfinder.Path path;
        private boolean started;
        private boolean stopped;

        ApproachGoal(PathNavigation navigation, Vec3 destination) {
            this.navigation = navigation;
            this.destination = destination;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() {
            return !started && navigation.isDone();
        }

        @Override public void start() {
            started = true;
            if (navigation.moveTo(destination.x, destination.y, destination.z, 1.0)) {
                path = navigation.getPath();
            }
        }

        @Override public boolean canContinueToUse() {
            return path != null && navigation.getPath() == path && !navigation.isDone();
        }

        @Override public void stop() {
            stopped = true;
            if (path != null && navigation.getPath() == path) navigation.stop();
        }
    }

    public Bridge() {
        Properties config = new Properties();
        Path path = FMLPaths.CONFIGDIR.get().resolve("ifsoul-easynpc.properties");
        try (var stream = Files.newInputStream(path)) {
            config.load(stream);
            playerId = UUID.fromString(config.getProperty("player-uuid"));
            actionsEnabled = "true".equals(config.getProperty("game-actions"));
            npcId = config.getProperty("npc-id", "");
            if (npcId.isBlank()) throw new IllegalArgumentException("An exact NPC is required.");
            var members = new java.util.HashSet<UUID>();
            var group = config.getProperty("group-player-uuids", "").strip();
            if (!group.isEmpty()) {
                for (var value : group.split(",", -1)) members.add(UUID.fromString(value.strip()));
                members.add(playerId);
                if (members.size() < 2 || members.size() > 16) throw new IllegalArgumentException();
            }
            groupMembers = Set.copyOf(members);
            var channels = new java.util.HashSet<String>();
            if (!groupMembers.isEmpty()) channels.add("group");
            if ("true".equals(config.getProperty("public-dialogue"))) channels.add("public");
            sharedChannels = Set.copyOf(channels);
            service = new DialogueService(config.getProperty("service-origin", ""),
                config.getProperty("pairing-code", ""), npcId, actionsEnabled ? Set.of("movement") : Set.of(), sharedChannels);
        } catch (IOException | IllegalArgumentException | NullPointerException error) {
            throw new IllegalStateException("Configure ifsoul-easynpc.properties before enabling the bridge.");
        }
        NeoForge.EVENT_BUS.addListener(this::onInteract);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onQuit);
        NeoForge.EVENT_BUS.addListener(this::onTravel);
        NeoForge.EVENT_BUS.addListener(this::onStop);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !playerId.equals(player.getUUID()) || event.getHand() != InteractionHand.MAIN_HAND
            || !validNpc(event.getTarget(), player)) return;
        selected = event.getTarget().getUUID();
        until = System.nanoTime() + 30_000_000_000L;
        player.sendSystemMessage(Component.literal("Use /ifsoul-easynpc <text> to speak privately."));
    }

    private void onCommands(RegisterCommandsEvent event) {
        for (var channel : sharedChannels) {
            event.getDispatcher().register(Commands.literal("ifsoul-easynpc-" + channel)
                .requires(source -> source.getEntity() instanceof ServerPlayer player && playerId.equals(player.getUUID()))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(context -> converseShared(context.getSource().getPlayerOrException(), channel,
                        StringArgumentType.getString(context, "text")))));
        }
        event.getDispatcher().register(Commands.literal("ifsoul-easynpc")
            .requires(source -> source.getEntity() instanceof ServerPlayer player
                && playerId.equals(player.getUUID()))
            .then(Commands.argument("text", StringArgumentType.greedyString())
                .executes(context -> converse(context.getSource().getPlayerOrException(),
                    StringArgumentType.getString(context, "text")))));
    }

    static boolean isCancellation(String text) {
        String command = text.strip();
        return "stop".equalsIgnoreCase(command) || "wait".equalsIgnoreCase(command);
    }

    private String audienceKey(String channel, String world) {
        var definition = channel + ":" + world + ":" + ("group".equals(channel)
            ? groupMembers.stream().map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(",")) : "");
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(definition.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private int converseShared(ServerPlayer player, String channel, String text) {
        var target = selected;
        if (!sharedChannels.contains(channel) || !playerId.equals(player.getUUID()) || inFlight
            || target == null || until - System.nanoTime() <= 0 || !DialogueService.safeText(text, 500)) return 0;
        var level = player.serverLevel();
        var npc = level.getEntity(target);
        if (!validNpc(npc, player)) return 0;
        var server = level.getServer();
        var playerName = player.getGameProfile().getName();
        var world = worldId(player);
        var audience = audienceKey(channel, world);
        var startedRevision = revision;
        boolean actionReady = !choices(npc, player).isEmpty();
        capabilityNpc = target;
        inFlight = true;
        worker.execute(() -> {
            DialogueService.ChannelReply reply = null;
            try {
                reply = service.converseShared(playerId, playerName, text, world, target.toString(),
                    channel, audience, actionReady);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException error) {
                System.getLogger(Bridge.class.getName()).log(System.Logger.Level.WARNING,
                    "IfSoul channel dialogue is unavailable.");
            }
            var result = reply;
            server.execute(() -> {
                inFlight = false;
                var recipient = server.getPlayerList().getPlayer(playerId);
                if (result == null || revision != startedRevision || recipient != player
                    || recipient.serverLevel() != level || level.getEntity(target) != npc
                    || !validNpc(npc, recipient)) return;
                var speech = Component.literal(result.speech());
                for (var listener : level.players()) {
                    if ("public".equals(channel) || groupMembers.contains(listener.getUUID())) {
                        listener.sendSystemMessage(speech);
                    }
                }
            });
        });
        return 1;
    }

    private int converse(ServerPlayer player, String text) {
        if (isCancellation(text)) {
            invalidate(player);
            player.sendSystemMessage(Component.literal("Pending IfSoul movement canceled."));
            return 1;
        }
        UUID target = selected;
        if (inFlight || movement != null || target == null || until - System.nanoTime() <= 0
            || !validNpc(player.serverLevel().getEntity(target), player)
            || !DialogueService.safeText(text, 500)) return 0;
        Entity npc = player.serverLevel().getEntity(target);
        Map<String, String> options = choices(npc, player);
        if (actionsEnabled && options.isEmpty()) {
            player.sendSystemMessage(Component.literal("IfSoul movement needs an idle Easy NPC you own with a ground travel objective."));
        }
        inFlight = true;
        var server = player.serverLevel().getServer();
        String playerName = player.getGameProfile().getName();
        String callbackWorldId = worldId(player);
        capabilityNpc = target;
        long startedRevision = revision;
        worker.execute(() -> {
            DialogueService.Decision decision;
            try {
                decision = service.decide(playerId, playerName, text, options, callbackWorldId, target.toString());
                if (!"none".equals(decision.choice()) && !service.checkActions(decision)) {
                    service.outcome(decision.id(), decision.choice(), "interrupted");
                    decision = null;
                }
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                decision = null;
            }
            var reply = decision;
            server.execute(() -> {
                inFlight = false;
                ServerPlayer recipient = server.getPlayerList().getPlayer(playerId);
                Entity currentNpc = recipient == null ? null : recipient.serverLevel().getEntity(target);
                if (startedRevision != revision || recipient == null || currentNpc != npc
                    || !validNpc(currentNpc, recipient)) {
                    if (reply != null && !"none".equals(reply.choice())) recordOutcome(reply, "interrupted");
                    return;
                }
                if (reply == null) recipient.sendSystemMessage(Component.literal("IfSoul dialogue is unavailable."));
                else {
                    if (!"none".equals(reply.choice())) {
                        if (until - System.nanoTime() <= 0) recordOutcome(reply, "expired");
                        else try {
                            executeAction(reply, currentNpc, recipient);
                        } catch (RuntimeException error) {
                            System.getLogger(Bridge.class.getName()).log(System.Logger.Level.WARNING,
                                "Native Minecraft action failed.");
                            recordOutcome(reply, "failed");
                        }
                    }
                    if ("none".equals(reply.choice()) && !reply.speech().isEmpty()) recipient.sendSystemMessage(Component.literal(reply.speech()));
                }
            });
        });
        return 1;
    }

    private PathNavigation actionNavigation(Entity npc, ServerPlayer player) {
        if (!actionsEnabled || !validNpc(npc, player)) return null;
        var easyNpc = (EasyNPC<?>) npc;
        var owner = easyNpc.getEasyNPCOwnerData();
        var data = easyNpc.getEasyNPCNavigationData();
        var objectives = easyNpc.getEasyNPCObjectiveData();
        return owner != null && owner.isOwner(player) && data != null && objectives != null
            && objectives.hasTravelTargetObjectives() ? data.getGroundPathNavigation() : null;
    }

    private Map<String, String> choices(Entity npc, ServerPlayer player) {
        var navigation = actionNavigation(npc, player);
        return navigation != null && navigation.isDone()
            ? Map.of("come", "Walk near the selected player") : Map.of();
    }

    private void executeAction(DialogueService.Decision decision, Entity npc, ServerPlayer player) {
        var navigation = actionNavigation(npc, player);
        if (!"come".equals(decision.choice()) || navigation == null || !navigation.isDone()) {
            recordOutcome(decision, "interrupted");
            return;
        }
        var destination = player.position();
        if (npc.distanceToSqr(destination) <= 4) {
            recordOutcome(decision, "arrived");
            return;
        }
        var selector = ((EasyNPC<?>) npc).getEntityGoalSelector();
        if (selector == null) {
            recordOutcome(decision, "interrupted");
            return;
        }
        var goal = new ApproachGoal(navigation, destination);
        // Pinned Easy NPC defaults: safety/combat 0–2, return home 3, ordinary stroll 5.
        selector.addGoal(4, goal);
        movement = new ActiveMovement(decision, npc, player, selector, goal, destination,
            System.nanoTime() + 10_000_000_000L);
        nextCheck = System.nanoTime() + 1_000_000_000L;
    }

    private long nextCapabilityHeartbeat;
    private boolean capabilityHeartbeat;
    private UUID capabilityNpc;

    private static String worldId(ServerPlayer player) {
        var name = player.getServer().getWorldData().getLevelName()
            + ":" + player.serverLevel().dimension().location();
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private void tickCapabilities(net.minecraft.server.MinecraftServer server) {
        if (capabilityNpc == null || capabilityHeartbeat || checkingAction
            || System.nanoTime() - nextCapabilityHeartbeat < 0) return;
        var player = server.getPlayerList().getPlayer(playerId);
        var npc = player == null ? null : player.serverLevel().getEntity(capabilityNpc);
        String currentWorld = player == null ? "" : worldId(player);
        var navigation = npc == null ? null : actionNavigation(npc, player);
        boolean available = navigation != null && (navigation.isDone() || movement != null);
        boolean duringTurn = inFlight;
        if (duringTurn && !(player != null && validNpc(npc, player))) return;
        capabilityHeartbeat = true;
        (duringTurn ? leaseWorker : worker).execute(() -> {
            try {
                if (duringTurn) service.keepCapabilityLeaseAlive(currentWorld);
                else service.refreshCapabilities(currentWorld, available);
            } catch (IOException ignored) {
                // Authorization fails closed when the service lease expires.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                server.execute(() -> {
                    capabilityHeartbeat = false;
                    nextCapabilityHeartbeat = System.nanoTime() + 10_000_000_000L;
                });
            }
        });
    }

    private void onTick(ServerTickEvent.Post event) {
        tickCapabilities(event.getServer());
        var active = movement;
        if (active == null) return;
        var player = event.getServer().getPlayerList().getPlayer(playerId);
        if (player != active.player() || actionNavigation(active.npc(), player) != active.goal().navigation) {
            finishMovement("interrupted");
        } else if (active.npc().distanceToSqr(active.destination()) <= 4) {
            finishMovement("arrived");
        } else if (active.goal().stopped) {
            finishMovement("unreachable");
        } else if (System.nanoTime() - active.deadline() >= 0) {
            finishMovement("expired");
        } else if (!checkingAction && System.nanoTime() - nextCheck >= 0) {
            checkingAction = true;
            var server = event.getServer();
            worker.execute(() -> {
                boolean authorized;
                try {
                    authorized = service.checkActions(active.decision());
                } catch (Exception error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    authorized = false;
                }
                boolean permitted = authorized;
                server.execute(() -> {
                    checkingAction = false;
                    nextCheck = System.nanoTime() + 1_000_000_000L;
                    if (!permitted && movement == active) finishMovement("interrupted");
                });
            });
        }
    }

    private void finishMovement(String result) {
        var active = movement;
        if (active == null) return;
        movement = null;
        active.selector().removeGoal(active.goal());
        recordOutcome(active.decision(), result);
        var player = ((net.minecraft.server.level.ServerLevel) active.npc().level()).getServer().getPlayerList().getPlayer(playerId);
        if (player != null) player.sendSystemMessage(Component.literal("IfSoul action: " + result + "."));
    }

    private void recordOutcome(DialogueService.Decision decision, String result) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            try {
                service.outcome(decision.id(), decision.choice(), result);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException error) {
                System.getLogger(Bridge.class.getName()).log(System.Logger.Level.WARNING,
                    "IfSoul action outcome could not be delivered.");
            }
        });
    }

    private boolean validNpc(Entity entity, ServerPlayer player) {
        if (entity == null || !entity.isAlive() || entity.level() != player.level()
            || player.distanceToSqr(entity) > 64) return false;
        return entity instanceof EasyNPC && npcId.equals(entity.getStringUUID());
    }

    private void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        invalidate(event.getEntity());
    }

    private void onTravel(PlayerEvent.PlayerChangedDimensionEvent event) {
        invalidate(event.getEntity());
    }

    private void invalidate(Entity entity) {
        if (!playerId.equals(entity.getUUID())) return;
        revision++;
        selected = null;
        finishMovement("stopped");
    }

    private void onStop(ServerStoppingEvent event) {
        revision++;
        finishMovement("interrupted");
        worker.shutdownNow();
        leaseWorker.shutdownNow();
    }
}
