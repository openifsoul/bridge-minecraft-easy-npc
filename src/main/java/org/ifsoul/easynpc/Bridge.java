package org.ifsoul.easynpc;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;

@Mod("ifsoul_easynpc")
public final class Bridge {
    private final UUID playerId;
    private final String npcId;
    private final DialogueService service;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UUID selected;
    private long until;
    private long revision;
    private boolean inFlight;

    public Bridge() {
        Properties config = new Properties();
        Path path = FMLPaths.CONFIGDIR.get().resolve("ifsoul-easynpc.properties");
        try (var stream = Files.newInputStream(path)) {
            config.load(stream);
            playerId = UUID.fromString(config.getProperty("player-uuid"));
            npcId = config.getProperty("npc-id", "");
            if (npcId.isBlank()) throw new IllegalArgumentException("An exact NPC is required.");
            service = new DialogueService(config.getProperty("service-origin", ""),
                config.getProperty("pairing-code", ""), npcId);
        } catch (IOException | IllegalArgumentException | NullPointerException error) {
            throw new IllegalStateException("Configure ifsoul-easynpc.properties before enabling the bridge.");
        }
        NeoForge.EVENT_BUS.addListener(this::onInteract);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onQuit);
        NeoForge.EVENT_BUS.addListener(this::onTravel);
        NeoForge.EVENT_BUS.addListener(this::onStop);
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
        event.getDispatcher().register(Commands.literal("ifsoul-easynpc")
            .requires(source -> source.getEntity() instanceof ServerPlayer player
                && playerId.equals(player.getUUID()))
            .then(Commands.argument("text", StringArgumentType.greedyString())
                .executes(context -> converse(context.getSource().getPlayerOrException(),
                    StringArgumentType.getString(context, "text")))));
    }

    private int converse(ServerPlayer player, String text) {
        UUID target = selected;
        if (inFlight || target == null || until - System.nanoTime() <= 0
            || !validNpc(player.serverLevel().getEntity(target), player)
            || !DialogueService.safeText(text, 500)) return 0;
        inFlight = true;
        var server = player.serverLevel().getServer();
        String playerName = player.getGameProfile().getName();
        long startedRevision = revision;
        worker.execute(() -> {
            String speech;
            try {
                speech = service.converse(playerId, playerName, text);
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                speech = null;
            }
            String reply = speech;
            server.execute(() -> {
                inFlight = false;
                ServerPlayer recipient = server.getPlayerList().getPlayer(playerId);
                if (startedRevision != revision || recipient == null
                    || !validNpc(recipient.serverLevel().getEntity(target), recipient)) return;
                if (reply == null) recipient.sendSystemMessage(Component.literal("IfSoul dialogue is unavailable."));
                else if (!reply.isEmpty()) recipient.sendSystemMessage(Component.literal(reply));
            });
        });
        return 1;
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
    }

    private void onStop(ServerStoppingEvent event) {
        revision++;
        worker.shutdownNow();
    }
}
