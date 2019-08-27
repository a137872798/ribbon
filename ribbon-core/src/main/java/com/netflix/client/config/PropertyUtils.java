package com.netflix.client.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 属性的工具类
 */
public class PropertyUtils {
    private static Logger LOG = LoggerFactory.getLogger(PropertyUtils.class);

    private PropertyUtils() {}

    /**
     * Class 代表配置类 method 代表能获取到 prop 的那个方法
     */
    private static Map<Class<?>, Optional<Method>> valueOfMethods = new ConcurrentHashMap<>();

    public static <T> Optional<T> resolveWithValueOf(Class<T> type, String value) {
        // 当key 没有找到对应的value时， 传入key 生成一个新的value 对象 并保存到容器中
        return valueOfMethods.computeIfAbsent(type, ignore -> {
            try {
                // 存放 valueOf(String) 方法
                return Optional.of(type.getDeclaredMethod("valueOf", String.class));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            } catch (Exception e) {
                LOG.warn("Unable to determine if type " + type + " has a valueOf() static method", e);
                return Optional.empty();
            }
        }).map(method -> {
            try {
                return (T)method.invoke(null, value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

}
