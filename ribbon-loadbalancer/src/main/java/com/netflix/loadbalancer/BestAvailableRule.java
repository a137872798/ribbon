/*
 *
 * Copyright 2014 Netflix, Inc.
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

import java.util.List;

/**
 * A rule that skips servers with "tripped" circuit breaker and picks the
 * server with lowest concurrent requests.
 * <p>
 * This rule should typically work with {@link ServerListSubsetFilter} which puts a limit on the 
 * servers that is visible to the rule. This ensure that it only needs to find the minimal 
 * concurrent requests among a small number of servers. Also, each client will get a random list of 
 * servers which avoids the problem that one server with the lowest concurrent requests is 
 * chosen by a large number of clients and immediately gets overwhelmed.
 * 
 * @author awang
 *      该规则对象 会先过滤掉失效的 服务 并优先选择请求数最小的那个
 */
public class BestAvailableRule extends ClientConfigEnabledRoundRobinRule {

    private LoadBalancerStats loadBalancerStats;
    
    @Override
    public Server choose(Object key) {
        //当统计对象不存在的时候 使用父类的 方法
        if (loadBalancerStats == null) {
            return super.choose(key);
        }
        //获取所有可用服务对象
        List<Server> serverList = getLoadBalancer().getAllServers();
        int minimalConcurrentConnections = Integer.MAX_VALUE;
        long currentTime = System.currentTimeMillis();
        Server chosen = null;
        for (Server server: serverList) {
            //获取每个服务对应的 统计信息
            ServerStats serverStats = loadBalancerStats.getSingleServerStat(server);
            // circuitBreaker 代表是否 触发了断路 如果没有触发的情况下 之后根据 请求数量 获取对应的服务
            if (!serverStats.isCircuitBreakerTripped(currentTime)) {
                //获取总请求次数
                int concurrentConnections = serverStats.getActiveRequestsCount(currentTime);
                if (concurrentConnections < minimalConcurrentConnections) {
                    minimalConcurrentConnections = concurrentConnections;
                    chosen = server;
                }
            }
        }
        if (chosen == null) {
            return super.choose(key);
        } else {
            return chosen;
        }
    }

    @Override
    public void setLoadBalancer(ILoadBalancer lb) {
        super.setLoadBalancer(lb);
        if (lb instanceof AbstractLoadBalancer) {
            loadBalancerStats = ((AbstractLoadBalancer) lb).getLoadBalancerStats();            
        }
    }
    
    

}
