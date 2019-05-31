/**
 * Copyright 2013 Netflix, Inc.
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
package com.netflix.loadbalancer;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.netflix.client.IClientConfigAware;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey;
import com.netflix.client.config.Property;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A server list filter that limits the number of the servers used by the load balancer to be the subset of all servers.
 * This is useful if the server farm is large (e.g., in the hundreds) and making use of every one of them
 * and keeping the connections in http client's connection pool is unnecessary. It also has the capability of eviction 
 * of relatively unhealthy servers by comparing the total network failures and concurrent connections. 
 *  
 * @author awang
 *
 * @param <T>
 *      该对象能通过比较服务实例的通信失败数量 和 并发连接数 有选择性的剔除掉 不健康的服务实例
 */
public class ServerListSubsetFilter<T extends Server> extends ZoneAffinityServerListFilter<T> implements IClientConfigAware, Comparator<T>{

    private Random random = new Random();
    private volatile Set<T> currentSubset = Sets.newHashSet(); 
    private Property<Integer> sizeProp;
    private Property<Float> eliminationPercent;
    private Property<Integer> eliminationFailureCountThreshold;
    private Property<Integer> eliminationConnectionCountThreshold;

    private static final IClientConfigKey<Integer> SIZE = new CommonClientConfigKey<Integer>("ServerListSubsetFilter.size", 20) {};
    private static final IClientConfigKey<Float> FORCE_ELIMINATE_PERCENT = new CommonClientConfigKey<Float>("ServerListSubsetFilter.forceEliminatePercent", 0.1f) {};
    private static final IClientConfigKey<Integer> ELIMINATION_FAILURE_THRESHOLD = new CommonClientConfigKey<Integer>("ServerListSubsetFilter.eliminationFailureThresold", 0) {};
    private static final IClientConfigKey<Integer> ELIMINATION_CONNECTION_THRESHOLD = new CommonClientConfigKey<Integer>("ServerListSubsetFilter.eliminationConnectionThresold", 0) {};

    /**
     * @deprecated ServerListSubsetFilter should only be created with an IClientConfig.  See {@link ServerListSubsetFilter#ServerListSubsetFilter(IClientConfig)}
     */
    @Deprecated
    public ServerListSubsetFilter() {
        sizeProp = Property.of(SIZE.defaultValue());
        eliminationPercent = Property.of(FORCE_ELIMINATE_PERCENT.defaultValue());
        eliminationFailureCountThreshold = Property.of(ELIMINATION_FAILURE_THRESHOLD.defaultValue());
        eliminationConnectionCountThreshold = Property.of(ELIMINATION_CONNECTION_THRESHOLD.defaultValue());
    }

    public ServerListSubsetFilter(IClientConfig clientConfig) {
        super(clientConfig);

        initWithNiwsConfig(clientConfig);
    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        sizeProp = clientConfig.getDynamicProperty(SIZE);
        eliminationPercent = clientConfig.getDynamicProperty(FORCE_ELIMINATE_PERCENT);
        eliminationFailureCountThreshold = clientConfig.getDynamicProperty(ELIMINATION_FAILURE_THRESHOLD);
        eliminationConnectionCountThreshold = clientConfig.getDynamicProperty(ELIMINATION_CONNECTION_THRESHOLD);
    }

