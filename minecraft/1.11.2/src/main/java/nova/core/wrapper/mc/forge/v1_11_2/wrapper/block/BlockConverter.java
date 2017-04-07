/*
 * Copyright (c) 2015 NOVA, All rights reserved.
 * This library is free software, licensed under GNU Lesser General Public License version 3
 *
 * This file is part of NOVA.
 *
 * NOVA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NOVA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NOVA.  If not, see <http://www.gnu.org/licenses/>.
 */

package nova.core.wrapper.mc.forge.v1_11_2.wrapper.block;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import nova.core.block.Block;
import nova.core.block.BlockFactory;
import nova.core.block.BlockManager;
import nova.core.component.Category;
import nova.core.event.BlockEvent;
import nova.core.loader.Mod;
import nova.core.nativewrapper.NativeConverter;
import nova.core.wrapper.mc.forge.v1_11_2.launcher.ForgeLoadable;
import nova.core.wrapper.mc.forge.v1_11_2.launcher.NovaMinecraft;
import nova.core.wrapper.mc.forge.v1_11_2.util.ModCreativeTab;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.block.backward.BWBlock;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.block.backward.BWBlockFactory;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.block.forward.FWBlock;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.item.forward.FWItemBlock;
import nova.internal.core.Game;
import nova.internal.core.launch.NovaLauncher;

import java.util.Arrays;
import java.util.Optional;

/**
 * @author Calclavia
 */
//TODO: Should be <BlockFactory, Block>
public class BlockConverter implements NativeConverter<BlockFactory, net.minecraft.block.Block>, ForgeLoadable {
	/**
	 * A map of all blockFactory to MC blocks registered
	 */
	public final BiMap<BlockFactory, net.minecraft.block.Block> blockFactoryMap = HashBiMap.create();

	public static BlockConverter instance() {
		return Game.natives().getNative(BlockFactory.class, net.minecraft.block.Block.class);
	}

	@Override
	public Class<BlockFactory> getNovaSide() {
		return BlockFactory.class;
	}

	@Override
	public Class<net.minecraft.block.Block> getNativeSide() {
		return net.minecraft.block.Block.class;
	}

	@Override
	public BlockFactory toNova(net.minecraft.block.Block nativeBlock) {
		//Prevent recursive wrapping
		if (nativeBlock instanceof FWBlock) {
			return ((FWBlock) nativeBlock).getFactory();
		}

		if (nativeBlock == Blocks.AIR) {
			return Game.blocks().getAirBlock();
		}

		return blockFactoryMap.inverse().get(nativeBlock);
	}

	public net.minecraft.block.Block toNative(Block novaBlock) {
		//Prevent recursive wrapping
		if (novaBlock instanceof BWBlock) {
			return ((BWBlock) novaBlock).block();
		}

		return toNative(novaBlock.getFactory());
	}

	@Override
	public net.minecraft.block.Block toNative(BlockFactory blockFactory) {
		return blockFactoryMap.get(blockFactory);
	}

	/**
	 * Register all Nova blocks
	 *
	 * @param evt The Minecraft Forge pre-initialization event
	 */
	@Override
	public void preInit(FMLPreInitializationEvent evt) {
		registerMinecraftToNOVA();
		registerNOVAToMinecraft();
	}

	private void registerMinecraftToNOVA() {
		//TODO: Will this register ALL Forge mod blocks as well?
		BlockManager blockManager = Game.blocks();
		net.minecraft.block.Block.REGISTRY.forEach(block -> blockManager.register(new BWBlockFactory(block)));
	}

	private void registerNOVAToMinecraft() {
		BlockManager blockManager = Game.blocks();

		//Register air block
		BlockFactory airBlock = new BlockFactory("air", () -> new BWBlock(Blocks.AIR) {
			@Override
			public boolean canReplace() {
				return true;
			}
		}, evt -> {
		});

		blockManager.register(airBlock);

		//NOTE: There should NEVER be blocks already registered in preInit() stage of a NativeConverter.
		Game.events().on(BlockEvent.Register.class).bind(evt -> registerNovaBlock(evt.blockFactory));
	}

	private void registerNovaBlock(BlockFactory blockFactory) {
		FWBlock blockWrapper = new FWBlock(blockFactory);
		FWItemBlock itemBlockWrapper = new FWItemBlock(blockWrapper);
		blockFactoryMap.put(blockFactory, blockWrapper);
		String blockId = blockFactory.getID();
		if (!blockId.contains(":"))
			blockId = NovaLauncher.instance().flatMap(NovaLauncher::getCurrentMod).map(Mod::id).orElse("nova") + ':' + blockId;
		ResourceLocation id = new ResourceLocation(blockId);
		GameRegistry.register(blockWrapper, id);
		GameRegistry.register(itemBlockWrapper, id);
		NovaMinecraft.proxy.postRegisterBlock(blockWrapper);

		if (blockWrapper.dummy.components.has(Category.class) && FMLCommonHandler.instance().getSide().isClient()) {
			//Add into creative tab
			Category category = blockWrapper.dummy.components.get(Category.class);
			Optional<CreativeTabs> first = Arrays.stream(CreativeTabs.CREATIVE_TAB_ARRAY)
				.filter(tab -> tab.getTabLabel().equals(category.name))
				.findFirst();
			if (first.isPresent()) {
				blockWrapper.setCreativeTab(first.get());
			} else {
				Optional<nova.core.item.Item> item = category.item;
				ModCreativeTab tab = new ModCreativeTab(category.name, item.isPresent() ? Game.natives().toNative(item.get()) : Item.getItemFromBlock(blockWrapper));
				blockWrapper.setCreativeTab(tab);
			}
		}

		System.out.println("[NOVA]: Registered '" + blockFactory.getID() + "' block.");
	}
}