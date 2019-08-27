package com.netflix.client.config;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Ribbon specific encapsulation of a dynamic configuration property
 * @param <T>
 *     代表 ribbon 中的配置属性
 */
public interface Property<T> {
    /**
     * Register a consumer to be called when the configuration changes
     * @param consumer
     * 注册一个 消费者 当配置发生改变时触发
     */
    void onChange(Consumer<T> consumer);

    /**
     * @return Get the current value.  Can be null if not set
     * 获取属性值
     */
    Optional<T> get();

    /**
     * @return Get the current value or the default value if not set
     * 获取属性值 不存在的话返回默认值
     */
    T getOrDefault();

    default Property<T> fallbackWith(Property<T> fallback) {
        return new FallbackProperty<>(this, fallback);
    }

    /**
     * 通过一个数值来 初始化一个 Prop 对象
     * @param value
     * @param <T>
     * @return
     */
    static <T> Property<T> of(T value) {
        return new Property<T>() {
            @Override
            public void onChange(Consumer<T> consumer) {
                // It's a static property so no need to track the consumer
            }

            @Override
            public Optional<T> get() {
                return Optional.ofNullable(value);
            }

            @Override
            public T getOrDefault() {
                return value;
            }

            @Override
            public String toString( ){
                return String.valueOf(value);
            }
        };
    }
}