    /**
     * Given all the servers, keep only a stable subset of servers to use. This method
     * keeps the current list of subset in use and keep returning the same list, with exceptions
     * to relatively unhealthy servers, which are defined as the following:
     * <p>
     * <ul>
     * <li>Servers with their concurrent connection count exceeding the client configuration for 
     *  {@code <clientName>.<nameSpace>.ServerListSubsetFilter.eliminationConnectionThresold} (default is 0)
     * <li>Servers with their failure count exceeding the client configuration for 
     *  {@code <clientName>.<nameSpace>.ServerListSubsetFilter.eliminationFailureThresold}  (default is 0)
     *  <li>If the servers evicted above is less than the forced eviction percentage as defined by client configuration
     *   {@code <clientName>.<nameSpace>.ServerListSubsetFilter.forceEliminatePercent} (default is 10%, or 0.1), the
     *   remaining servers will be sorted by their health status and servers will worst health status will be
     *   forced evicted.
     * </ul>
     * <p>
     * After the elimination, new servers will be randomly chosen from all servers pool to keep the
     * number of the subset unchanged. 
     *      该方法重写父类的 服务过滤功能 并且还会剔除不够健康的服务实例
     */
    @Override
    public List<T> getFilteredListOfServers(List<T> servers) {
        //获取 上层过滤后的 结果 这里可能已经经过zone 进行过滤了 也可能没过滤 没过滤的情况是因为zone 中有大量实例是不健康
        List<T> zoneAffinityFiltered = super.getFilteredListOfServers(servers);
        Set<T> candidates = Sets.newHashSet(zoneAffinityFiltered);
        //默认一开始是空容器  这个应该是每次过滤后 都会将结果保留在容器中
        Set<T> newSubSet = Sets.newHashSet(currentSubset);
        //获取 统计对象
        LoadBalancerStats lbStats = getLoadBalancerStats();
        for (T server: currentSubset) {
            // this server is either down or out of service
            //发现了 下线的 服务就剔除掉
            if (!candidates.contains(server)) {
                newSubSet.remove(server);
            } else {
                //这里是 2方服务都存在的情况
                //获取某个服务的 统计信息 并根据 条件 剔除无效的服务
                ServerStats stats = lbStats.getSingleServerStat(server);
                // remove the servers that do not meet health criteria
                // 如果并发请求数 超过预期值 或者 失败次数 超过预期值 将 该服务实例从容器中移除
                if (stats.getActiveRequestsCount() > eliminationConnectionCountThreshold.getOrDefault()
                        || stats.getFailureCount() > eliminationFailureCountThreshold.getOrDefault()) {
                    newSubSet.remove(server);
                    // also remove from the general pool to avoid selecting them again
                    candidates.remove(server);
                }
            }
        }
        //这里是 强制剔除的数量大小
        int targetedListSize = sizeProp.getOrDefault();
        //获取本次 被剔除的服务对象
        int numEliminated = currentSubset.size() - newSubSet.size();
        //获取 最小的剔除数量
        int minElimination = (int) (targetedListSize * eliminationPercent.getOrDefault());
        int numToForceEliminate = 0;
        if (targetedListSize < newSubSet.size()) {
            // size is shrinking
            numToForceEliminate = newSubSet.size() - targetedListSize;
            //如果最小剔除数 大于 本次被剔除的服务对象
        } else if (minElimination > numEliminated) {
            //那么多余的服务要被强制剔除
            numToForceEliminate = minElimination - numEliminated; 
        }
        
        if (numToForceEliminate > newSubSet.size()) {
            numToForceEliminate = newSubSet.size();
        }

        if (numToForceEliminate > 0) {
            List<T> sortedSubSet = Lists.newArrayList(newSubSet);           
            Collections.sort(sortedSubSet, this);
            List<T> forceEliminated = sortedSubSet.subList(0, numToForceEliminate);
            newSubSet.removeAll(forceEliminated);
            candidates.removeAll(forceEliminated);
        }
        
        // after forced elimination or elimination of unhealthy instances,
        // the size of the set may be less than the targeted size,
        // then we just randomly add servers from the big pool
        // 当剔除后 可能实例数量 小于目标值 那么就需要从池中获取新的服务实例
        if (newSubSet.size() < targetedListSize) {
            int numToChoose = targetedListSize - newSubSet.size();
            candidates.removeAll(newSubSet);
            if (numToChoose > candidates.size()) {
                // Not enough healthy instances to choose, fallback to use the
                // total server pool
                candidates = Sets.newHashSet(zoneAffinityFiltered);
                candidates.removeAll(newSubSet);
            }
            List<T> chosen = randomChoose(Lists.newArrayList(candidates), numToChoose);
            for (T server: chosen) {
                newSubSet.add(server);
            }
        }
        //这里 更新当前 服务列表
        currentSubset = newSubSet;       
        return Lists.newArrayList(newSubSet);            
    }

    /**
     * Randomly shuffle the beginning portion of server list (according to the number passed into the method) 
     * and return them.
     *  
     * @param servers
     * @param toChoose
     * @return
     */
    private List<T> randomChoose(List<T> servers, int toChoose) {
        int size = servers.size();
        if (toChoose >= size || toChoose < 0) {
            return servers;
        } 
        for (int i = 0; i < toChoose; i++) {
            int index = random.nextInt(size);
            T tmp = servers.get(index);
            servers.set(index, servers.get(i));
            servers.set(i, tmp);
        }
        return servers.subList(0, toChoose);        
    }

    /**
     * Function to sort the list by server health condition, with
     * unhealthy servers before healthy servers. The servers are first sorted by
     * failures count, and then concurrent connection count.
     */
    @Override
    public int compare(T server1, T server2) {
        LoadBalancerStats lbStats = getLoadBalancerStats();
        ServerStats stats1 = lbStats.getSingleServerStat(server1);
        ServerStats stats2 = lbStats.getSingleServerStat(server2);
        int failuresDiff = (int) (stats2.getFailureCount() - stats1.getFailureCount());
        if (failuresDiff != 0) {
            return failuresDiff;
        } else {
            return (stats2.getActiveRequestsCount() - stats1.getActiveRequestsCount());
        }
    }
}
