package nova.core.network;

import nova.core.util.ReflectionUtils;

/**
 * @author Calclavia
 */
public interface PacketHandler {

	/**
	 * Reads a packet.
	 *
	 * @param id - An ID to indicate the type of packet receiving. An ID of 0 indicates the default packet containing basic information.
	 * @param packet - data encoded into the packet.
	 */
	default void read(int id, Packet packet) {
		ReflectionUtils.forEachAnnotatedField(Sync.class, this, (field, annotation) -> {
			if (annotation.id() == id) {
				try {
					field.set(this, packet.read(field.getType()));
				} catch (IllegalAccessException e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Writes a packet based on the arguments.
	 *
	 * @param id - The ID of the packet. An ID of 0 indicates the default packet containing basic information.
	 * @param packet - data encoded into the packet
	 */
	default void write(int id, Packet packet) {
		packet.writeInt(id);
		ReflectionUtils.forEachAnnotatedField(Sync.class, this, (field, annotation) -> {
			if (annotation.id() == id) {
				try {
					packet.write(field.get(this));
				} catch (IllegalAccessException e) {
					e.printStackTrace();
				}
			}
		});
	}

}
