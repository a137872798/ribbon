package com.netflix.client.config;

import com.google.common.base.Preconditions;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Base implementation of an IClientConfig with configuration that can be reloaded at runtime from an underlying
 * property source while optimizing access to property values.
 * <p>
 * Properties can either be scoped to a specific client or default properties that span all clients.   By default
 * properties follow the name convention `{clientname}.{namespace}.{key}` and then fallback to `{namespace}.{key}`
 * if not found
 * <p>
 * Internally the config tracks two maps, one for dynamic properties and one for code settable default values to use
 * when a property is not defined in the underlying property source.
 * 代表动态属性 可以在运行时 动态加载  是 config的骨架类
 */
public abstract class ReloadableClientConfig implements IClientConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ReloadableClientConfig.class);

    /**
     * 可以理解为属性名前缀 有些属性是精确到某个client 的 就会使用 clientName.nameSpace.key 的格式
     */
    private static final String DEFAULT_CLIENT_NAME = "";
    /**
     * 默认的命名空间
     */
    private static final String DEFAULT_NAMESPACE = "ribbon";

    // Map of raw property names (without namespace or client name) to values. All values are non-null and properly
    // typed to match the key type
    /**
     * 该对象就是维护了所有的 key:value 关系
     */
    private final Map<IClientConfigKey, Optional<?>> internalProperties = new ConcurrentHashMap<>();

    /**
     * 维护 动态属性 与 configKey 的映射关系
     */
    private final Map<IClientConfigKey, ReloadableProperty<?>> dynamicProperties = new ConcurrentHashMap<>();

    // List of actions to perform when configuration changes.  This includes both updating the Property instances
    // as well as external consumers.
    /**
     * 维护了当配置发生改变的时候会触发的 action
     */
    private final Map<IClientConfigKey, Runnable> changeActions = new ConcurrentHashMap<>();

    /**
     * 代表配置的刷新次数
     */
    private final AtomicLong refreshCounter = new AtomicLong();

    /**
     * 属性解析器
     */
    private final PropertyResolver resolver;

    /**
     * 代表该clientconfig 是针对哪个config起作用
     */
    private String clientName = DEFAULT_CLIENT_NAME;

    /**
     * 代表该clientconfig 是针对哪个namespace
     */
    private String namespace = DEFAULT_NAMESPACE;

    /**
     * 是否完成初始化
     */
    private boolean isLoaded = false;

    /**
     * 通过传入的 属性解析器进行初始化
     *
     * @param resolver
     */
    protected ReloadableClientConfig(PropertyResolver resolver) {
        this.resolver = resolver;
    }

    protected PropertyResolver getPropertyResolver() {
        return this.resolver;
    }

    /**
     * Refresh all seen properties from the underlying property storage
     * 重新加载属性 (因为属性可能是动态变化的)
     */
    public final void reload() {
        // 触发 所有 run() 方法
        changeActions.values().forEach(Runnable::run);
        // 调用所有动态属性的 reload 方法
        dynamicProperties.values().forEach(ReloadableProperty::reload);
        cachedToString = null;
    }

    /**
     * @deprecated Use {@link #loadProperties(String)}
     */
    @Deprecated
    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    @Override
    public final String getClientName() {
        return clientName;
    }

    @Override
    public String getNameSpace() {
        return namespace;
    }

    @Override
    public final void setNameSpace(String nameSpace) {
        this.namespace = nameSpace;
    }

    /**
     * 加载执行 client下所有的配置
     * @param clientName
     */
    @Override
    public void loadProperties(String clientName) {
        // 如果已经加载过 则抛出异常
        Preconditions.checkState(!isLoaded, "Config '{}' can only be loaded once", clientName);

        LOG.info("Loading config for `{}`", clientName);
        this.clientName = clientName;
        // 该属性没有使用 volatile 修饰
        this.isLoaded = true;
        // 开始加载属性 该方法由子类实现
        loadDefaultValues();
        // 触发所有的回调方法
        resolver.onChange(this::reload);

        // 之后打印加载的结果
        internalProperties.forEach((key, value) -> LOG.info("{} : {} -> {}", clientName, key, value.orElse(null)));
    }

    /**
     * @return use {@link #forEach(BiConsumer)}
     * 生成 副本对象
     */
    @Override
    @Deprecated
    public final Map<String, Object> getProperties() {
        final Map<String, Object> result = new HashMap<>(internalProperties.size());
        forEach((key, value) -> result.put(key.key(), String.valueOf(value)));
        return result;
    }

    /**
     * 针对每个属性 执行 函数
     * @param consumer
     */
    @Override
    public void forEach(BiConsumer<IClientConfigKey<?>, Object> consumer) {
        internalProperties.forEach((key, value) -> {
            if (value.isPresent()) {
                consumer.accept(key, value.get());
            }
        });
    }

    /**
     * Register an action that will refresh the cached value for key. Uses the current value as a reference and will
     * update from the dynamic property source to either delete or set a new value.
     *
     * @param key - Property key without client name or namespace
     *            为指定的 key 设置一个用于动态刷新属性的函数
     */
    private <T> void autoRefreshFromPropertyResolver(final IClientConfigKey<T> key) {
        changeActions.computeIfAbsent(key, ignore -> {
            // 生成一个加载属性的 函数
            final Supplier<Optional<T>> valueSupplier = () -> resolveFromPropertyResolver(key);
            final Optional<T> current = valueSupplier.get();
            if (current.isPresent()) {
                internalProperties.put(key, current);
            }

            final AtomicReference<Optional<T>> previous = new AtomicReference<>(current);
            return () -> {
                // 当感知到 onChange 时 自动执行该函数并设置值 和 打印日志
                final Optional<T> next = valueSupplier.get();
                if (!next.equals(previous.get())) {
                    LOG.info("New value {}: {} -> {}", key.key(), previous.get(), next);
                    previous.set(next);
                    internalProperties.put(key, next);
                }
            };
        });
    }

    /**
     * 代表本属性是动态属性 且具备重新加载的能力
     *
     * @param <T>
     */
    interface ReloadableProperty<T> extends Property<T> {

        /**
         * 增加一个 重新加载的功能 也就是默认的 Prop 是不具备重新加载属性的
         */
        void reload();
    }

    /**
     * 尝试从缓存中获取 或者构建一个新的 Prop 对象
     *
     * @param key             代表本prop 是对应到哪个 configKey的
     * @param valueSupplier   代表能生成 value 的函数对象
     * @param defaultSupplier 生成默认值的函数对象
     * @param <T>
     * @return
     */
    private synchronized <T> Property<T> getOrCreateProperty(final IClientConfigKey<T> key, final Supplier<Optional<T>> valueSupplier, final Supplier<T> defaultSupplier) {
        // 不允许 生成value 的 提供者函数为null
        Preconditions.checkNotNull(valueSupplier, "defaultValueSupplier cannot be null");

        // 尝试从 动态属性工厂中获取 期望的属性 不存在就生成一个新的对象
        return (Property<T>) dynamicProperties.computeIfAbsent(key, ignore -> new ReloadableProperty<T>() {
            /**
             * 设置当前属性为 null
             */
            private volatile Optional<T> current = Optional.empty();
            /**
             * 维护 当属性发生变化时的函数
             */
            private List<Consumer<T>> consumers = new CopyOnWriteArrayList<>();

            {
                reload();
            }

            @Override
            public void onChange(Consumer<T> consumer) {
                consumers.add(consumer);
            }

            @Override
            public Optional<T> get() {
                return current;
            }

            @Override
            public T getOrDefault() {
                return current.orElse(defaultSupplier.get());
            }

            @Override
            public void reload() {
                // 看来每 针对一个 client 或者 namesapce 都会统计对应范围内的 刷新次数
                refreshCounter.incrementAndGet();

                // 通过函数对象获取最新值 同时触发 onChange 函数
                Optional<T> next = valueSupplier.get();
                if (!next.equals(current)) {
                    current = next;
                    consumers.forEach(consumer -> consumer.accept(next.orElseGet(defaultSupplier::get)));
                }
            }

            @Override
            public String toString() {
                return String.valueOf(get());
            }
        });
    }

    /**
     * 尝试获取属性 不存在就返回null
     * @param key
     * @param <T>
     * @return
     */
    @Override
    public final <T> T get(IClientConfigKey<T> key) {
        Optional<T> value = (Optional<T>) internalProperties.get(key);
        if (value == null) {
            if (!isLoaded) {
                return null;
            } else {
                set(key, null);
                value = (Optional<T>) internalProperties.get(key);
            }
        }

        return value.orElse(null);
    }

    /**
     * 获取全局属性
     * @param key
     * @param <T>
     * @return
     */
    @Override
    public final <T> Property<T> getGlobalProperty(IClientConfigKey<T> key) {
        LOG.debug("Get global property {} default {}", key.key(), key.defaultValue());

        // 获取全局属性 如果不存在就创建  这里会返回一个动态属性对象
        return getOrCreateProperty(
                key,
                // 代表获取 属性值的函数
                () -> resolver.get(key.key(), key.type()),
                // 获取默认值的函数
                key::defaultValue);
    }

    /**
     * 获取动态属性对象
     * @param key
     * @param <T>
     * @return
     */
    @Override
    public final <T> Property<T> getDynamicProperty(IClientConfigKey<T> key) {
        LOG.debug("Get dynamic property key={} ns={} client={}", key.key(), getNameSpace(), clientName);

        if (isLoaded) {
            autoRefreshFromPropertyResolver(key);
        }

        return getOrCreateProperty(
                key,
                () -> (Optional<T>) internalProperties.getOrDefault(key, Optional.empty()),
                key::defaultValue);
    }

    @Override
    public <T> Property<T> getPrefixMappedProperty(IClientConfigKey<T> key) {
        LOG.debug("Get dynamic property key={} ns={} client={}", key.key(), getNameSpace(), clientName);

        return getOrCreateProperty(
                key,
                getPrefixedMapPropertySupplier(key),
                key::defaultValue);
    }

    /**
     * Resolve a properties final value in the following order or precedence
     * - client scope
     * - default scope
     * - internally set default
     * - IClientConfigKey defaultValue
     *
     * @param key
     * @param <T>
     * @return
     * 从 resolver 中 获取属性 默认情况使用 clientName + nameSpace + key 作为前缀
     */
    private <T> Optional<T> resolveFromPropertyResolver(IClientConfigKey<T> key) {
        Optional<T> value;
        if (!StringUtils.isEmpty(clientName)) {
            value = resolver.get(clientName + "." + getNameSpace() + "." + key.key(), key.type());
            if (value.isPresent()) {
                return value;
            }
        }

        // 没有获取到的情况 使用 nameSpace + key 作为前缀
        return resolver.get(getNameSpace() + "." + key.key(), key.type());
    }

    @Override
    public <T> Optional<T> getIfSet(IClientConfigKey<T> key) {
        return (Optional<T>) internalProperties.getOrDefault(key, Optional.empty());
    }

    /**
     * 将 value 按照key要求的类型进行转换
     * @param key
     * @param value
     * @param <T>
     * @return
     */
    private <T> T resolveValueToType(IClientConfigKey<T> key, Object value) {
        if (value == null) {
            return null;
        }

        final Class<T> type = key.type();
        // Unfortunately there's some legacy code setting string values for typed keys.  Here are do our best to parse
        // and store the typed value
        if (!value.getClass().equals(type)) {
            try {
                if (type.equals(String.class)) {
                    return (T) value.toString();
                } else if (value.getClass().equals(String.class)) {
                    final String strValue = (String) value;
                    if (Integer.class.equals(type)) {
                        return (T) Integer.valueOf(strValue);
                    } else if (Boolean.class.equals(type)) {
                        return (T) Boolean.valueOf(strValue);
                    } else if (Float.class.equals(type)) {
                        return (T) Float.valueOf(strValue);
                    } else if (Long.class.equals(type)) {
                        return (T) Long.valueOf(strValue);
                    } else if (Double.class.equals(type)) {
                        return (T) Double.valueOf(strValue);
                    } else if (TimeUnit.class.equals(type)) {
                        return (T) TimeUnit.valueOf(strValue);
                    } else {
                        return PropertyUtils.resolveWithValueOf(type, strValue)
                                .orElseThrow(() -> new IllegalArgumentException("Unsupported value type `" + type + "'"));
                    }
                } else {
                    return PropertyUtils.resolveWithValueOf(type, value.toString())
                            .orElseThrow(() -> new IllegalArgumentException("Incompatible value type `" + value.getClass() + "` while expecting '" + type + "`"));
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Error parsing value '" + value + "' for '" + key.key() + "'", e);
            }
        } else {
            return (T) value;
        }
    }

    private <T> Supplier<Optional<T>> getPrefixedMapPropertySupplier(IClientConfigKey<T> key) {
        final Method method;
        try {
            method = key.type().getDeclaredMethod("valueOf", Map.class);
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException("Class '" + key.type().getName() + "' must have static method valueOf(Map<String, String>)", e);
        }

        return () -> {
            final Map<String, String> values = new HashMap<>();

            resolver.forEach(getNameSpace() + "." + key.key(), values::put);

            if (!StringUtils.isEmpty(clientName)) {
                resolver.forEach(clientName + "." + getNameSpace() + "." + key.key(), values::put);
            }

            try {
                return Optional.ofNullable((T) method.invoke(null, values));
            } catch (Exception e) {
                LOG.warn("Unable to map value for '{}'", key.key(), e);
                return Optional.empty();
            }
        };
    }

    @Override
    public final <T> T get(IClientConfigKey<T> key, T defaultValue) {
        return Optional.ofNullable(get(key)).orElse(defaultValue);
    }

    /**
     * 将给定值 设置到 internalProp 中
     * @param key
     * @param value
     * @param <T>
     * @return
     */
    @Override
    public <T> IClientConfig set(IClientConfigKey<T> key, T value) {
        Preconditions.checkArgument(key != null, "key cannot be null");

        // 将value 转换成 key 要求的类型
        value = resolveValueToType(key, value);
        if (isLoaded) {
            // 将结果设置到 容器中
            internalProperties.put(key, Optional.ofNullable(resolveFromPropertyResolver(key).orElse(value)));
            // 并为 该key 追加自动更新的 action
            autoRefreshFromPropertyResolver(key);
        } else {
            internalProperties.put(key, Optional.ofNullable(value));
        }
        cachedToString = null;

        return this;
    }

    @Override
    @Deprecated
    public void setProperty(IClientConfigKey key, Object value) {
        Preconditions.checkArgument(value != null, "Value may not be null");
        set(key, value);
    }

    @Override
    @Deprecated
    public Object getProperty(IClientConfigKey key) {
        return get(key);
    }

    @Override
    @Deprecated
    public Object getProperty(IClientConfigKey key, Object defaultVal) {
        return Optional.ofNullable(get(key)).orElse(defaultVal);
    }

    @Override
    @Deprecated
    public boolean containsProperty(IClientConfigKey key) {
        return internalProperties.containsKey(key);
    }

    @Override
    @Deprecated
    public int getPropertyAsInteger(IClientConfigKey key, int defaultValue) {
        return Optional.ofNullable(getProperty(key)).map(Integer.class::cast).orElse(defaultValue);
    }

    @Override
    @Deprecated
    public String getPropertyAsString(IClientConfigKey key, String defaultValue) {
        return Optional.ofNullable(getProperty(key)).map(Object::toString).orElse(defaultValue);
    }

    @Override
    @Deprecated
    public boolean getPropertyAsBoolean(IClientConfigKey key, boolean defaultValue) {
        return Optional.ofNullable(getProperty(key)).map(Boolean.class::cast).orElse(defaultValue);
    }

    public IClientConfig applyOverride(IClientConfig override) {
        if (override == null) {
            return this;
        }

        override.forEach((key, value) -> setProperty(key, value));

        return this;
    }

    private volatile String cachedToString = null;

    @Override
    public String toString() {
        if (cachedToString == null) {
            String newToString = generateToString();
            cachedToString = newToString;
            return newToString;
        }
        return cachedToString;
    }

    /**
     * @return Number of individual properties refreshed.  This can be used to identify patterns of excessive updates.
     */
    public long getRefreshCount() {
        return refreshCounter.get();
    }

    private String generateToString() {
        return "ClientConfig:" + internalProperties.entrySet().stream()
                .map(t -> {
                    if (t.getKey().key().endsWith("Password")) {
                        return t.getKey() + ":***";
                    }
                    return t.getKey() + ":" + t.getValue();
                })
                .collect(Collectors.joining(", "));
    }
}
