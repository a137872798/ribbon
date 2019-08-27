/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.client.config;


import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

/**
 * Created by awang on 7/18/14.
 * Client端的 配置工厂
 */
public interface ClientConfigFactory {

    /**
     * 生成一个新的 配置对象
     * @return
     */
    IClientConfig newConfig();

    /**
     * 获取默认的配置工厂
     */
    ClientConfigFactory DEFAULT = findDefaultConfigFactory();

    /**
     * 看来不同的工厂有不同的优先级 默认为0
     * @return
     */
    default int getPriority() { return 0; }

    /**
     * 根据SPI 加载所有的 configFactory对象 并按照优先级排序 返回第一个对象
     * @return
     */
    static ClientConfigFactory findDefaultConfigFactory() {
        return StreamSupport.stream(ServiceLoader.load(ClientConfigFactory.class).spliterator(), false)
                .sorted(Comparator
                        .comparingInt(ClientConfigFactory::getPriority)
                        .thenComparing(f -> f.getClass().getCanonicalName())
                        .reversed())
                .findFirst()
                .orElseGet(() -> {
                    throw new IllegalStateException("Expecting at least one implementation of ClientConfigFactory discoverable via the ServiceLoader");
                });
    }
}
