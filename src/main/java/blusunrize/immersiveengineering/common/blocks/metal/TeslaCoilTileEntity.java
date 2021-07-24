/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.metal;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.IEEnums.IOSideConfig;
import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.client.IModelOffsetProvider;
import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.api.tool.IElectricEquipment;
import blusunrize.immersiveengineering.api.tool.IElectricEquipment.ElectricSource;
import blusunrize.immersiveengineering.api.tool.ITeslaEntity;
import blusunrize.immersiveengineering.common.IETileTypes;
import blusunrize.immersiveengineering.common.blocks.IEBaseTileEntity;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.*;
import blusunrize.immersiveengineering.common.config.IEServerConfig;
import blusunrize.immersiveengineering.common.network.MessageTileSync;
import blusunrize.immersiveengineering.common.temp.IETickableBlockEntity;
import blusunrize.immersiveengineering.common.util.*;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.IEDamageSources.ElectricDamageSource;
import blusunrize.immersiveengineering.mixin.accessors.EntityAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.fmllegacy.network.PacketDistributor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class TeslaCoilTileEntity extends IEBaseTileEntity implements IETickableBlockEntity, IIEInternalFluxHandler, IHasDummyBlocks,
		IStateBasedDirectional, IBlockBounds, IScrewdriverInteraction, IModelOffsetProvider
{
	public FluxStorage energyStorage = new FluxStorage(48000);
	public boolean redstoneControlInverted = false;
	public boolean lowPower = false;
	public final List<LightningAnimation> effectMap = new ArrayList<>();
	private static final ElectricSource TC_FIELD = new ElectricSource(-1);

	public TeslaCoilTileEntity(BlockPos pos, BlockState state)
	{
		super(IETileTypes.TESLACOIL.get(), pos, state);
	}

	@Override
	public void tickClient()
	{
		IETickableBlockEntity.super.tickClient();
		effectMap.removeIf(LightningAnimation::tick);
	}

	@Override
	public void tick()
	{
		checkForNeedlessTicking();
		IETickableBlockEntity.super.tick();
	}

	@Override
	public void tickServer()
	{
		int timeKey = getBlockPos().getX()^getBlockPos().getZ();
		int energyDrain = IEServerConfig.MACHINES.teslacoil_consumption.get();
		if(lowPower)
			energyDrain /= 2;
		if(level.getGameTime()%32==(timeKey&31)&&canRun(energyDrain))
		{
			this.energyStorage.extractEnergy(energyDrain, false);

			double radius = 6;
			if(lowPower)
				radius /= 2;
			AABB aabbSmall = new AABB(getBlockPos().getX()+.5-radius, getBlockPos().getY()+.5-radius, getBlockPos().getZ()+.5-radius, getBlockPos().getX()+.5+radius, getBlockPos().getY()+.5+radius, getBlockPos().getZ()+.5+radius);
			AABB aabb = aabbSmall.inflate(radius/2);
			List<Entity> targetsAll = level.getEntitiesOfClass(Entity.class, aabb);
			List<Entity> targets = targetsAll.stream().filter((e) -> (e instanceof LivingEntity&&aabbSmall.intersects(e.getBoundingBox()))).collect(Collectors.toList());
			LivingEntity target = null;
			if(!targets.isEmpty())
			{
				ElectricDamageSource dmgsrc = IEDamageSources.causeTeslaDamage(IEServerConfig.MACHINES.teslacoil_damage.get().floatValue(), lowPower);
				int randomTarget = Utils.RAND.nextInt(targets.size());
				target = (LivingEntity)targets.get(randomTarget);
				if(target!=null)
				{
					if(!level.isClientSide)
					{
						energyDrain = IEServerConfig.MACHINES.teslacoil_consumption_active.get();
						if(lowPower)
							energyDrain /= 2;
						if(energyStorage.extractEnergy(energyDrain, true)==energyDrain)
						{
							energyStorage.extractEnergy(energyDrain, false);
							target.addEffect(new MobEffectInstance(IEPotions.stunned.get(), 128));
							if(dmgsrc.apply(target))
							{
								EntityAccess targetAccessor = (EntityAccess)target;
								int prevFire = targetAccessor.getRemainingFireTicks();
								targetAccessor.setRemainingFireTicks(1);
								targetAccessor.setRemainingFireTicks(prevFire);
							}
							this.sendRenderPacket(target);
						}
					}
				}
			}
			for(Entity e : targetsAll)
				if(e!=target)
				{
					if(e instanceof ITeslaEntity)
						((ITeslaEntity)e).onHit(this, lowPower);
					else if(e instanceof LivingEntity)
						IElectricEquipment.applyToEntity((LivingEntity)e, null, TC_FIELD);
				}
			if(targets.isEmpty()&&level.getGameTime()%128==(timeKey&127))
			{
				//target up to 4 blocks away
				double tV = (Utils.RAND.nextDouble()-.5)*8;
				double tH = (Utils.RAND.nextDouble()-.5)*8;
				if(lowPower)
				{
					tV /= 2;
					tH /= 2;
				}
				//Minimal distance to the coil is 2 blocks
				tV += tV < 0?-2: 2;
				tH += tH < 0?-2: 2;

				BlockPos targetBlock = getBlockPos().offset(getFacing().getAxis()==Axis.X?0: tH, getFacing().getAxis()==Axis.Y?0: tV, getFacing().getAxis()==Axis.Y?tV: getFacing().getAxis()==Axis.X?tH: 0);
				double tL = 0;
				boolean targetFound = false;
				if(!level.isEmptyBlock(targetBlock))
				{
					BlockState state = level.getBlockState(targetBlock);
					VoxelShape shape = state.getShape(level, targetBlock);
					if(!shape.isEmpty())
					{
						AABB blockBounds = shape.bounds();
						if(getFacing()==Direction.UP)
							tL = targetBlock.getY()-getBlockPos().getY()+blockBounds.maxY;
						else if(getFacing()==Direction.DOWN)
							tL = targetBlock.getY()-getBlockPos().getY()+blockBounds.minY;
						else if(getFacing()==Direction.NORTH)
							tL = targetBlock.getZ()-getBlockPos().getZ()+blockBounds.minZ;
						else if(getFacing()==Direction.SOUTH)
							tL = targetBlock.getZ()-getBlockPos().getZ()+blockBounds.maxZ;
						else if(getFacing()==Direction.WEST)
							tL = targetBlock.getX()-getBlockPos().getX()+blockBounds.minX;
						else
							tL = targetBlock.getX()-getBlockPos().getX()+blockBounds.maxX;
						targetFound = true;
					}
				}
				if(!targetFound)
				{
					boolean positiveFirst = Utils.RAND.nextBoolean();
					for(int i = 0; i < 2; i++)
					{
						for(int ll = 0; ll <= 6; ll++)
						{
							BlockPos targetBlock2 = targetBlock.relative(positiveFirst?getFacing(): getFacing().getOpposite(), ll);
							if(!level.isEmptyBlock(targetBlock2))
							{
								BlockState state = level.getBlockState(targetBlock2);
								VoxelShape shape = state.getShape(level, targetBlock2);
								if(shape.isEmpty())
									continue;
								AABB blockBounds = shape.bounds();
								tL = getFacing().getAxis()==Axis.Y?(targetBlock2.getY()-getBlockPos().getY()): getFacing().getAxis()==Axis.Z?(targetBlock2.getZ()-getBlockPos().getZ()): (targetBlock2.getZ()-getBlockPos().getZ());
								Direction tempF = positiveFirst?getFacing(): getFacing().getOpposite();
								if(tempF==Direction.UP)
									tL += blockBounds.maxY;
								else if(tempF==Direction.DOWN)
									tL += blockBounds.minY;
								else if(tempF==Direction.NORTH)
									tL += blockBounds.minZ;
								else if(tempF==Direction.SOUTH)
									tL += blockBounds.maxZ;
								else if(tempF==Direction.WEST)
									tL += blockBounds.minX;
								else
									tL += blockBounds.maxX;
								targetFound = true;
								break;
							}
						}
						if(targetFound)
							break;
						positiveFirst = !positiveFirst;
					}
				}
				if(targetFound)
					sendFreePacket(tL, tH, tV);
			}
			this.setChanged();
		}
	}

	protected void sendRenderPacket(Entity target)
	{
		CompoundTag tag = new CompoundTag();
		tag.putInt("targetEntity", target.getId());
		ImmersiveEngineering.packetHandler.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(worldPosition)), new MessageTileSync(this, tag));
	}

	protected void sendFreePacket(double tL, double tH, double tV)
	{
		CompoundTag tag = new CompoundTag();
		tag.putDouble("tL", tL);
		tag.putDouble("tV", tV);
		tag.putDouble("tH", tH);
		ImmersiveEngineering.packetHandler.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(worldPosition)), new MessageTileSync(this, tag));
	}

	@Override
	public void receiveMessageFromServer(CompoundTag message)
	{
		if(message.contains("targetEntity", NBT.TAG_INT))
		{
			Entity target = level.getEntity(message.getInt("targetEntity"));
			if(target instanceof LivingEntity)
			{
				double dx = target.getX()-getBlockPos().getX();
				double dy = target.getY()-getBlockPos().getY();
				double dz = target.getZ()-getBlockPos().getZ();

				Direction f;
				if(getFacing().getAxis()==Axis.Y)
				{
					if(Math.abs(dz) > Math.abs(dx))
						f = dz < 0?Direction.NORTH: Direction.SOUTH;
					else
						f = dx < 0?Direction.WEST: Direction.EAST;
				}
				else if(getFacing().getAxis()==Axis.Z)
				{
					if(Math.abs(dy) > Math.abs(dx))
						f = dy < 0?Direction.DOWN: Direction.UP;
					else
						f = dx < 0?Direction.WEST: Direction.EAST;
				}
				else
				{
					if(Math.abs(dy) > Math.abs(dz))
						f = dy < 0?Direction.DOWN: Direction.UP;
					else
						f = dz < 0?Direction.NORTH: Direction.SOUTH;
				}
				double verticalOffset = 1+Utils.RAND.nextDouble()*.25;
				Vec3 coilPos = Vec3.atCenterOf(getBlockPos());
				//Vertical offset
				coilPos = coilPos.add(getFacing().getStepX()*verticalOffset, getFacing().getStepY()*verticalOffset, getFacing().getStepZ()*verticalOffset);
				//offset to direction
				if(f!=null)
				{
					coilPos = coilPos.add(f.getStepX()*.375, f.getStepY()*.375, f.getStepZ()*.375);
					//random side offset
					f = DirectionUtils.rotateAround(f, getFacing().getAxis());
					double dShift = (Utils.RAND.nextDouble()-.5)*.75;
					coilPos = coilPos.add(f.getStepX()*dShift, f.getStepY()*dShift, f.getStepZ()*dShift);
				}

				addAnimation(new LightningAnimation(coilPos, (LivingEntity)target));
				level.playLocalSound(coilPos.x, coilPos.y, coilPos.z, IESounds.tesla, SoundSource.BLOCKS, 2.5F, 0.5F+Utils.RAND.nextFloat(), true);
			}
		}
		else if(message.contains("tL", NBT.TAG_DOUBLE))
			initFreeStreamer(message.getDouble("tL"), message.getDouble("tV"), message.getDouble("tH"));
	}

	public void initFreeStreamer(double tL, double tV, double tH)
	{
		double tx = getFacing().getAxis()==Axis.X?tL: tH;
		double ty = getFacing().getAxis()==Axis.Y?tL: tV;
		double tz = getFacing().getAxis()==Axis.Y?tV: getFacing().getAxis()==Axis.X?tH: tL;

		Direction f = null;
		if(getFacing().getAxis()==Axis.Y)
		{
			if(Math.abs(tz) > Math.abs(tx))
				f = tz < 0?Direction.NORTH: Direction.SOUTH;
			else
				f = tx < 0?Direction.WEST: Direction.EAST;
		}
		else if(getFacing().getAxis()==Axis.Z)
		{
			if(Math.abs(ty) > Math.abs(tx))
				f = ty < 0?Direction.DOWN: Direction.UP;
			else
				f = tx < 0?Direction.WEST: Direction.EAST;
		}
		else
		{
			if(Math.abs(ty) > Math.abs(tz))
				f = ty < 0?Direction.DOWN: Direction.UP;
			else
				f = tz < 0?Direction.NORTH: Direction.SOUTH;
		}

		double verticalOffset = 1+Utils.RAND.nextDouble()*.25;
		Vec3 coilPos = Vec3.atCenterOf(getBlockPos());
		//Vertical offset
		coilPos = coilPos.add(getFacing().getStepX()*verticalOffset, getFacing().getStepY()*verticalOffset, getFacing().getStepZ()*verticalOffset);
		//offset to direction
		coilPos = coilPos.add(f.getStepX()*.375, f.getStepY()*.375, f.getStepZ()*.375);
		//random side offset
		f = DirectionUtils.rotateAround(f, getFacing().getAxis());
		double dShift = (Utils.RAND.nextDouble()-.5)*.75;
		coilPos = coilPos.add(f.getStepX()*dShift, f.getStepY()*dShift, f.getStepZ()*dShift);
		addAnimation(new LightningAnimation(coilPos, Vec3.atLowerCornerOf(getBlockPos()).add(tx, ty, tz)));
//		world.playSound(null, getPos(), IESounds.tesla, SoundCategory.BLOCKS,2.5f, .5f + Utils.RAND.nextFloat());
		level.playLocalSound(getBlockPos().getX(), getBlockPos().getY(), getBlockPos().getZ(), IESounds.tesla, SoundSource.BLOCKS, 2.5F, 0.5F+Utils.RAND.nextFloat(), true);
	}

	private void addAnimation(LightningAnimation ani)
	{
		Minecraft.getInstance().submitAsync(() -> effectMap.add(ani));
	}

	@Override
	public void readCustomNBT(CompoundTag nbt, boolean descPacket)
	{
		redstoneControlInverted = nbt.getBoolean("redstoneInverted");
		lowPower = nbt.getBoolean("lowPower");
		energyStorage.readFromNBT(nbt);
	}

	@Override
	public void writeCustomNBT(CompoundTag nbt, boolean descPacket)
	{
		nbt.putBoolean("redstoneInverted", redstoneControlInverted);
		nbt.putBoolean("lowPower", lowPower);
		energyStorage.writeToNBT(nbt);
	}

	@Override
	public VoxelShape getBlockBounds(@Nullable CollisionContext ctx)
	{
		if(!isDummy())
			return Shapes.block();
		switch(getFacing())
		{
			case DOWN:
				return Shapes.box(.125f, .125f, .125f, .875f, 1, .875f);
			case UP:
				return Shapes.box(.125f, 0, .125f, .875f, .875f, .875f);
			case NORTH:
				return Shapes.box(.125f, .125f, .125f, .875f, .875f, 1);
			case SOUTH:
				return Shapes.box(.125f, .125f, 0, .875f, .875f, .875f);
			case WEST:
				return Shapes.box(.125f, .125f, .125f, 1, .875f, .875f);
			case EAST:
				return Shapes.box(0, .125f, .125f, .875f, .875f, .875f);
		}
		return Shapes.block();
	}

	AABB renderBB;

	@Override
	@OnlyIn(Dist.CLIENT)
	public AABB getRenderBoundingBox()
	{
		if(renderBB==null)
			renderBB = new AABB(getBlockPos().offset(-8, -8, -8), getBlockPos().offset(8, 8, 8));
		return renderBB;
	}

	@Override
	public InteractionResult screwdriverUseSide(Direction side, Player player, InteractionHand hand, Vec3 hitVec)
	{
		if(isDummy())
		{
			BlockEntity te = level.getBlockEntity(getBlockPos().relative(getFacing(), -1));
			if(te instanceof TeslaCoilTileEntity)
				return ((TeslaCoilTileEntity)te).screwdriverUseSide(side, player, hand, hitVec);
			return InteractionResult.PASS;
		}
		if(!level.isClientSide)
		{
			if(player.isShiftKeyDown())
			{
				int energyDrain = IEServerConfig.MACHINES.teslacoil_consumption.get();
				if(lowPower)
					energyDrain /= 2;
				if(canRun(energyDrain))
					player.hurt(IEDamageSources.causeTeslaPrimaryDamage(), Float.MAX_VALUE);
				else
				{
					lowPower = !lowPower;
					ChatUtils.sendServerNoSpamMessages(player, new TranslatableComponent(Lib.CHAT_INFO+"tesla."+(lowPower?"lowPower": "highPower")));
					setChanged();
				}
			}
			else
			{
				redstoneControlInverted = !redstoneControlInverted;
				ChatUtils.sendServerNoSpamMessages(player, new TranslatableComponent(Lib.CHAT_INFO+"rsControl."+(redstoneControlInverted?"invertedOn": "invertedOff")));
				setChanged();
				this.markContainingBlockForUpdate(null);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public Property<Direction> getFacingProperty()
	{
		return IEProperties.FACING_ALL;
	}

	@Override
	public PlacementLimitation getFacingLimitation()
	{
		return PlacementLimitation.SIDE_CLICKED;
	}

	@Override
	public boolean mirrorFacingOnPlacement(LivingEntity placer)
	{
		return false;
	}

	@Override
	public boolean canHammerRotate(Direction side, Vec3 hit, LivingEntity entity)
	{
		return false;
	}

	@Override
	public boolean canRotate(Direction axis)
	{
		return false;
	}

	@Nullable
	@Override
	public IGeneralMultiblock master()
	{
		if(!isDummy())
			return this;
		BlockPos masterPos = getBlockPos().below();
		BlockEntity te = Utils.getExistingTileEntity(level, masterPos);
		return this.getClass().isInstance(te)?(IGeneralMultiblock)te: null;
	}

	@Override
	public void placeDummies(BlockPlaceContext ctx, BlockState state)
	{
		level.setBlockAndUpdate(worldPosition.relative(getFacing()), state.setValue(IEProperties.MULTIBLOCKSLAVE, true));
		((TeslaCoilTileEntity)level.getBlockEntity(worldPosition.relative(getFacing()))).setFacing(getFacing());
	}

	@Override
	public void breakDummies(BlockPos pos, BlockState state)
	{
		boolean dummy = isDummy();
		for(int i = 0; i <= 1; i++)
			if(level.getBlockEntity(getBlockPos().relative(getFacing(), dummy?-1: 0).relative(getFacing(), i)) instanceof TeslaCoilTileEntity)
				level.removeBlock(getBlockPos().relative(getFacing(), dummy?-1: 0).relative(getFacing(), i), false);
	}

	@Nonnull
	@Override
	public FluxStorage getFluxStorage()
	{
		if(isDummy())
		{
			BlockEntity te = level.getBlockEntity(getBlockPos().relative(getFacing(), -1));
			if(te instanceof TeslaCoilTileEntity)
				return ((TeslaCoilTileEntity)te).getFluxStorage();
		}
		return energyStorage;
	}

	@Nonnull
	@Override
	public IOSideConfig getEnergySideConfig(Direction facing)
	{
		return !isDummy()?IOSideConfig.INPUT: IOSideConfig.NONE;
	}

	IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	@Override
	public IEForgeEnergyWrapper getCapabilityWrapper(Direction facing)
	{
		if(!isDummy())
			return wrappers[facing==null?0: facing.ordinal()];
		return null;
	}

	public boolean canRun(int energyDrain)
	{
		return (isRSPowered()^redstoneControlInverted)&&energyStorage.getEnergyStored() >= energyDrain;
	}

	public static class LightningAnimation
	{
		public Vec3 startPos;
		public LivingEntity targetEntity;
		public Vec3 targetPos;
		private int lifeTimer = 20;
		private final int ANIMATION_MAX = 4;
		private int animationTimer = ANIMATION_MAX;

		public List<Vec3> subPoints = new ArrayList<>();
		private Vec3 prevTarget;

		public LightningAnimation(Vec3 startPos, LivingEntity targetEntity)
		{
			this.startPos = startPos;
			this.targetEntity = targetEntity;
		}

		public LightningAnimation(Vec3 startPos, Vec3 targetPos)
		{
			this.startPos = startPos;
			this.targetPos = targetPos;
		}

		public boolean shoudlRecalculateLightning()
		{
			if(subPoints.isEmpty()||animationTimer==0)
				return true;
			boolean b = false;
			Vec3 end = targetEntity!=null?targetEntity.position(): targetPos;
			if(prevTarget!=null)
				b = prevTarget.distanceTo(end) > 1;
			prevTarget = end;
			return b;
		}

		public void createLightning(Random rand)
		{
			subPoints.clear();
			Vec3 end = targetEntity!=null?targetEntity.position(): targetPos;
			Vec3 dist = end.subtract(startPos);
			double points = 12;
			for(int i = 0; i < points; i++)
			{
				Vec3 sub = startPos.add(dist.x/points*i, dist.y/points*i, dist.z/points*i);
				//distance to the middle point and by that, distance from the start and end. -1 is start, 1 is end
				double fixPointDist = (i-points/2)/(points/2);
				//Randomization modifier, closer to start/end means smaller divergence
				double mod = 1-.75*Math.abs(fixPointDist);
				double offX = (rand.nextDouble()-.5)*mod;
				double offY = (rand.nextDouble()-.5)*mod;
				double offZ = (rand.nextDouble()-.5)*mod;
				if(fixPointDist < 0)
				{
					offY += .75*mod*(.75+fixPointDist);//Closer to the coil should arc upwards
					offX = (sub.x-startPos.x) < 0?-Math.abs(offX): Math.abs(offX);
					offZ = (sub.z-startPos.z) < 0?-Math.abs(offZ): Math.abs(offZ);
				}
				else
				{
					offY = Math.min(end.y+1*(1-fixPointDist)*-Math.signum(dist.y), offY);//final points should be higher/lower than end, depending on if lightning goes up or down
					offX = Math.abs(offX)*(end.x-sub.x);
					offZ = Math.abs(offZ)*(end.z-sub.z);
				}
				subPoints.add(sub.add(offX, offY, offZ));
			}
			animationTimer = ANIMATION_MAX+Utils.RAND.nextInt(5)-2;
		}

		public boolean tick()
		{
			animationTimer--;
			lifeTimer--;
			return lifeTimer <= 0;
		}
	}

	@Override
	public BlockPos getModelOffset(BlockState state, @Nullable Vec3i size)
	{
		if(isDummy())
			return new BlockPos(0, 0, -1);
		else
			return BlockPos.ZERO;
	}
}
