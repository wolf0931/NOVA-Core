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

package nova.core.wrapper.mc.forge.v1_11_2.wrapper.item;

import com.google.common.collect.HashBiMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import nova.core.block.BlockFactory;
import nova.core.component.Category;
import nova.core.event.ItemEvent;
import nova.core.item.Item;
import nova.core.item.ItemBlock;
import nova.core.item.ItemFactory;
import nova.core.nativewrapper.NativeConverter;
import nova.core.retention.Data;
import nova.core.wrapper.mc.forge.v1_11_2.launcher.ForgeLoadable;
import nova.core.wrapper.mc.forge.v1_11_2.launcher.NovaMinecraft;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.CategoryConverter;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.block.BlockConverter;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.item.backward.BWItem;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.item.backward.BWItemFactory;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.item.forward.FWItem;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.item.forward.FWItemCapabilityProvider;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.item.forward.FWNBTTagCompound;
import nova.internal.core.Game;
import nova.internal.core.launch.InitializationException;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Set;

/**
 * The main class responsible for wrapping items.
 * @author Calclavia, Stan Hebben
 */
public class ItemConverter implements NativeConverter<Item, ItemStack>, ForgeLoadable {

	/**
	 * A map of all items registered
	 */
	private final HashBiMap<ItemFactory, MinecraftItemMapping> map = HashBiMap.create();

	public static ItemConverter instance() {
		return Game.natives().getNative(Item.class, ItemStack.class);
	}

	@Override
	public Class<Item> getNovaSide() {
		return Item.class;
	}

	@Override
	public Class<ItemStack> getNativeSide() {
		return ItemStack.class;
	}

	@Override
	public Item toNova(ItemStack stack) {
		return getNovaItem(stack).setCount(stack.getCount());
	}

	public Item getNovaItem(ItemStack stack) {
		if (stack.getItemDamage() == net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE) {
			// TODO: Deal withPriority wildcard meta values - important for the ore dictionary
			return getNovaItem(new ItemStack(stack.getItem(), 1, 0));
		}

		ICapabilityProvider stackCapabilities = getStackCapabilities(stack);
		if (stack.getTagCompound() instanceof FWNBTTagCompound) {
			return ((FWNBTTagCompound) stack.getTagCompound()).getItem();
		} else if (stackCapabilities instanceof FWItemCapabilityProvider) {
			return ((FWItemCapabilityProvider) stackCapabilities).item;
		} else {
			ItemFactory itemFactory = registerMinecraftMapping(stack.getItem(), stack.getItemDamage());

			Data data = stack.getTagCompound() != null ? Game.natives().toNova(stack.getTagCompound()) : new Data();
			if (!stack.getHasSubtypes() && stack.getItemDamage() > 0) {
				data.put("damage", stack.getItemDamage());
			}

			return itemFactory.build(data);
		}
	}

	@Override
	public ItemStack toNative(Item item) {
		if (item == null) {
			return ItemStack.EMPTY;
		}

		//Prevent recusive wrapping
		if (item instanceof BWItem) {
			return ((BWItem) item).makeItemStack(item.count());
		} else {
			ItemFactory itemFactory = Game.items().get(item.getID()).get();// TODO?
			FWNBTTagCompound tag = new FWNBTTagCompound(item);

			MinecraftItemMapping mapping = get(itemFactory);
			if (mapping == null) {
				throw new InitializationException("Missing mapping for " + itemFactory.getID());
			}

			ItemStack result = new ItemStack(mapping.item, item.count(), mapping.meta);
			result.setTagCompound(tag);
			return result;
		}
	}

	public ItemStack toNative(ItemFactory itemFactory) {
		FWNBTTagCompound tag = new FWNBTTagCompound(itemFactory.build());

		MinecraftItemMapping mapping = get(itemFactory);
		if (mapping == null) {
			throw new InitializationException("Missing mapping for " + itemFactory.getID());
		}

		ItemStack result = new ItemStack(mapping.item, 1, mapping.meta);
		result.setTagCompound(tag);
		return result;
	}

