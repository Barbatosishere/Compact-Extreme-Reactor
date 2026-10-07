package com.compact.extremereactor.common.multiblock;

import it.zerono.mods.zerocore.lib.data.geometry.CuboidBoundingBox;
import it.zerono.mods.zerocore.lib.multiblock.AbstractMultiblockController;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;

public final class CompactControllerBounds {

    private static Field _boundingBoxField;

    private CompactControllerBounds() {
    }

    public static void setAnchor(AbstractMultiblockController<?> controller, BlockPos anchor) {
        try {
            getBoundingBoxField().set(controller, new CuboidBoundingBox(anchor, anchor));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Unsupported ZeroCore bounding-box API; cannot initialize compact machine at "
                    + anchor, exception);
        }
    }

    private static synchronized Field getBoundingBoxField() throws ReflectiveOperationException {
        if (_boundingBoxField == null) {
            final Field field = AbstractMultiblockController.class.getDeclaredField("_boundingBox");
            if (field.getType() != CuboidBoundingBox.class || !field.trySetAccessible()) {
                throw new IllegalAccessException("ZeroCore _boundingBox is unavailable or has an incompatible type");
            }
            _boundingBoxField = field;
        }
        return _boundingBoxField;
    }
}
