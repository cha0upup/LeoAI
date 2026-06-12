package org.leo.jmg.mem.packer;

public interface Packer {
    /**
     * 将自定义类打包成特定 payload
     *
     * @param classPackerConfig 自定义类信息
     * @return 字符串 payload
     */
    default String pack(ClassPackerConfig classPackerConfig) throws Exception {
        throw new UnsupportedOperationException("当前 " + this.getClass().getSimpleName() + " 不支持 string 生成");
    }
}
