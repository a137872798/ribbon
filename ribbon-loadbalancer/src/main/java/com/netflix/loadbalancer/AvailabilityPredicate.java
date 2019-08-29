/*
 *
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.loadbalancer;

import com.google.common.base.Preconditions;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey;
import com.netflix.client.config.Property;

import javax.annotation.Nullable;

/**
 * Predicate with the logic of filtering out circuit breaker tripped servers and servers 
 * with too many concurrent connections from this client.
 * 
 * @author awang
 *      通过可用性 进行过滤
 */
public class AvailabilityPredicate extends  AbstractServerPredicate {

    private static final IClientConfigKey<Boolean> FILTER_CIRCUIT_TRIPPED = new CommonClientConfigKey<Boolean>(
            "niws.loadbalancer.availabilityFilteringRule.filterCircuitTripped", true) {};

    /**
     * 默认的活跃连接数
     */
    private static final IClientConfigKey<Integer> DEFAULT_ACTIVE_CONNECTIONS_LIMIT = new CommonClientConfigKey<Integer>(
            "niws.loadbalancer.availabilityFilteringRule.activeConnectionsLimit", -1) {};

    private static final IClientConfigKey<Integer> ACTIVE_CONNECTIONS_LIMIT = new CommonClientConfigKey<Integer>(
            "ActiveConnectionsLimit", -1) {};

    private Property<Boolean> circuitBreakerFiltering = Property.of(FILTER_CIRCUIT_TRIPPED.defaultValue());
    private Property<Integer> defaultActiveConnectionsLimit = Property.of(DEFAULT_ACTIVE_CONNECTIONS_LIMIT.defaultValue());
    private Property<Integer> activeConnectionsLimit = Property.of(ACTIVE_CONNECTIONS_LIMIT.defaultValue());

    public AvailabilityPredicate(IRule rule, IClientConfig clientConfig) {
        super(rule);
        //初始化 动态属性
        initDynamicProperty(clientConfig);
    }

    public AvailabilityPredicate(LoadBalancerStats lbStats, IClientConfig clientConfig) {
        super(lbStats);
        initDynamicProperty(clientConfig);
    }

    AvailabilityPredicate(IRule rule) {
        super(rule);
    }

    /**
     * 初始化 动态属性
     * @param clientConfig
     */
    private void initDynamicProperty(IClientConfig clientConfig) {
        if (clientConfig != null) {
            this.circuitBreakerFiltering = clientConfig.getGlobalProperty(FILTER_CIRCUIT_TRIPPED);
            this.defaultActiveConnectionsLimit = clientConfig.getGlobalProperty(DEFAULT_ACTIVE_CONNECTIONS_LIMIT);
            this.activeConnectionsLimit = clientConfig.getDynamicProperty(ACTIVE_CONNECTIONS_LIMIT);
        }
    }

    /**
     * 获取 连接数限制
     * @return
     */
    private int getActiveConnectionsLimit() {
        // 先获取 设定值
        Integer limit = activeConnectionsLimit.getOrDefault();
        if (limit == -1) {
            // 没有的话尝试获取默认值
            limit = defaultActiveConnectionsLimit.getOrDefault();
            if (limit == -1) {
                limit = Integer.MAX_VALUE;
            }
        }
        return limit;
    }

    /**
     * 不存在 统计信息的情况下 回避该层过滤
     * @param input
     * @return
     */
    @Override
    public boolean apply(@Nullable PredicateKey input) {
        LoadBalancerStats stats = getLBStats();
        if (stats == null) {
            return true;
        }
        //判断是否 应该过滤掉该服务
        return !shouldSkipServer(stats.getSingleServerStat(input.getServer()));
    }
    
    private boolean shouldSkipServer(ServerStats stats) {
        //开启熔断功能并且 距离上次熔断的时间 短于某个阈值 就代表 本次还属于不可用的范畴内
        if ((circuitBreakerFiltering.getOrDefault() && stats.isCircuitBreakerTripped())
                // 请求次数过高也会被拒绝
                || stats.getActiveRequestsCount() >= getActiveConnectionsLimit()) {
            return true;
        }
        return false;
    }

}
