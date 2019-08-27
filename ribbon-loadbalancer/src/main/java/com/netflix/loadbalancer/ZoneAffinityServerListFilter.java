package com.netflix.loadbalancer;

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

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.netflix.client.IClientConfigAware;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey;
import com.netflix.client.config.Property;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * This server list filter deals with filtering out servers based on the Zone affinity. 
 * This filtering will be turned on if either {@link CommonClientConfigKey#EnableZoneAffinity} 
 * or {@link CommonClientConfigKey#EnableZoneExclusivity} is set to true in {@link IClientConfig} object
 * passed into this class during initialization. When turned on, servers outside the same zone (as 
 * indicated by {@link Server#getZone()}) will be filtered out. By default, zone affinity 
 * and exclusivity are turned off and nothing is filtered out.
 * 
 * @author stonse
 * 要求 zone  一致的拦截器
 *
 */
public class ZoneAffinityServerListFilter<T extends Server> extends
        AbstractServerListFilter<T> implements IClientConfigAware {

    private static IClientConfigKey<String> ZONE = new CommonClientConfigKey<String>("@zone", "") {};
    private static IClientConfigKey<Double> MAX_LOAD_PER_SERVER = new CommonClientConfigKey<Double>("zoneAffinity.maxLoadPerServer", 0.6d) {};
    private static IClientConfigKey<Double> MAX_BLACKOUT_SERVER_PERCENTAGE = new CommonClientConfigKey<Double>("zoneAffinity.maxBlackOutServesrPercentage", 0.8d) {};
    private static IClientConfigKey<Integer> MIN_AVAILABLE_SERVERS = new CommonClientConfigKey<Integer>("zoneAffinity.minAvailableServers", 2) {};

    private boolean zoneAffinity;
    private boolean zoneExclusive;
    private Property<Double> activeReqeustsPerServerThreshold;
    private Property<Double> blackOutServerPercentageThreshold;
    private Property<Integer> availableServersThreshold;
    private Counter overrideCounter;
    private ZoneAffinityPredicate zoneAffinityPredicate;

    private static Logger logger = LoggerFactory.getLogger(ZoneAffinityServerListFilter.class);
    
    private String zone;

    /**
     * @deprecated Must pass in a config via {@link ZoneAffinityServerListFilter#ZoneAffinityServerListFilter(IClientConfig)}
     */
    @Deprecated
    public ZoneAffinityServerListFilter() {

    }

    public ZoneAffinityServerListFilter(IClientConfig niwsClientConfig) {
        initWithNiwsConfig(niwsClientConfig);
    }

    @Override
    public void initWithNiwsConfig(IClientConfig niwsClientConfig) {
        zoneAffinity = niwsClientConfig.getOrDefault(CommonClientConfigKey.EnableZoneAffinity);
        zoneExclusive = niwsClientConfig.getOrDefault(CommonClientConfigKey.EnableZoneExclusivity);
        zone = niwsClientConfig.getGlobalProperty(ZONE).getOrDefault();
        zoneAffinityPredicate = new ZoneAffinityPredicate(zone);

        activeReqeustsPerServerThreshold = niwsClientConfig.getDynamicProperty(MAX_LOAD_PER_SERVER);
        blackOutServerPercentageThreshold = niwsClientConfig.getDynamicProperty(MAX_BLACKOUT_SERVER_PERCENTAGE);
        availableServersThreshold = niwsClientConfig.getDynamicProperty(MIN_AVAILABLE_SERVERS);

        overrideCounter = Monitors.newCounter("ZoneAffinity_OverrideCounter");

        Monitors.registerObject("NIWSServerListFilter_" + niwsClientConfig.getClientName());
    }

    /**
     * 该方法 判断是否要按照区域来获取 服务列表
     * @param filtered
     * @return
     */
    private boolean shouldEnableZoneAffinity(List<T> filtered) {
        //如果没有开启 区域感知功能 且没有开启 排除区域功能就 返回false
        if (!zoneAffinity && !zoneExclusive) {
            return false;
        }
        //如果设置了该标识就是返回true 该标识代表 只允许使用本zone 的服务
        if (zoneExclusive) {
            return true;
        }
        //获取 均衡负载 信息统计对象
        LoadBalancerStats stats = getLoadBalancerStats();
        if (stats == null) {
            return zoneAffinity;
        } else {
            logger.debug("Determining if zone affinity should be enabled with given server list: {}", filtered);
            //获取 区域 快照
            ZoneSnapshot snapshot = stats.getZoneSnapshot(filtered);
            double loadPerServer = snapshot.getLoadPerServer();
            int instanceCount = snapshot.getInstanceCount();            
            int circuitBreakerTrippedCount = snapshot.getCircuitTrippedCount();
            //blackOutServerPercentageThreshold 代表 故障百分比
            //activeReqeustsPerServerThreshold 代表 实例平均负载
            //availableServersThreshold 代表可用实例数
            /**
             * 上述几个指标的意思就是 如果 本区域中 故障的 服务器超过80% 或者 平均每个实例负载 超过60% 或者 可用实例小于2 允许使用其他zone 的服务实例
             */
            if (((double) circuitBreakerTrippedCount) / instanceCount >= blackOutServerPercentageThreshold.getOrDefault()
                    || loadPerServer >= activeReqeustsPerServerThreshold.getOrDefault()
                    || (instanceCount - circuitBreakerTrippedCount) < availableServersThreshold.getOrDefault()) {
                logger.debug("zoneAffinity is overriden. blackOutServerPercentage: {}, activeReqeustsPerServer: {}, availableServers: {}", 
                        new Object[] {(double) circuitBreakerTrippedCount / instanceCount,  loadPerServer, instanceCount - circuitBreakerTrippedCount});
                return false;
            } else {
                return true;
            }
            
        }
    }

    /**
     * 针对 zone 进行 过滤 spring cloud 整合 eureka 和 ribbon 时
     * @param servers
     * @return
     */
    @Override
    public List<T> getFilteredListOfServers(List<T> servers) {
        //如果区域信息不为空 且 开启了 区域感知功能 或者 开启了限定了必须使用本区域的服务 且 存在服务对象的时候 可以开始过滤
        if (zone != null && (zoneAffinity || zoneExclusive) && servers !=null && servers.size() > 0){
            //使用zoneAffinityPredicate 对象 对server 进行过滤
            /**
             * 该谓语对象的内部实现  先将server 包装成 PredicateKey 根据zone 来匹配
             *     public boolean apply(PredicateKey input) {
             *         Server s = input.getServer();
             *         String az = s.getZone();
             *         if (az != null && zone != null && az.toLowerCase().equals(zone.toLowerCase())) {
             *             return true;
             *         } else {
             *             return false;
             *         }
             *     }
             */
            List<T> filteredServers = Lists.newArrayList(Iterables.filter(
                    servers, this.zoneAffinityPredicate.getServerOnlyPredicate()));
            //判断是否要进行区域感知 要的话就返回过滤后的结果 否则返回 原来的 servers
            //这里将根据 一系列统计信息 判断 是否合适开启 区域感知 功能 不合适的话 就不会只返回 对应zone 的服务对象
            if (shouldEnableZoneAffinity(filteredServers)) {
                return filteredServers;
            } else if (zoneAffinity) {
                overrideCounter.increment();
            }
        }
        return servers;
    }

    @Override
    public String toString(){
        StringBuilder sb = new StringBuilder("ZoneAffinityServerListFilter:");
        sb.append(", zone: ").append(zone).append(", zoneAffinity:").append(zoneAffinity);
        sb.append(", zoneExclusivity:").append(zoneExclusive);
        return sb.toString();       
    }

}
