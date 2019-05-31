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

import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** 
 * Rule that use the average/percentile response times
 * to assign dynamic "weights" per Server which is then used in 
 * the "Weighted Round Robin" fashion. 
 * <p>
 * The basic idea for weighted round robin has been obtained from JCS
 * The implementation for choosing the endpoint from the list of endpoints
 * is as follows:Let's assume 4 endpoints:A(wt=10), B(wt=30), C(wt=40), 
 * D(wt=20). 
 * <p>
 * Using the Random API, generate a random number between 1 and10+30+40+20.
 * Let's assume that the above list is randomized. Based on the weights, we
 * have intervals as follows:
 * <p>
 * 1-----10 (A's weight)
 * <br>
 * 11----40 (A's weight + B's weight)
 * <br>
 * 41----80 (A's weight + B's weight + C's weight)
 * <br>
 * 81----100(A's weight + B's weight + C's weight + C's weight)
 * <p>
 * Here's the psuedo code for deciding where to send the request:
 * <p>
 * if (random_number between 1 &amp; 10) {send request to A;}
 * <br>
 * else if (random_number between 11 &amp; 40) {send request to B;}
 * <br>
 * else if (random_number between 41 &amp; 80) {send request to C;}
 * <br>
 * else if (random_number between 81 &amp; 100) {send request to D;}
 * <p>
 * When there is not enough statistics gathered for the servers, this rule
 * will fall back to use {@link RoundRobinRule}. 
 * @author stonse
 *      根据权重 选择合适的Rule 对象  该类继承的是 RoundRobinRule
 */
public class WeightedResponseTimeRule extends RoundRobinRule {

    public static final IClientConfigKey<Integer> WEIGHT_TASK_TIMER_INTERVAL_CONFIG_KEY = new IClientConfigKey<Integer>() {
        @Override
        public String key() {
            return "ServerWeightTaskTimerInterval";
        }
        
        @Override
        public String toString() {
            return key();
        }

        @Override
        public Class<Integer> type() {
            return Integer.class;
        }
    };

    /**
     * 默认的时间间隔
     */
    public static final int DEFAULT_TIMER_INTERVAL = 30 * 1000;
    
    private int serverWeightTaskTimerInterval = DEFAULT_TIMER_INTERVAL;

    private static final Logger logger = LoggerFactory.getLogger(WeightedResponseTimeRule.class);
    
    // holds the accumulated weight from index 0 to current index
    // for example, element at index 2 holds the sum of weight of servers from 0 to 2
    // 累积权重对象
    private volatile List<Double> accumulatedWeights = new ArrayList<Double>();
    

    private final Random random = new Random();

    protected Timer serverWeightTimer = null;

    protected AtomicBoolean serverWeightAssignmentInProgress = new AtomicBoolean(false);

    String name = "unknown";

    public WeightedResponseTimeRule() {
        super();
    }

    public WeightedResponseTimeRule(ILoadBalancer lb) {
        super(lb);
    }
    
    @Override
    public void setLoadBalancer(ILoadBalancer lb) {
        super.setLoadBalancer(lb);
        if (lb instanceof BaseLoadBalancer) {
            name = ((BaseLoadBalancer) lb).getName();
        }
        //初始化方法
        initialize(lb);
    }

    void initialize(ILoadBalancer lb) {
        //每次执行初始化前 先关闭之前的Timer 对象
        if (serverWeightTimer != null) {
            serverWeightTimer.cancel();
        }
        //生成一个新的Timer 对象
        serverWeightTimer = new Timer("NFLoadBalancer-serverWeightTimer-"
                + name, true);

        //这里 无论是哪个 ServerWeight 对象 核心都是 会更新维护记录了 权重信息的列表对象
        //执行定时任务  这个定时任务 是每次都会更新最新的 服务耗时 用于计算权重信息
        serverWeightTimer.schedule(new DynamicServerWeightTask(), 0,
                serverWeightTaskTimerInterval);
        // do a initial run
        ServerWeight sw = new ServerWeight();
        // 记录每个服务当前的权重信息
        sw.maintainWeights();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                logger
                        .info("Stopping NFLoadBalancer-serverWeightTimer-"
                                + name);
                //增加钩子 用于关闭定时任务
                serverWeightTimer.cancel();
            }
        }));
    }

    public void shutdown() {
        if (serverWeightTimer != null) {
            logger.info("Stopping NFLoadBalancer-serverWeightTimer-" + name);
            serverWeightTimer.cancel();
        }
    }

    List<Double> getAccumulatedWeights() {
        return Collections.unmodifiableList(accumulatedWeights);
    }

    /**
     * 根据权重选择合适服务
     * @param lb
     * @param key
     * @return
     */
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE")
    @Override
    public Server choose(ILoadBalancer lb, Object key) {
        if (lb == null) {
            return null;
        }
        Server server = null;

        while (server == null) {
            // get hold of the current reference in case it is changed from the other thread
            // 获取权重列表
            List<Double> currentWeights = accumulatedWeights;
            if (Thread.interrupted()) {
                return null;
            }
            //获取当前所有服务
            List<Server> allList = lb.getAllServers();

            int serverCount = allList.size();

            if (serverCount == 0) {
                return null;
            }

            int serverIndex = 0;

            // last one in the list is the sum of all weights
            // 列表中每个元素 都是 之前权重 + 当前服务的权重总和
            double maxTotalWeight = currentWeights.size() == 0 ? 0 : currentWeights.get(currentWeights.size() - 1); 
            // No server has been hit yet and total weight is not initialized
            // fallback to use round robin
            // 如果权重 过低 或者 数量出现问题 就回退到 轮询的 rule
            if (maxTotalWeight < 0.001d || serverCount != currentWeights.size()) {
                server =  super.choose(getLoadBalancer(), key);
                if(server == null) {
                    return server;
                }
            } else {
                // generate a random weight between 0 (inclusive) to maxTotalWeight (exclusive)
                // 获取一个随机权重值
                double randomWeight = random.nextDouble() * maxTotalWeight;
                // pick the server index based on the randomIndex
                int n = 0;
                // 找到权重范围内对应的server 对象
                for (Double d : currentWeights) {
                    if (d >= randomWeight) {
                        serverIndex = n;
                        break;
                    } else {
                        n++;
                    }
                }

                server = allList.get(serverIndex);
            }

            if (server == null) {
                /* Transient. */
                //尝试让出 cpu 分片并进入下次循环
                Thread.yield();
                continue;
            }

            if (server.isAlive()) {
                return (server);
            }

            // Next.
            server = null;
        }
        return server;
    }

    /**
     * 动态计算权重对象
     */
    class DynamicServerWeightTask extends TimerTask {
        public void run() {
            ServerWeight serverWeight = new ServerWeight();
            try {
                serverWeight.maintainWeights();
            } catch (Exception e) {
                logger.error("Error running DynamicServerWeightTask for {}", name, e);
            }
        }
    }

    /**
     * 服务权重对象
     */
    class ServerWeight {

        //维护权重
        public void maintainWeights() {
            //获取均衡负载对象
            ILoadBalancer lb = getLoadBalancer();
            if (lb == null) {
                return;
            }

            //尝试设置标识为true 失败情况下代表其他线程正在处理 就放弃
            if (!serverWeightAssignmentInProgress.compareAndSet(false,  true))  {
                return; 
            }
            
            try {
                logger.info("Weight adjusting job started");
                AbstractLoadBalancer nlb = (AbstractLoadBalancer) lb;
                //获取均衡负载 统计信息
                LoadBalancerStats stats = nlb.getLoadBalancerStats();
                if (stats == null) {
                    // no statistics, nothing to do
                    return;
                }
                //存在 统计信息的情况下 获取每个统计信息 记录的服务
                double totalResponseTime = 0;
                // find maximal 95% response time
                for (Server server : nlb.getAllServers()) {
                    // this will automatically load the stats if not in cache
                    ServerStats ss = stats.getSingleServerStat(server);
                    //记录总的响应时间
                    totalResponseTime += ss.getResponseTimeAvg();
                }
                // weight for each server is (sum of responseTime of all servers - responseTime)
                // so that the longer the response time, the less the weight and the less likely to be chosen
                Double weightSoFar = 0.0;
                
                // create new list and hot swap the reference
                List<Double> finalWeights = new ArrayList<Double>();
                for (Server server : nlb.getAllServers()) {
                    ServerStats ss = stats.getSingleServerStat(server);
                    //总响应时间 - 平均响应时间 获取到就是该 服务调用所消耗的时间权重
                    double weight = totalResponseTime - ss.getResponseTimeAvg();
                    weightSoFar += weight;
                    //将每个服务对应的 响应时间设置进去
                    finalWeights.add(weightSoFar);   
                }
                setWeights(finalWeights);
            } catch (Exception e) {
                logger.error("Error calculating server weights", e);
            } finally {
                serverWeightAssignmentInProgress.set(false);
            }

        }
    }

    /**
     * 设置权重列表
     * @param weights
     */
    void setWeights(List<Double> weights) {
        this.accumulatedWeights = weights;
    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        super.initWithNiwsConfig(clientConfig);
        serverWeightTaskTimerInterval = clientConfig.get(WEIGHT_TASK_TIMER_INTERVAL_CONFIG_KEY, DEFAULT_TIMER_INTERVAL);
    }

}
