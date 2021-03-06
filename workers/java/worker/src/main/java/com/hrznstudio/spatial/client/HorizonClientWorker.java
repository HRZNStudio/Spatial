package com.hrznstudio.spatial.client;

import com.hrznstudio.spatial.client.vanillawrappers.SpatialNetworkManager;
import com.hrznstudio.spatial.util.ConnectionManager;
import com.hrznstudio.spatial.util.ConnectionStatus;
import com.hrznstudio.spatial.util.EntityBuilder;
import com.hrznstudio.spatial.worker.BaseWorker;
import improbable.Coordinates;
import improbable.Position;
import improbable.WorkerAttributeSet;
import improbable.WorkerRequirementSet;
import improbable.collections.Option;
import improbable.worker.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

public final class HorizonClientWorker extends BaseWorker<ClientView> {
    private EntityId playerId;
    private NetHandlerPlayClient netHandlerPlayClient;
    private NetworkManager networkManager;
    private GuiMainMenu guiMainMenu;

    public HorizonClientWorker() {
        super(ClientView::new);
    }

    public EntityId getPlayerId() {
        return playerId;
    }

    @Override
    public void start() {
        Minecraft mc = Minecraft.getMinecraft();
        //noinspection ConstantConditions
        if (mc == null) throw new IllegalStateException("Client worker should never be started this way");
        guiMainMenu = new GuiMainMenu();

        this.setName(makeName());
        super.start();
    }

    public void stop() {
        ConnectionManager.disconnect();
    }

    public ConnectionStatus getConnectionStatus() {
        return ConnectionManager.getConnectionStatus();
    }

    @Override
    protected void onConnected() {
        Connection connection = ConnectionManager.getConnection();
        Dispatcher dispatcher = getDispatcher();
        final Option<Integer> timeoutMillis = Option.of(500);

        // Reserve an entity ID.
        RequestId<ReserveEntityIdsRequest> entityIdReservationRequestId = connection.sendReserveEntityIdsRequest(1, timeoutMillis);
        // When the reservation succeeds, create an entity with the reserved ID.

        AtomicReference<RequestId<CreateEntityRequest>> createEntityRequestRequestId = new AtomicReference<>();
        dispatcher.onReserveEntityIdsResponse(op -> {
            if (op.requestId.equals(entityIdReservationRequestId) && op.statusCode == StatusCode.SUCCESS) {
                EntityBuilder builder = new EntityBuilder("Player");
                builder.addComponent(Position.COMPONENT, new improbable.PositionData(new Coordinates(5, 200, 5)), // TODO: use position from server
                        new WorkerRequirementSet(Collections.singletonList(new WorkerAttributeSet(Collections.singletonList("workerId:" + this.getName()))))
                );
                createEntityRequestRequestId.set(connection.sendCreateEntityRequest(builder.build(), op.firstEntityId, timeoutMillis));
            }
        });
        dispatcher.onCreateEntityResponse(argument -> {
            if (argument.requestId.equals(createEntityRequestRequestId.get()) && argument.statusCode == StatusCode.SUCCESS) {
                playerId = argument.entityId.get();
            }
        });

        Minecraft mc = Minecraft.getMinecraft();
        networkManager = new SpatialNetworkManager(this);
        netHandlerPlayClient = new NetHandlerPlayClient(mc, guiMainMenu, networkManager, mc.getSession().getProfile());
        FMLClientHandler.instance().setPlayClient(netHandlerPlayClient);
        NetworkDispatcher.allocAndSet(networkManager);

        mc.addScheduledTask(() -> {
            netHandlerPlayClient.handleJoinGame(new SPacketJoinGame(
                    0,
                    GameType.CREATIVE,
                    false,
                    0,
                    EnumDifficulty.NORMAL,
                    9001,
                    WorldType.FLAT,
                    false
            ));
            netHandlerPlayClient.handlePlayerPosLook(new SPacketPlayerPosLook(
                    5, 60, 5, 0, 0, Collections.emptySet(), -1
            )); // TODO: use position from server
        });
    }

    @Override
    protected void onDisConnected(@Nonnull final Ops.Disconnect reason) {
        super.onDisConnected(reason);
        WorldClient wc = Minecraft.getMinecraft().world;
        if (wc != null) wc.sendQuittingDisconnectingPacket();
        // TODO: remove player entity
    }
}
