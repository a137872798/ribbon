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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.netflix.client.ClientFactory;
import com.netflix.client.config.ClientConfigFactory;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey;
import com.netflix.client.config.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Load balancer that can avoid a zone as a whole when choosing server. 
 *<p>
 * The key metric used to measure the zone condition is Average Active Requests,
which is aggregated per rest client per zone. It is the
total outstanding requests in a zone divided by number of available targeted instances (excluding circuit breaker tripped instances).
This metric is very effective when timeout occurs slowly on a bad zone.
<p>
The  LoadBalancer will calculate and examine zone stats of all available zones. If the Average Active Requests for any zone has reached a configured threshold, this zone will be dropped from the active server list. In case more than one zone has reached the threshold, the zone with the most active requests per server will be dropped.
Once the the worst zone is dropped, a zone will be chosen among the rest with the probability proportional to its number of instances.
A server will be returned from the chosen zone with a given Rule (A Rule is a load balancing strategy, for example {@link AvailabilityFilteringRule})
For each request, the steps above will be repeated. That is to say, each zone related load balancing decisions are made at real time with the up-to-date statistics aiding the choice.

 * @author awang
 *
 * @param <T>
 *          区域感知的均衡负载器 是拓展于 动态服务均衡负载对象
 */
public class ZoneAwareLoadBalancer<T extends Server> extends DynamicServerListLoadBalancer<T> {

    private ConcurrentHashMap<String, BaseLoadBalancer> balancers = new ConcurrentHashMap<String, BaseLoadBalancer>();
    
    private static final Logger logger = LoggerFactory.getLogger(ZoneAwareLoadBalancer.class);

    private static final IClientConfigKey<Boolean> ENABLED = new CommonClientConfigKey<Boolean>(
            "ZoneAwareNIWSDiscoveryLoadBalancer.enabled", true){};

    private static final IClientConfigKey<Double> TRIGGERING_LOAD_PER_SERVER_THRESHOLD = new CommonClientConfigKey<Double>(
            "ZoneAwareNIWSDiscoveryLoadBalancer.%s.triggeringLoadPerServerThreshold", 0.2d){};

    private static final IClientConfigKey<Double> AVOID_ZONE_WITH_BLACKOUT_PERCENTAGE = new CommonClientConfigKey<Double>(
            "ZoneAwareNIWSDiscoveryLoadBalancer.%s.avoidZoneWithBlackoutPercetage", 0.99999d){};


    private Property<Double> triggeringLoad = Property.of(TRIGGERING_LOAD_PER_SERVER_THRESHOLD.defaultValue());

    private Property<Double> triggeringBlackoutPercentage = Property.of(AVOID_ZONE_WITH_BLACKOUT_PERCENTAGE.defaultValue());

    private Property<Boolean> enabled = Property.of(ENABLED.defaultValue());

    void setUpServerList(List<Server> upServerList) {
        this.upServerList = upServerList;
    }

    @Deprecated
    public ZoneAwareLoadBalancer(IClientConfig clientConfig, IRule rule,
            IPing ping, ServerList<T> serverList, ServerListFilter<T> filter) {
        super(clientConfig, rule, ping, serverList, filter);

        String name = Optional.ofNullable(getName()).orElse("default");

        this.enabled = clientConfig.getGlobalProperty(ENABLED);
        this.triggeringLoad = clientConfig.getGlobalProperty(TRIGGERING_LOAD_PER_SERVER_THRESHOLD.format(name));
        this.triggeringBlackoutPercentage = clientConfig.getGlobalProperty(AVOID_ZONE_WITH_BLACKOUT_PERCENTAGE.format(name));
    }

    public ZoneAwareLoadBalancer(IClientConfig clientConfig, IRule rule,
                                 IPing ping, ServerList<T> serverList, ServerListFilter<T> filter,
                                 ServerListUpdater serverListUpdater) {
        super(clientConfig, rule, ping, serverList, filter, serverListUpdater);

        String name = Optional.ofNullable(getName()).orElse("default");

        this.enabled = clientConfig.getGlobalProperty(ENABLED);
        this.triggeringLoad = clientConfig.getGlobalProperty(TRIGGERING_LOAD_PER_SERVER_THRESHOLD.format(name));
        this.triggeringBlackoutPercentage = clientConfig.getGlobalProperty(AVOID_ZONE_WITH_BLACKOUT_PERCENTAGE.format(name));
    }

    public ZoneAwareLoadBalancer() {
        super();
    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        super.initWithNiwsConfig(clientConfig);

        String name = Optional.ofNullable(getName()).orElse("default");

        this.enabled = clientConfig.getGlobalProperty(ENABLED);
        this.triggeringLoad = clientConfig.getGlobalProperty(TRIGGERING_LOAD_PER_SERVER_THRESHOLD.format(name));
        this.triggeringBlackoutPercentage = clientConfig.getGlobalProperty(AVOID_ZONE_WITH_BLACKOUT_PERCENTAGE.format(name));
    }

