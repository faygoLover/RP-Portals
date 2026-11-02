package faygolover.rpportals.gametest;

import faygolover.rpmedicine.core.MedicalSettings;
import faygolover.rpmedicine.core.MedicalState;
import faygolover.rpmedicine.entity.MedCartEntity;
import faygolover.rpmedicine.registry.ModEntities;
import faygolover.rpmedicine.server.CarryService;
import faygolover.rpmedicine.server.Medical;
import faygolover.rpportals.MedicineCompat;
import faygolover.rpportals.PortalFrame;
import faygolover.rpportals.PortalServer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Переход через портал на сервере (02.11.2026): G2 — после переноса шаг клиента не считается «moved too quickly»;
 * Ж3 — везомая каталка с пассажиром идёт с везущим; Ж4 — несомое тело идёт с несущим (и несущий — с телом, если
 * плоскость первым пересекло оно). Порталы здесь — только пара плоскостей: вход смотрит на +Z, выход — в 12 блоках
 * по X лицом на −Z, перенос — сдвиг на +12 по X без поворота. Клиентское (прыжок у видящих) здесь не проверить.
 */
@GameTestHolder("rpportals")
@PrefixGameTestTemplate(false)
public final class CrossingGameTests {
    private CrossingGameTests() {}

    private static final String T = "platform";
    private static final double SHIFT = 12;
    private static int counter;

    private static PortalFrame in(GameTestHelper h) {
        return new PortalFrame(h.absoluteVec(new Vec3(3.5, 2, 6)), 0f, 3, 3);
    }

    private static PortalFrame out(GameTestHelper h) {
        return new PortalFrame(h.absoluteVec(new Vec3(3.5 + SHIFT, 2, 6)), 180f, 3, 3);
    }

