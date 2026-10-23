package faygolover.rpportals;

import faygolover.rpcore.gm.GmBlock;
import faygolover.rpcore.gm.GmSettings;
import faygolover.rpcore.gm.GmTools;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Блок-якорь портала: сам невидим и проходим, плоскость рисует клиент по настройкам блок-сущности. Ведущий с меткой
 * видит контур якоря и плоскости, ломает якорь, ПКМ меткой — настройки. Ставится лицом к поставившему.
 */
public class PortalBlock extends Block implements EntityBlock, GmBlock {
    private static final VoxelShape ANCHOR = Block.box(4, 0, 4, 12, 8, 12);

    public PortalBlock(Properties props) {
        super(props);
    }

    @Override
    public int markerColor() {
        return 0xFF60D0;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return GmTools.markerContext(ctx) ? ANCHOR : Shapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0f;
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return true;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (placer != null && level.getBlockEntity(pos) instanceof PortalBlockEntity be) {
            // Лицом к поставившему, по сторонам света (точнее — в настройках).
            be.yaw = Mth.wrapDegrees(Math.round((placer.getYRot() + 180f) / 90f) * 90f);
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!GmTools.holdsMarker(player)) return InteractionResult.PASS;
        if (!level.isClientSide && player instanceof ServerPlayer sp) GmSettings.open(sp, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PortalBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != PortalRegistry.PORTAL_BE.get() ? null
                : (BlockEntityTicker<T>) (BlockEntityTicker<PortalBlockEntity>) PortalBlockEntity::serverTick;
    }
}
