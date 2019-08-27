package com.netflix.client.config;

import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Internal abstraction to decouple the property source from Ribbon's internal configuration.
 * 用于 ribbon 进行属性解析的类
 */
public interface PropertyResolver {
    /**
     * @return Get the value of a property or Optional.empty() if not set
     * key 代表属性名 type 代表解析后的类型
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Iterate through all properties with the specified prefix
     * 针对 所有包含指定前缀的配置 执行相同的逻辑
     */
    void forEach(String prefix, BiConsumer<String, String> consumer);

    /**
     * Provide action to invoke when config changes
     * @param action
     * 设置 一个 当本属性发生变化时 执行的 runnable
     */
    void onChange(Runnable action);
}