	public ItemStack toNative(String id) {
		return toNative(Game.items().get(id).get().build().setCount(1));
	}

	public MinecraftItemMapping get(ItemFactory item) {
		return map.get(item);
	}

	public ItemFactory get(MinecraftItemMapping minecraftItem) {
		return map.inverse().get(minecraftItem);
	}

	/**
	 * Saves NOVA item into a Minecraft ItemStack.
	 *
	 * @param itemStack Minecraft ItemStack.
	 * @param item NOVA Item.
	 * @return The modified ItemStack.
	 */
	public ItemStack updateMCItemStack(ItemStack itemStack, Item item) {
		itemStack.setCount(item.count());
		if (itemStack.getCount() <= 0) {
			return ItemStack.EMPTY;
		}

		itemStack.setTagCompound(Game.natives().toNative(item.getFactory().save(item)));
		return itemStack;
	}

	/**
	 * Register all Nova blocks
	 */
	@Override
	public void preInit(FMLPreInitializationEvent evt) {
		registerNOVAItemsToMinecraft();
		registerMinecraftItemsToNOVA();
		registerSubtypeResolution();
	}

	private void registerNOVAItemsToMinecraft() {
		//There should be no items registered during Native Converter preInit()
		//	item.registry.forEach(this::registerNOVAItem);
		Game.events().on(ItemEvent.Register.class).bind(this::onItemRegistered);
	}

	private void onItemRegistered(ItemEvent.Register event) {
		registerNOVAItem(event.itemFactory);
	}

	private void registerNOVAItem(ItemFactory itemFactory) {
		if (map.containsKey(itemFactory)) {
			// just a safeguard - don't map stuff twice
			return;
		}

		net.minecraft.item.Item itemWrapper;

		Item dummy = itemFactory.build();
		if (dummy instanceof ItemBlock) {
			BlockFactory blockFactory = ((ItemBlock) dummy).blockFactory;
			net.minecraft.block.Block mcBlock = BlockConverter.instance().toNative(blockFactory);
			itemWrapper = net.minecraft.item.Item.getItemFromBlock(mcBlock);
			if (itemWrapper == null) {
				throw new InitializationException("ItemConverter: Missing block: " + itemFactory.getID());
			}
			if (!itemFactory.getID().equals(Objects.toString(net.minecraft.item.Item.REGISTRY.getNameForObject(itemWrapper)))) {
				System.err.println("[NOVA]: ItemConverter: " + net.minecraft.item.Item.REGISTRY.getNameForObject(itemWrapper) + " != " + itemFactory.getID());
				net.minecraft.item.Item newItemWrapper = net.minecraft.item.Item.getByNameOrId(itemFactory.getID());
				itemWrapper = newItemWrapper != null ? newItemWrapper : itemWrapper;
			}
		} else {
			itemWrapper = new FWItem(itemFactory);
		}

		MinecraftItemMapping minecraftItemMapping = new MinecraftItemMapping(itemWrapper, 0);
		map.forcePut(itemFactory, minecraftItemMapping);

		// Don't register ItemBlocks twice
		if (!(dummy instanceof ItemBlock)) {
			NovaMinecraft.proxy.registerItem((FWItem) itemWrapper);
			String itemId = itemFactory.getID(); // TODO?
			GameRegistry.register(itemWrapper, new ResourceLocation(itemId));

			if (dummy.components.has(Category.class) && FMLCommonHandler.instance().getSide().isClient()) {
				//Add into creative tab
				Category category = dummy.components.get(Category.class);
				itemWrapper.setCreativeTab(CategoryConverter.instance().toNative(category, itemWrapper));
			}

			System.out.println("[NOVA]: Registered '" + itemFactory.getID() + "' item.");
		}
	}

