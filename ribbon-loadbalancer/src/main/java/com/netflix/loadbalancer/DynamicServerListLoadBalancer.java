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
import com.netflix.client.ClientFactory;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A LoadBalancer that has the capabilities to obtain the candidate list of
 * servers using a dynamic source. i.e. The list of servers can potentially be
 * changed at Runtime. It also contains facilities wherein the list of servers
 * can be passed through a Filter criteria to filter out servers that do not
 * meet the desired criteria.
 * 
 * @author stonse
 *      拓展于 基本的均衡负载对象
 *      该对象应该是会自动在合适的时机调用 addServer 对象
 */
public class DynamicServerListLoadBalancer<T extends Server> extends BaseLoadBalancer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicServerListLoadBalancer.class);

    boolean isSecure = false;
    boolean useTunnel = false;

    // to keep track of modification of server lists 列表当前是否属于正常状态
    protected AtomicBoolean serverListUpdateInProgress = new AtomicBoolean(false);

    /**
     * 服务实现列表对象 使用易变修饰符 修饰
     */
    volatile ServerList<T> serverListImpl;

    volatile ServerListFilter<T> filter;

    /**
     * 该对象 负责实现对于服务实例的更新工作
     */
    protected final ServerListUpdater.UpdateAction updateAction = new ServerListUpdater.UpdateAction() {
        @Override
        public void doUpdate() {
            updateListOfServers();
        }
    };

    /**
     * 定义了更新服务的接口方法
     */
    protected volatile ServerListUpdater serverListUpdater;

    public DynamicServerListLoadBalancer() {
        //父类构造方法 设置默认的均衡负载规则(roundrobin) 以及 开启一个定时任务 会定时ping 当前的服务列表 并对无效的服务进行下线
        //todo 还没有看ping具体是如何实现 预测是 访问看结果是否是 404 之类的吧
        super();
    }

    @Deprecated
    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping, 
            ServerList<T> serverList, ServerListFilter<T> filter) {
        this(
                clientConfig,
                rule,
                ping,
                serverList,
                filter,
                new PollingServerListUpdater()
        );
    }

    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping,
                                         ServerList<T> serverList, ServerListFilter<T> filter,
                                         ServerListUpdater serverListUpdater) {
        super(clientConfig, rule, ping);
        this.serverListImpl = serverList;
        this.filter = filter;
        this.serverListUpdater = serverListUpdater;
        if (filter instanceof AbstractServerListFilter) {
            ((AbstractServerListFilter) filter).setLoadBalancerStats(getLoadBalancerStats());
        }
        restOfInit(clientConfig);
    }

    public DynamicServerListLoadBalancer(IClientConfig clientConfig) {
        initWithNiwsConfig(clientConfig);
    }
    
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        this.initWithNiwsConfig(clientConfig, ClientFactory::instantiateInstanceWithClientConfig);

    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig, Factory factory) {
        try {
            super.initWithNiwsConfig(clientConfig, factory);
            String niwsServerListClassName = clientConfig.getOrDefault(CommonClientConfigKey.NIWSServerListClassName);

            ServerList<T> niwsServerListImpl = (ServerList<T>) factory.create(niwsServerListClassName, clientConfig);
            this.serverListImpl = niwsServerListImpl;

            if (niwsServerListImpl instanceof AbstractServerList) {
                AbstractServerListFilter<T> niwsFilter = ((AbstractServerList) niwsServerListImpl)
                        .getFilterImpl(clientConfig);
                niwsFilter.setLoadBalancerStats(getLoadBalancerStats());
                this.filter = niwsFilter;
            }

            String serverListUpdaterClassName = clientConfig.getOrDefault(
                    CommonClientConfigKey.ServerListUpdaterClassName);

            // 这里通过update 类的名字 来生成对象
            this.serverListUpdater = (ServerListUpdater) factory.create(serverListUpdaterClassName, clientConfig);

            restOfInit(clientConfig);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Exception while initializing NIWSDiscoveryLoadBalancer:"
                            + clientConfig.getClientName()
                            + ", niwsClientConfig:" + clientConfig, e);
        }
    }

    void restOfInit(IClientConfig clientConfig) {
        boolean primeConnection = this.isEnablePrimingConnections();
        // turn this off to avoid duplicated asynchronous priming done in BaseLoadBalancer.setServerList()
        this.setEnablePrimingConnections(false);
        enableAndInitLearnNewServersFeature();

        updateListOfServers();
        if (primeConnection && this.getPrimeConnections() != null) {
            this.getPrimeConnections()
                    .primeConnections(getReachableServers());
        }
        this.setEnablePrimingConnections(primeConnection);
        LOGGER.info("DynamicServerListLoadBalancer for client {} initialized: {}", clientConfig.getClientName(), this.toString());
    }


    /**
     * 设置ServerList 对象
     * @param lsrv
     */
    @Override
    public void setServersList(List lsrv) {
        //父类 该方法就是将 servetList 设置到 allServers 中
        super.setServersList(lsrv);
        List<T> serverList = (List<T>) lsrv;
        Map<String, List<Server>> serversInZones = new HashMap<String, List<Server>>();
        for (Server server : serverList) {
            // make sure ServerStats is created to avoid creating them on hot
            // path
            // 获取每个服务的统计信息
            getLoadBalancerStats().getSingleServerStat(server);
            // 获取每个服务的区域信息
            String zone = server.getZone();
            if (zone != null) {
                zone = zone.toLowerCase();
                //根据 zone 查询 该区域下所有的server 对象
                List<Server> servers = serversInZones.get(zone);
                if (servers == null) {
                    servers = new ArrayList<Server>();
                    serversInZones.put(zone, servers);
                }
                //往 zone 中插入新的服务实例
                servers.add(server);
            }
        }
        //更新映射容器
        setServerListForZones(serversInZones);
    }

    /**
     * 为统计对象 更新对应的 map 该mapkey 为 zone value是 该zone 下所有的server
     * @param zoneServersMap
     */
    protected void setServerListForZones(
            Map<String, List<Server>> zoneServersMap) {
        LOGGER.debug("Setting server list for zones: {}", zoneServersMap);
        getLoadBalancerStats().updateZoneServerMapping(zoneServersMap);
    }

    public ServerList<T> getServerListImpl() {
        return serverListImpl;
    }

    public void setServerListImpl(ServerList<T> niwsServerList) {
        this.serverListImpl = niwsServerList;
    }

    public ServerListFilter<T> getFilter() {
        return filter;
    }

    public void setFilter(ServerListFilter<T> filter) {
        this.filter = filter;
    }

    public ServerListUpdater getServerListUpdater() {
        return serverListUpdater;
    }

    public void setServerListUpdater(ServerListUpdater serverListUpdater) {
        this.serverListUpdater = serverListUpdater;
    }

    @Override
    /**
     * Makes no sense to ping an inmemory disc client
     * 
     */
    public void forceQuickPing() {
        // no-op
    }

    /**
     * Feature that lets us add new instances (from AMIs) to the list of
     * existing servers that the LB will use Call this method if you want this
     * feature enabled
     */
    public void enableAndInitLearnNewServersFeature() {
        LOGGER.info("Using serverListUpdater {}", serverListUpdater.getClass().getSimpleName());
        //这里传入了更新的动作 看来无论是 哪个updater 实现使用的action 都是一样的
        serverListUpdater.start(updateAction);
    }

    private String getIdentifier() {
        return this.getClientConfig().getClientName();
    }

    public void stopServerListRefreshing() {
        if (serverListUpdater != null) {
            serverListUpdater.stop();
        }
    }

    /**
     * 执行更新的实际操作
     */
    @VisibleForTesting
    public void updateListOfServers() {
        List<T> servers = new ArrayList<T>();
        //服务列表
        if (serverListImpl != null) {
            //通过eureka 获取最新的 服务列表
            servers = serverListImpl.getUpdatedListOfServers();
            LOGGER.debug("List of Servers for {} obtained from Discovery client: {}",
                    getIdentifier(), servers);

            //如果存在过滤器的话 进行过滤
            if (filter != null) {
                servers = filter.getFilteredListOfServers(servers);
                LOGGER.debug("Filtered List of Servers for {} obtained from Discovery client: {}",
                        getIdentifier(), servers);
            }
        }
        //更新allServer 列表
        updateAllServerList(servers);
    }

    /**
     * Update the AllServer list in the LoadBalancer if necessary and enabled
     * 
     * @param ls
     *      更新allServer 服务列表
     */
    protected void updateAllServerList(List<T> ls) {
        // other threads might be doing this - in which case, we pass
        if (serverListUpdateInProgress.compareAndSet(false, true)) {
            try {
                //将拉取到的全部服务都设置成 alive
                for (T s : ls) {
                    s.setAlive(true); // set so that clients can start using these
                                      // servers right away instead
                                      // of having to wait out the ping cycle.
                }
                //将结果设置到 serverList 中
                setServersList(ls);
                //强制 ping 目前是 noop
                super.forceQuickPing();
            } finally {
                serverListUpdateInProgress.set(false);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DynamicServerListLoadBalancer:");
        sb.append(super.toString());
        sb.append("ServerList:" + String.valueOf(serverListImpl));
        return sb.toString();
    }
    
    @Override 
    public void shutdown() {
        super.shutdown();
        stopServerListRefreshing();
    }


    @Monitor(name="LastUpdated", type=DataSourceType.INFORMATIONAL)
    public String getLastUpdate() {
        return serverListUpdater.getLastUpdate();
    }

    @Monitor(name="DurationSinceLastUpdateMs", type= DataSourceType.GAUGE)
    public long getDurationSinceLastUpdateMs() {
        return serverListUpdater.getDurationSinceLastUpdateMs();
    }

    @Monitor(name="NumUpdateCyclesMissed", type=DataSourceType.GAUGE)
    public int getNumberMissedCycles() {
        return serverListUpdater.getNumberMissedCycles();
    }

    @Monitor(name="NumThreads", type=DataSourceType.GAUGE)
    public int getCoreThreads() {
        return serverListUpdater.getCoreThreads();
    }
}
