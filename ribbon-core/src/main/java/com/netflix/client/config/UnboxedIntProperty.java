package com.netflix.client.config;

/**
 * 非包装类 (int) 因为是volatile 修饰所以能保证获取到的都是最新值
 */
public class UnboxedIntProperty {
    private volatile int value;

    public UnboxedIntProperty(Property<Integer> delegate) {
        this.value = delegate.getOrDefault();

        delegate.onChange(newValue -> this.value = newValue);
    }

    public UnboxedIntProperty(int constantValue) {
        this.value = constantValue;
    }

    public int get() {
        return value;
    }
}
