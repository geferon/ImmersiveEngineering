/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks;

import blusunrize.immersiveengineering.common.IETileTypes;
import blusunrize.immersiveengineering.common.blocks.FakeLightBlock.FakeLightTileEntity;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.ISpawnInterdiction;
import blusunrize.immersiveengineering.common.blocks.generic.GenericTileBlock;
import blusunrize.immersiveengineering.common.blocks.metal.FloodlightTileEntity;
import blusunrize.immersiveengineering.common.temp.IETickableBlockEntity;
import blusunrize.immersiveengineering.common.util.SpawnInterdictionHandler;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.function.Supplier;

public class FakeLightBlock extends GenericTileBlock<FakeLightTileEntity>
{
	public static final Supplier<Properties> PROPERTIES = () -> Properties.of(Material.AIR)
			.noOcclusion()
			.lightLevel(b -> 15);

	public FakeLightBlock(Properties props)
	{
		super(IETileTypes.FAKE_LIGHT, props);
	}

	@Override
	public boolean isAir(BlockState state)
	{
		return true;
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context)
	{
		return Shapes.empty();
	}

	@Override
	public PushReaction getPistonPushReaction(BlockState state)
	{
		return PushReaction.DESTROY;
	}

	@Override
	public boolean isPathfindable(BlockState state, BlockGetter worldIn, BlockPos pos, PathComputationType type)
	{
		return true;
	}

	public static class FakeLightTileEntity extends IEBaseTileEntity implements IETickableBlockEntity, ISpawnInterdiction
	{
		public BlockPos floodlightCoords = null;

		public FakeLightTileEntity(BlockPos pos, BlockState state)
		{
			super(IETileTypes.FAKE_LIGHT.get(), pos, state);
		}

		@Override
		public void tickServer()
		{
			if(floodlightCoords==null)
			{
				level.removeBlock(getBlockPos(), false);
				return;
			}
			if(level.getGameTime()%256==((getBlockPos().getX()^getBlockPos().getZ())&255))
			{
				BlockEntity tile = Utils.getExistingTileEntity(level, floodlightCoords);
				if(!(tile instanceof FloodlightTileEntity)||!((FloodlightTileEntity)tile).getIsActive())
					level.removeBlock(getBlockPos(), false);
			}

		}

		@Override
		public double getInterdictionRangeSquared()
		{
			return 1024;
		}

		@Override
		public void setRemoved()
		{
			SpawnInterdictionHandler.removeFromInterdictionTiles(this);
			super.setRemoved();
		}

		@Override
		public void onChunkUnloaded()
		{
			SpawnInterdictionHandler.removeFromInterdictionTiles(this);
			super.onChunkUnloaded();
		}

		@Override
		public void onLoad()
		{
			super.onLoad();
			SpawnInterdictionHandler.addInterdictionTile(this);
		}

		@Override
		public void readCustomNBT(CompoundTag nbt, boolean descPacket)
		{
			if(nbt.contains("floodlightCoords", NBT.TAG_COMPOUND))
				floodlightCoords = NbtUtils.readBlockPos(nbt.getCompound("floodlightCoords"));
			else
				floodlightCoords = null;
		}

		@Override
		public void writeCustomNBT(CompoundTag nbt, boolean descPacket)
		{
			if(floodlightCoords!=null)
				nbt.put("floodlightCoords", NbtUtils.writeBlockPos(floodlightCoords));
		}
	}
}