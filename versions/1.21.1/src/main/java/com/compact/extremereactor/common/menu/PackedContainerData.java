package com.compact.extremereactor.common.menu;

import net.minecraft.world.inventory.ContainerData;

/**
 * 将每个逻辑 int 拆成两个 16 位数据槽。
 *
 * <p>原版容器同步协议在线路上只安全承载有符号 short。服务端使用编码模式暴露
 * 高低 16 位，客户端使用解码模式从 {@link ContainerData#set(int, int)} 收到的槽位
 * 重建原始 int，从而避免坐标和机器大数值发生 16 位回绕。</p>
 */
final class PackedContainerData implements ContainerData {

    private final ContainerData _source;
    private final int _logicalCount;
    private final boolean _serverEncoding;

    PackedContainerData(ContainerData source, int logicalCount, boolean serverEncoding) {
        this._source = source;
        this._logicalCount = logicalCount;
        this._serverEncoding = serverEncoding;
    }

    int getValue(int logicalIndex) {
        if (logicalIndex < 0 || logicalIndex >= this._logicalCount) {
            return 0;
        }
        if (this._serverEncoding) {
            return this._source.get(logicalIndex);
        }
        final int low = this._source.get(logicalIndex * 2) & 0xFFFF;
        final int high = this._source.get(logicalIndex * 2 + 1) & 0xFFFF;
        return low | (high << 16);
    }

    @Override
    public int get(int index) {
        if (index < 0 || index >= this.getCount()) {
            return 0;
        }
        if (!this._serverEncoding) {
            return this._source.get(index);
        }
        final int value = this._source.get(index / 2);
        return (index & 1) == 0 ? value & 0xFFFF : value >>> 16;
    }

    @Override
    public void set(int index, int value) {
        if (!this._serverEncoding) {
            this._source.set(index, value);
        }
    }

    @Override
    public int getCount() {
        return this._logicalCount * 2;
    }
}