    /** Игрок с настоящим соединением на EmbeddedChannel: пакеты к нему копятся в канале. */
    private static ServerPlayer player(GameTestHelper h, double x, double z) {
        var server = h.getLevel().getServer();
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "rpp-test-" + (counter++));
        ServerPlayer p = new ServerPlayer(server, h.getLevel(), profile);
        Connection conn = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(conn);
        server.getPlayerList().placeNewPlayer(conn, p);
        p.setGameMode(GameType.SURVIVAL);
        Vec3 pos = h.absoluteVec(new Vec3(x, 2, z));
        p.moveTo(pos.x, pos.y, pos.z, 0, 0);
        return p;
    }

    private static void remove(GameTestHelper h, ServerPlayer... ps) {
        for (ServerPlayer p : ps) {
            MedicalState s = Medical.state(p);
            if (s != null) s.reset(MedicalSettings.get());
            if (!p.isRemoved()) h.getLevel().getServer().getPlayerList().remove(p);
        }
    }

    /** Последний ClientboundPlayerPositionPacket к игроку (остальное из канала выбрасывается). */
    private static ClientboundPlayerPositionPacket lastPositionPacket(ServerPlayer p) {
        EmbeddedChannel ch = (EmbeddedChannel) p.connection.connection.channel();
        ClientboundPlayerPositionPacket last = null;
        for (Object o; (o = ch.readOutbound()) != null; ) if (o instanceof ClientboundPlayerPositionPacket pp) last = pp;
        return last;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.05;
    }

    /** G2: переход, подтверждение и шаг клиента в одном окне между тиками — шаг принят, отката нет. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void predictedCrossingAcceptsNextStep(GameTestHelper h) {
        PortalFrame f = in(h), t = out(h);
        ServerPlayer p = player(h, 3.5, 5.5);
        // Тик слушателя: точка отсчёта проверки скорости — место у входа (у фальшивого игрока тиков иначе нет,
        // и на нулевом тике сервер сбрасывает её на каждом пакете — гонка не воспроизводилась бы).
        p.connection.tick();
        lastPositionPacket(p);
        Vec3 at = p.position();
        PortalServer.cross(p, h.getLevel(), f, t, at, 0f, 0f, true, 10);
        h.assertTrue(near(p.getX(), at.x + SHIFT), "сервер поставил игрока у выхода, x=" + (p.getX() - at.x));
        ClientboundPlayerPositionPacket pos = lastPositionPacket(p);
        h.assertTrue(pos != null && near(pos.getX(), 0) && near(pos.getZ(), 0), "клиенту — нулевой относительный сдвиг");
        p.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(pos.getId()));
        double nx = p.getX() + 0.2;
        p.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(nx, p.getY(), p.getZ(), true));
        h.assertTrue(near(p.getX(), nx), "шаг после перехода принят (без «moved too quickly»), x−ждали=" + (p.getX() - nx));
        h.assertTrue(lastPositionPacket(p) == null, "сервер не вернул игрока назад");
        remove(h, p);
        h.succeed();
    }

    /** Ж3: кресло с сидящим идёт через портал с везущим; везёт дальше, сидящий в кресле. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void pushedWheelchairCrossesWithRider(GameTestHelper h) {
        crossCart(h, ModEntities.WHEELCHAIR.get(), true);
    }

    /** Ж3: пустая каталка идёт с везущим и сама в портал не уходит. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void pushedEmptyStretcherCrosses(GameTestHelper h) {
        crossCart(h, ModEntities.STRETCHER.get(), false);
    }

    private static void crossCart(GameTestHelper h, EntityType<MedCartEntity> type, boolean withRider) {
        PortalFrame f = in(h), t = out(h);
        MedCartEntity c = type.create(h.getLevel());
        Vec3 cv = h.absoluteVec(new Vec3(3.5, 2, 3.5));
        c.moveTo(cv.x, cv.y, cv.z, 0, 0);
        h.getLevel().addFreshEntity(c);
        ServerPlayer rider = withRider ? player(h, 3.5, 4.8) : null;
        ServerPlayer pusher = player(h, 3.5, 2.2);
        if (rider != null) {
            c.interact(rider, InteractionHand.MAIN_HAND);
            h.assertTrue(rider.getVehicle() == c, "сел в кресло");
        }
        c.interact(pusher, InteractionHand.MAIN_HAND);
        h.assertTrue(c.pusher() == pusher, "взялся за ручки");
        h.assertTrue(MedicineCompat.attached(c), "везомая каталка сама порталом не переносится");
        double cx = c.getX(), rx = rider != null ? rider.getX() : 0;
        PortalServer.cross(pusher, h.getLevel(), f, t, pusher.position(), 0f, 0f, true, 10);
        h.assertTrue(near(c.getX(), cx + SHIFT), "каталка у выхода, dx=" + (c.getX() - cx));
        if (rider != null) {
            h.assertTrue(rider.getVehicle() == c, "сидящий в кресле");
            h.assertTrue(near(rider.getX(), rx + SHIFT), "сидящий у выхода вместе с креслом");
        }
        c.tick();
        h.assertTrue(c.pusher() == pusher && MedCartEntity.pushedBy(pusher) == c, "везёт дальше");
        c.release();
        if (rider != null) MedCartEntity.unseat(rider);
        c.discard();
        if (rider != null) remove(h, rider);
        remove(h, pusher);
        h.succeed();
    }

    /** Ж4: тело, которое тащат, идёт с несущим; а если плоскость первым пересекло тело — несущий идёт с ним. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void draggedBodyCrossesWithCarrier(GameTestHelper h) {
        PortalFrame f = in(h), t = out(h);
        ServerPlayer medic = player(h, 3.5, 4.0);
        ServerPlayer patient = player(h, 3.5, 2.8);
        MedicalState s = Medical.state(patient);
        s.down = MedicalState.Down.FAINT;
        s.painShock = true;
        h.assertTrue(CarryService.pickUp(medic, patient), "взял волоком");
        h.assertTrue(MedicineCompat.attached(patient), "несомое тело само порталом не переносится");

        double bx = patient.getX();
        PortalServer.cross(medic, h.getLevel(), f, t, medic.position(), 0f, 0f, true, 10);
        h.assertTrue(near(patient.getX(), bx + SHIFT), "тело у выхода, dx=" + (patient.getX() - bx));
        CarryService.tickCarrier(medic);
        h.assertTrue(CarryService.carried(medic) == patient, "несёт дальше");

        // Обратно, первым плоскость пересекло тело (его клиент): несущий идёт следом.
        double mx = medic.getX();
        PortalServer.cross(patient, h.getLevel(), t, f, patient.position(), 0f, 0f, true, 10);
        h.assertTrue(near(medic.getX(), mx - SHIFT), "несущий у входа вместе с телом, dx=" + (medic.getX() - mx));
        CarryService.tickCarrier(medic);
        h.assertTrue(CarryService.carried(medic) == patient, "несёт дальше");
        CarryService.dropCarried(medic);
        remove(h, medic, patient);
        h.succeed();
    }
}
