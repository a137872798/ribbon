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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.netflix.client.config.IClientConfig;

/**
 * A rule that uses the a {@link CompositePredicate} to filter servers based on zone and availability. The primary predicate is composed of
 * a {@link ZoneAvoidancePredicate} and {@link AvailabilityPredicate}, with the fallbacks to {@link AvailabilityPredicate}
 * and an "always true" predicate returned from {@link AbstractServerPredicate#alwaysTrue()} 
 * 
 * @author awang
 *      根据zone 进行过滤的 规则对象
 */
public class ZoneAvoidanceRule extends PredicateBasedRule {

    private static final Random random = new Random();

    /**
     * 组合谓语对象
     */
    private CompositePredicate compositePredicate;
    
    public ZoneAvoidanceRule() {
        //生成了 一个以zone 作为 谓语条件的 规则对象
        ZoneAvoidancePredicate zonePredicate = new ZoneAvoidancePredicate(this);
        //可用谓语作为 第二个条件
        AvailabilityPredicate availabilityPredicate = new AvailabilityPredicate(this);
        //生成组合谓语对象
        compositePredicate = createCompositePredicate(zonePredicate, availabilityPredicate);
    }
    
    private CompositePredicate createCompositePredicate(ZoneAvoidancePredicate p1, AvailabilityPredicate p2) {
        return CompositePredicate.withPredicates(p1, p2)
                             .addFallbackPredicate(p2)
                             .addFallbackPredicate(AbstractServerPredicate.alwaysTrue())
                             .build();
    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        ZoneAvoidancePredicate zonePredicate = new ZoneAvoidancePredicate(this, clientConfig);
        AvailabilityPredicate availabilityPredicate = new AvailabilityPredicate(this, clientConfig);
        compositePredicate = createCompositePredicate(zonePredicate, availabilityPredicate);
    }

    /**
     * 根据均衡负载对象 生成快照
     * @param lbStats
     * @return
     */
    static Map<String, ZoneSnapshot> createSnapshot(LoadBalancerStats lbStats) {
        Map<String, ZoneSnapshot> map = new HashMap<String, ZoneSnapshot>();
        //获取统计对象中所有可用的区域
        for (String zone : lbStats.getAvailableZones()) {
            //通过指定zone 获取快照对象
            ZoneSnapshot snapshot = lbStats.getZoneSnapshot(zone);
            map.put(zone, snapshot);
        }
        return map;
    }

    static String randomChooseZone(Map<String, ZoneSnapshot> snapshot,
            Set<String> chooseFrom) {
        if (chooseFrom == null || chooseFrom.size() == 0) {
            return null;
        }
        String selectedZone = chooseFrom.iterator().next();
        if (chooseFrom.size() == 1) {
            return selectedZone;
        }
        int totalServerCount = 0;
        for (String zone : chooseFrom) {
            totalServerCount += snapshot.get(zone).getInstanceCount();
        }
        int index = random.nextInt(totalServerCount) + 1;
        int sum = 0;
        for (String zone : chooseFrom) {
            sum += snapshot.get(zone).getInstanceCount();
            if (index <= sum) {
                selectedZone = zone;
                break;
            }
        }
        return selectedZone;
    }

    /**
     * 获取 map 中可用的 zone 信息
     * @param snapshot
     * @param triggeringLoad
     * @param triggeringBlackoutPercentage
     * @return
     */
    public static Set<String> getAvailableZones(
            Map<String, ZoneSnapshot> snapshot, double triggeringLoad,
            double triggeringBlackoutPercentage) {
        //如果快照容器为空 返回null
        if (snapshot.isEmpty()) {
            return null;
        }
        //获取 所有zone 信息
        Set<String> availableZones = new HashSet<String>(snapshot.keySet());
        //如果 可选区域只有一个 就直接返回
        if (availableZones.size() == 1) {
            return availableZones;
        }
        Set<String> worstZones = new HashSet<String>();
        double maxLoadPerServer = 0;
        boolean limitedZoneAvailability = false;

        //遍历 zone->zoneSnapshot map 内容
        for (Map.Entry<String, ZoneSnapshot> zoneEntry : snapshot.entrySet()) {
            String zone = zoneEntry.getKey();
            ZoneSnapshot zoneSnapshot = zoneEntry.getValue();
            //获取 该zone 下server 数量
            int instanceCount = zoneSnapshot.getInstanceCount();
            if (instanceCount == 0) {
                //为空 就将 该zone 从map 中移除
                availableZones.remove(zone);
                //该标识应该是记录 可使用zone 是否发生变化
                limitedZoneAvailability = true;
            } else {
                //实例不为0 的情况 就要看有多少 不健康的 服务实例
                double loadPerServer = zoneSnapshot.getLoadPerServer();
                //这个指标应该是 断路器触发次数
                if (((double) zoneSnapshot.getCircuitTrippedCount())
                        / instanceCount >= triggeringBlackoutPercentage
                        || loadPerServer < 0) {
                    //触发次数过高 将该zone移除
                    availableZones.remove(zone);
                    limitedZoneAvailability = true;
                } else {
                    //故障率超过99%
                    if (Math.abs(loadPerServer - maxLoadPerServer) < 0.000001d) {
                        // they are the same considering double calculation
                        // round error
                        // 将本 zone 加入到 待移除的zone 中
                        worstZones.add(zone);
                    } else if (loadPerServer > maxLoadPerServer) {
                        maxLoadPerServer = loadPerServer;
                        worstZones.clear();
                        worstZones.add(zone);
                    }
                }
            }
        }

        //不需要 剔除 某个zone
        if (maxLoadPerServer < triggeringLoad && !limitedZoneAvailability) {
            // zone override is not needed here
            return availableZones;
        }
        //这里 从最差的 zone 中随机选择一个 然后剔除
        String zoneToAvoid = randomChooseZone(snapshot, worstZones);
        if (zoneToAvoid != null) {
            availableZones.remove(zoneToAvoid);
        }
        return availableZones;

    }

    public static Set<String> getAvailableZones(LoadBalancerStats lbStats,
            double triggeringLoad, double triggeringBlackoutPercentage) {
        if (lbStats == null) {
            return null;
        }
        Map<String, ZoneSnapshot> snapshot = createSnapshot(lbStats);
        return getAvailableZones(snapshot, triggeringLoad,
                triggeringBlackoutPercentage);
    }

    @Override
    public AbstractServerPredicate getPredicate() {
        return compositePredicate;
    }    
}
