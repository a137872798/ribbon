package com.netflix.client.config;

import com.netflix.config.ConfigurationManager;
import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.event.ConfigurationEvent;
import org.apache.commons.configuration.event.ConfigurationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 适配 Archaius 具备加载动态配置的能力
 */
public class ArchaiusPropertyResolver implements PropertyResolver {
    private static final Logger LOG = LoggerFactory.getLogger(ArchaiusPropertyResolver.class);

    public static final ArchaiusPropertyResolver INSTANCE = new ArchaiusPropertyResolver();

    /**
     * 代表 动态配置的上级抽象 在初始化时 会依次尝试进行加载
     */
    private final AbstractConfiguration config;
    private final CopyOnWriteArrayList<Runnable> actions = new CopyOnWriteArrayList<>();

    private ArchaiusPropertyResolver() {
        // 生成 archaius
        this.config = ConfigurationManager.getConfigInstance();

        // 添加监听器对象 在对应的属性发生改变时触发
        ConfigurationManager.getConfigInstance().addConfigurationListener(new ConfigurationListener() {
            @Override
            public void configurationChanged(ConfigurationEvent event) {
                // 代表该事件是在更新后触发 就执行对应的action
                if (!event.isBeforeUpdate()) {
                    // ArchaiusPropertyResolver::invokeAction 代表执行 run() 只是不抛异常
                    actions.forEach(ArchaiusPropertyResolver::invokeAction);
                }
            }
        });
    }

    private static void invokeAction(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.info("Failed to invoke action", e);
        }
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        if (Integer.class.equals(type)) {
            return Optional.ofNullable((T) config.getInteger(key, null));
        } else if (Boolean.class.equals(type)) {
            return Optional.ofNullable((T) config.getBoolean(key, null));
        } else if (Float.class.equals(type)) {
            return Optional.ofNullable((T) config.getFloat(key, null));
        } else if (Long.class.equals(type)) {
            return Optional.ofNullable((T) config.getLong(key, null));
        } else if (Double.class.equals(type)) {
            return Optional.ofNullable((T) config.getDouble(key, null));
        } else if (TimeUnit.class.equals(type)) {
            return Optional.ofNullable((T) TimeUnit.valueOf(config.getString(key, null)));
        } else {
            return Optional.ofNullable(config.getStringArray(key))
                    .filter(ar -> ar.length > 0)
                    .map(ar -> Arrays.stream(ar).collect(Collectors.joining(",")))
                    .map(value -> {
                        if (type.equals(String.class)) {
                            return (T)value;
                        } else {
                            return PropertyUtils.resolveWithValueOf(type, value)
                                    .orElseThrow(() -> new IllegalArgumentException("Unable to convert value to desired type " + type));
                        }
                    });
        }
    }

    @Override
    public void forEach(String prefix, BiConsumer<String, String> consumer) {
        Optional.ofNullable(config.subset(prefix))
                .ifPresent(subconfig -> {
                    subconfig.getKeys().forEachRemaining(key -> {
                        String value = config.getString(prefix + "." + key);
                        consumer.accept(key, value);
                    });
                });
    }

    @Override
    public void onChange(Runnable action) {
        actions.add(action);
    }

    public int getActionCount() {
        return actions.size();
    }
}