    /**
     * 该方法 重写了 设置 zone 与 所属 server map 的方法
     * @param zoneServersMap
     */
    @Override
    protected void setServerListForZones(Map<String, List<Server>> zoneServersMap) {
        //更新统计信息
        super.setServerListForZones(zoneServersMap);
        //balancers 以zone 为key BaseLoadBalance 为value 的map
        if (balancers == null) {
            balancers = new ConcurrentHashMap<String, BaseLoadBalancer>();
        }
        for (Map.Entry<String, List<Server>> entry: zoneServersMap.entrySet()) {
        	String zone = entry.getKey().toLowerCase();
        	//更新负载对象维护的 服务列表
            getLoadBalancer(zone).setServersList(entry.getValue());
        }
        // check if there is any zone that no longer has a server
        // and set the list to empty so that the zone related metrics does not
        // contain stale data
        // 更新 负载对象内部的 serverList
        for (Map.Entry<String, BaseLoadBalancer> existingLBEntry: balancers.entrySet()) {
            if (!zoneServersMap.keySet().contains(existingLBEntry.getKey())) {
                existingLBEntry.getValue().setServersList(Collections.emptyList());
            }
        }
    }

    /**
     * 该方法 覆盖父类方法 能够按照zone 挑选合适 的 server
     * @param key
     * @return
     */
    @Override
    public Server chooseServer(Object key) {
        //如果 可用区域 只有一个 就不需要 通过区域进行筛选 直接调用父类的方法
        if (!enabled.getOrDefault() || getLoadBalancerStats().getAvailableZones().size() <= 1) {
            logger.debug("Zone aware logic disabled or there is only one zone");
            return super.chooseServer(key);
        }
        Server server = null;
        try {
            //获取均衡负载的统计对象
            LoadBalancerStats lbStats = getLoadBalancerStats();
            //根据统计对象 获取合适的快照
            Map<String, ZoneSnapshot> zoneSnapshot = ZoneAvoidanceRule.createSnapshot(lbStats);
            logger.debug("Zone snapshots: {}", zoneSnapshot);
            Set<String> availableZones = ZoneAvoidanceRule.getAvailableZones(zoneSnapshot, triggeringLoad.getOrDefault(), triggeringBlackoutPercentage.getOrDefault());
            logger.debug("Available zones: {}", availableZones);
            if (availableZones != null &&  availableZones.size() < zoneSnapshot.keySet().size()) {
                String zone = ZoneAvoidanceRule.randomChooseZone(zoneSnapshot, availableZones);
                logger.debug("Zone chosen: {}", zone);
                if (zone != null) {
                    BaseLoadBalancer zoneLoadBalancer = getLoadBalancer(zone);
                    server = zoneLoadBalancer.chooseServer(key);
                }
            }
        } catch (Exception e) {
            logger.error("Error choosing server using zone aware logic for load balancer={}", name, e);
        }
        if (server != null) {
            return server;
        } else {
            logger.debug("Zone avoidance logic is not invoked.");
            return super.chooseServer(key);
        }
    }

    /**
     * 通过 zone 从balancers 中获取负载对象
     * @param zone
     * @return
     */
    @VisibleForTesting
    BaseLoadBalancer getLoadBalancer(String zone) {
        zone = zone.toLowerCase();
        BaseLoadBalancer loadBalancer = balancers.get(zone);
        if (loadBalancer == null) {
        	// We need to create rule object for load balancer for each zone
            // 没有对象的情况下 尝试使用默认rule 生成一个  并返回
        	IRule rule = cloneRule(this.getRule());
            loadBalancer = new BaseLoadBalancer(this.getName() + "_" + zone, rule, this.getLoadBalancerStats());
            BaseLoadBalancer prev = balancers.putIfAbsent(zone, loadBalancer);
            if (prev != null) {
            	loadBalancer = prev;
            }
        } 
        return loadBalancer;        
    }

    private IRule cloneRule(IRule toClone) {
    	IRule rule;
    	if (toClone == null) {
    		rule = new AvailabilityFilteringRule();
    	} else {
    		String ruleClass = toClone.getClass().getName();        		
    		try {
				rule = (IRule) ClientFactory.instantiateInstanceWithClientConfig(ruleClass, this.getClientConfig());
			} catch (Exception e) {
				throw new RuntimeException("Unexpected exception creating rule for ZoneAwareLoadBalancer", e);
			}
    	}
    	return rule;
    }
    
       
    @Override
    public void setRule(IRule rule) {
        super.setRule(rule);
        if (balancers != null) {
            for (String zone: balancers.keySet()) {
                balancers.get(zone).setRule(cloneRule(rule));
            }
        }
    }
}
