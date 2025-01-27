/*
 * BluSunrize
 * Copyright (c) 2022
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models.mirror;

import blusunrize.immersiveengineering.api.client.ICacheKeyProvider;
import blusunrize.immersiveengineering.client.models.CompositeBakedModel;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.IModelData;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class CachedMirroredModel<K, T extends ICacheKeyProvider<K>> extends CompositeBakedModel<T> implements ICacheKeyProvider<K>
{
	private final LoadingCache<K, List<BakedQuad>> cache;

	public CachedMirroredModel(T base)
	{
		super(base);
		this.cache = CacheBuilder.newBuilder()
				.expireAfterAccess(120, TimeUnit.SECONDS)
				.build(CacheLoader.from(k -> MirroredModelLoader.reversedQuads(base.getQuads(k))));
	}

	@Override
	public List<BakedQuad> getQuads(K key)
	{
		return cache.getUnchecked(key);
	}

	@Nullable
	@Override
	public K getKey(@Nullable BlockState state, @Nullable Direction side, @Nonnull Random rand, @Nonnull IModelData extraData)
	{
		return base.getKey(state, side, rand, extraData);
	}

	@Nonnull
	@Override
	public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @Nonnull Random rand, @Nonnull IModelData extraData)
	{
		return ICacheKeyProvider.super.getQuads(state, side, rand, extraData);
	}
}
