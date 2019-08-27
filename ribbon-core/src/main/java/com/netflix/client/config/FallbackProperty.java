package com.netflix.client.config;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 降级属性
 * @param <T>
 */
public final class FallbackProperty<T> implements Property<T> {

    /**
     * 代表主属性
     */
    private final Property<T> primary;
    /**
     * 代表降级属性
     */
    private final Property<T> fallback;

    public FallbackProperty(Property<T> primary, Property<T> fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /**
     * 注册 当属性发生变化时的消费者  该消费者对象会处理修改后的属性值
     * @param consumer
     */
    @Override
    public void onChange(Consumer<T> consumer) {
        primary.onChange(ignore -> consumer.accept(getOrDefault()));
        fallback.onChange(ignore -> consumer.accept(getOrDefault()));
    }

    /**
     * 默认情况下 会返回主配置 如果主属性不存在就会返回降级属性
     * @return
     */
    @Override
    public Optional<T> get() {
        Optional<T> value = primary.get();
        if (value.isPresent()) {
            return value;
        }
        return fallback.get();
    }

    @Override
    public T getOrDefault() {
        return primary.get().orElseGet(fallback::getOrDefault);
    }

    @Override
    public String toString() {
        return String.valueOf(get());
    }
}
