package com.netflix.client.util;

import java.io.File;
import java.net.URL;
import java.net.URLDecoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ribbon 资源对象 内部是 URL 相关信息
 */
public abstract class Resources {
    private static final Logger logger = LoggerFactory.getLogger(Resources.class);

    /**
     * 传入 资源名获取到 URL 对象
     * @param resourceName
     * @return
     */
    public static URL getResource(String resourceName) {
        URL url = null;
        // attempt to load from the context classpath
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null) {
            // 尝试使用classLoader 加载资源
            url = loader.getResource(resourceName);
        }
        if (url == null) {
            // attempt to load from the system classpath
            url = ClassLoader.getSystemResource(resourceName);
        }
        if (url == null) {
            try {
                resourceName = URLDecoder.decode(resourceName, "UTF-8");
                url = (new File(resourceName)).toURI().toURL();
            } catch (Exception e) {
                logger.error("Problem loading resource", e);
            }
        }
        // 以上都加载失败时 返回 null
        return url;
    }
}
