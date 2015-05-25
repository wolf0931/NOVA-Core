package nova.wrapper.mc1710.forward.entity;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import nova.core.component.Updater;
import nova.core.component.transform.EntityTransform;
import nova.core.entity.Entity;
import nova.core.entity.EntityFactory;
import nova.core.entity.EntityWrapper;
import nova.core.retention.Data;
import nova.core.retention.Storable;
import nova.wrapper.mc1710.util.DataUtility;

/**
 * Entity wrapper
 * @author Calclavia
 */
public class FWEntity extends net.minecraft.entity.Entity implements EntityWrapper {

	public final Entity wrapped;
	public final EntityTransform transform;

	public FWEntity(World world, EntityFactory factory, Object... args) {
		super(world);
		this.wrapped = factory.makeEntity(this, args);
		this.transform = wrapped.transform;
		entityInit();
	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt) {
		if (wrapped instanceof Storable) {
			((Storable) wrapped).load(DataUtility.nbtToData(nbt));
		}
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt) {

		if (wrapped instanceof Storable) {
			Data data = new Data();
			((Storable) wrapped).save(data);
			DataUtility.dataToNBT(nbt, data);
		}
	}

	/**
	 * All methods below here are exactly the same between FWEntity and FWEntityFX.
	 * *****************************************************************************
	 */
	@Override
	protected void entityInit() {
		//MC calls entityInit() before we finish wrapping, so this variable is required to check if wrapped exists.
		if (wrapped != null) {
			wrapped.awake();
		}
	}

	@Override
	public void onUpdate() {
		super.onUpdate();
		double deltaTime = 0.05;

		if (wrapped instanceof Updater) {
			((Updater) wrapped).update(deltaTime);
		}

		/**
		 * Update all components in the entity.
		 */
		wrapped.components()
			.stream()
			.filter(component -> component instanceof Updater)
			.forEach(component -> ((Updater) component).update(deltaTime));
	}
}
