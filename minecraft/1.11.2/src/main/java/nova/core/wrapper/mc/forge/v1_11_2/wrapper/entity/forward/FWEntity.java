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

package nova.core.wrapper.mc.forge.v1_11_2.wrapper.entity.forward;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import nova.core.block.Stateful;
import nova.core.component.Updater;
import nova.core.component.misc.Collider;
import nova.core.component.transform.EntityTransform;
import nova.core.entity.Entity;
import nova.core.entity.EntityFactory;
import nova.core.retention.Data;
import nova.core.retention.Storable;
import nova.core.util.Direction;
import nova.core.util.EnumSelector;
import nova.core.util.shape.Cuboid;
import nova.core.wrapper.mc.forge.v1_11_2.util.WrapperEvent;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.capability.forward.NovaCapabilityProvider;
import nova.core.wrapper.mc.forge.v1_11_2.wrapper.data.DataConverter;
import nova.internal.core.Game;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity wrapper
 * @author Calclavia
 */
public class FWEntity extends net.minecraft.entity.Entity implements IEntityAdditionalSpawnData, NovaCapabilityProvider {

	private final EnumMap<Direction, Map<Capability<?>, Object>> capabilities = new EnumMap<>(Direction.class); {
		for (Direction facing : Direction.values())
			capabilities.put(facing, new ConcurrentHashMap<>());
	}

	protected final EntityTransform transform;
	protected Entity wrapped;
	boolean firstTick = true;

	public FWEntity(World worldIn) {
		super(worldIn);
		this.transform = new MCEntityTransform(this);
	}

	public FWEntity(World world, EntityFactory factory) {
		this(world);
		setWrapped(factory.build());
		entityInit();
	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) {
		if (wrapped instanceof Storable) {
			((Storable) wrapped).load(Game.natives().toNova(nbt));
		}
		if (wrapped == null) {
			//This entity was saved to disk.
			setWrapped(Game.entities().get(nbt.getString("novaID")).get().build());
		}
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) {
		if (wrapped instanceof Storable) {
			Data data = new Data();
			((Storable) wrapped).save(data);
			DataConverter.instance().toNative(nbt, data);
		}
		nbt.setString("novaID", wrapped.getID());
	}

	@Override
	public void writeSpawnData(ByteBuf buffer) {
		//Write the ID of the entity to client
		String id = wrapped.getID();
		char[] chars = id.toCharArray();
		buffer.writeInt(chars.length);

		for (char c : chars)
			buffer.writeChar(c);
	}

	@Override
	public void readSpawnData(ByteBuf buffer) {
		//Load the client ID
		String id = "";
		int length = buffer.readInt();
		for (int i = 0; i < length; i++)
			id += buffer.readChar();

		setWrapped(Game.entities().get(id).get().build());
	}

	public Entity getWrapped() {
		return wrapped;
	}

	private void setWrapped(Entity wrapped) {
		this.wrapped = wrapped;
		wrapped.components.add(transform);
	}

	public EntityTransform getTransform() {
		return transform;
	}

	/**
	 * All methods below here are exactly the same between FWEntity and FWEntityFX.
	 * *****************************************************************************
	 */
	@Override
	protected void entityInit() {
		//MC calls entityInit() before we finish wrapping, so this variable is required to check if wrapped exists.
		if (wrapped != null) {
			wrapped.events.publish(new Stateful.LoadEvent());
			updateCollider();
			WrapperEvent.FWEntityCreate event = new WrapperEvent.FWEntityCreate(wrapped, this);
			Game.events().publish(event);
		}
	}

	@Override
	public void onUpdate() {
		if (wrapped != null) {
			if (firstTick) {
				prevPosX = posX;
				prevPosY = posY;
				prevPosZ = posZ;
				setPosition(posX, posY, posZ);
				firstTick = false;
			}

			//onEntityUpdate();

			double deltaTime = 0.05;

			if (wrapped instanceof Updater) {
				((Updater) wrapped).update(deltaTime);
			}

			updateCollider();

			/**
			 * Update all components in the entity.
			 */
			wrapped.components()
				.stream()
				.filter(component -> component instanceof Updater)
				.forEach(component -> ((Updater) component).update(deltaTime));
		} else {
			Game.logger().error("Ticking entity without wrapped entity object.");
		}
	}

	/**
	 * Wraps the entity collider values
	 */
	public void updateCollider() {
		//Wrap entity collider
		if (wrapped.components.has(Collider.class)) {
			Collider collider = wrapped.components.get(Collider.class);

			//Transform cuboid based on entity.
			Cuboid size = collider
				.boundingBox
				.get();
			///.scalarMultiply(transform.scale());

			setBounds(size);
		}
	}

	@Override
	protected void setSize(float width, float height) {
		if (width != this.width || height != this.height) {
			this.width = width;
			this.height = height;
			setBounds(new Cuboid(-width / 2, -height / 2, -width / 2, width / 2, height / 2, width / 2));
		}
	}

	@Override
	public void setPosition(double x, double y, double z) {
		this.posX = x;
		this.posY = y;
		this.posZ = z;
		//Reset the bounding box
		if (getCollisionBoundingBox() != null) {
			setBounds(Game.natives().toNova(getCollisionBoundingBox()));
		}
	}

	/**
	 * Sets the bounding box of the entity based on NOVA cuboid bounds
	 * @param bounds NOVA Cuboid bounds
	 */
	public void setBounds(Cuboid bounds) {
		//TODO: Fix moveEntity auto-centering
		if (transform != null) {
			setEntityBoundingBox(Game.natives().toNative(bounds.add(transform.position())));
		}
	}

	@Override
	public void setDead() {
		wrapped.events.publish(new Stateful.UnloadEvent());
		super.setDead();
	}

	@Override
	public boolean hasCapabilities() {
		return capabilities.values().parallelStream().map(map -> map.keySet().parallelStream()).count() > 0;
	}

	@Override
	public <T> T addCapability(Capability<T> capability, T capabilityInstance, EnumSelector<Direction> facing) {
		if (facing.allowsAll()) {
			if (capabilities.get(Direction.UNKNOWN).containsKey(capability))
				throw new IllegalArgumentException("Already has capability " + capabilityInstance.getClass());

			capabilities.get(Direction.UNKNOWN).put(capability, capabilityInstance);
		} else {
			facing.forEach(enumFacing -> {
				Map<Capability<?>, Object> caps = capabilities.get(enumFacing);

				if (caps.containsKey(capability))
					throw new IllegalArgumentException("Already has capability " + capabilityInstance.getClass());

				caps.put(capability, capabilityInstance);
			});
		}
		return capabilityInstance;
	}

	@Override
	public boolean hasCapability(Capability<?> capability, Direction direction) {
		return Optional.of(direction)
			.filter(d -> d != Direction.UNKNOWN)
			.map(capabilities::get)
			.map(caps -> caps.containsValue(capability))
			.orElseGet(() -> capabilities.get(Direction.UNKNOWN).containsValue(capability));
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getCapability(Capability<T> capability, Direction direction) {
		return (T) Optional.of(direction)
			.filter(d -> d != Direction.UNKNOWN)
			.map(capabilities::get)
			.map(caps -> caps.get(capability))
			.orElseGet(() -> capabilities.get(Direction.UNKNOWN).get(capability));
	}
}