	private void registerMinecraftItemsToNOVA() {
		Set<ResourceLocation> itemIDs = net.minecraft.item.Item.REGISTRY.getKeys();
		itemIDs.forEach(itemID -> {
			net.minecraft.item.Item item = net.minecraft.item.Item.REGISTRY.getObject(itemID);
			registerMinecraftMapping(item, 0);
		});
	}

	private void registerSubtypeResolution() {
		Game.events().on(ItemEvent.IDNotFound.class).bind(this::onIDNotFound);
	}

	private void onIDNotFound(ItemEvent.IDNotFound event) {
		// if item minecraft:planks:2 is detected, this code will register minecraft:planks:2 dynamically
		// we cannot do this up front since there is **NO** reliable way to get the sub-items of an item

		int lastColon = event.id.lastIndexOf(':');
		if (lastColon < 0) {
			return;
		}

		try {
			int meta = Integer.parseInt(event.id.substring(lastColon + 1));
			String itemID = event.id.substring(0, lastColon);

			net.minecraft.item.Item item = net.minecraft.item.Item.REGISTRY.getObject(new ResourceLocation(itemID));
			if (item == null || !item.getHasSubtypes()) {
				return;
			}

			event.setRemappedFactory(registerMinecraftMapping(item, meta));
		} catch (NumberFormatException ex) {
		}
	}

	private ItemFactory registerMinecraftMapping(net.minecraft.item.Item item, int meta) {
		MinecraftItemMapping mapping = new MinecraftItemMapping(item, meta);
		if (map.inverse().containsKey(mapping)) {
			// don't register twice, return the factory instead
			return map.inverse().get(mapping);
		}

		BWItemFactory itemFactory = new BWItemFactory(item, meta);
		map.put(itemFactory, mapping);

		Game.items().register(itemFactory);

		return itemFactory;
	}

	/**
	 * Used to map MC items and their meta to nova item factories.
	 */
	public final class MinecraftItemMapping {
		public final net.minecraft.item.Item item;
		public final int meta;

		public MinecraftItemMapping(net.minecraft.item.Item item, int meta) {
			this.item = item;
			this.meta = item.getHasSubtypes() ? meta : 0;
		}

		public MinecraftItemMapping(ItemStack itemStack) {
			this.item = itemStack.getItem();
			this.meta = itemStack.getHasSubtypes() ? itemStack.getItemDamage() : 0;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}

			MinecraftItemMapping that = (MinecraftItemMapping) o;

			if (meta != that.meta) {
				return false;
			}
			if (!item.equals(that.item)) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			int result = item.hashCode();
			result = 31 * result + meta;
			return result;
		}

		@Override
		public String toString() {
			if (item.getHasSubtypes()) {
				return net.minecraft.item.Item.REGISTRY.getNameForObject(item) + ":" + meta;
			} else {
				return Objects.toString(net.minecraft.item.Item.REGISTRY.getNameForObject(item));
			}
		}
	}

	private ICapabilityProvider getStackCapabilities(ItemStack stack) {
		try {
			Field capabilities = ItemStack.class.getDeclaredField("capabilities");
			capabilities.setAccessible(true);
			@SuppressWarnings("unchecked")
			CapabilityDispatcher dispatcher = (CapabilityDispatcher) capabilities.get(stack);
			capabilities.setAccessible(false);
			if (dispatcher == null)
				return null;

			Field caps = CapabilityDispatcher.class.getDeclaredField("caps");
			caps.setAccessible(true);
			@SuppressWarnings("unchecked")
			ICapabilityProvider[] providers = (ICapabilityProvider[]) caps.get(dispatcher);
			caps.setAccessible(false);

			ICapabilityProvider provider = null;
			if (providers.length > 0)
				provider = providers[0];
			return provider;
		} catch (ReflectiveOperationException | ClassCastException ex) {
			return null;
		}
	}
}
