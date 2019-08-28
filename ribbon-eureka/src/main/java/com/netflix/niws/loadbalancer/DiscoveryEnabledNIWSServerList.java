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
package com.netflix.niws.loadbalancer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.client.config.ClientConfigFactory;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey.Keys;
import com.netflix.config.ConfigurationManager;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.loadbalancer.AbstractServerList;
import com.netflix.loadbalancer.DynamicServerListLoadBalancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Provider;

/**
 * The server list class that fetches the server information from Eureka client. ServerList is used by
 * {@link DynamicServerListLoadBalancer} to get server list dynamically.
 *
 * @author stonse
 *      具备自主更新服务的服务列表对象
 */
public class DiscoveryEnabledNIWSServerList extends AbstractServerList<DiscoveryEnabledServer>{

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryEnabledNIWSServerList.class);

    /**
     * 对应的 客户端名
     */
    String clientName;
    /**
     * vip 地址
     */
    String vipAddresses;
    boolean isSecure = false;

    boolean prioritizeVipAddressBasedServers = true;

    /**
     * 数据中心
     */
    String datacenter;
    /**
     * 代表会从哪个region 拉取数据
     */
    String targetRegion;

    /**
     * 获取默认端口
     */
    int overridePort = CommonClientConfigKey.Port.defaultValue();
    boolean shouldUseOverridePort = false;
    boolean shouldUseIpAddr = false;

    /**
     * 内部维护了一个eurekaClient 对象 该对象具备 拉取服务列表的能力
     */
    private final Provider<EurekaClient> eurekaClientProvider;

    /**
     * @deprecated use {@link #DiscoveryEnabledNIWSServerList(String)}
     * or {@link #DiscoveryEnabledNIWSServerList(IClientConfig)}
     */
    @Deprecated
    public DiscoveryEnabledNIWSServerList() {
        this.eurekaClientProvider = new LegacyEurekaClientProvider();
    }

    /**
     * @deprecated
     * use {@link #DiscoveryEnabledNIWSServerList(String, javax.inject.Provider)}
     * @param vipAddresses
     */
    @Deprecated
    public DiscoveryEnabledNIWSServerList(String vipAddresses) {
        this(vipAddresses, new LegacyEurekaClientProvider());
    }

    /**
     * @deprecated
     * use {@link #DiscoveryEnabledNIWSServerList(com.netflix.client.config.IClientConfig, javax.inject.Provider)}
     * @param clientConfig
     */
    @Deprecated
    public DiscoveryEnabledNIWSServerList(IClientConfig clientConfig) {
        this(clientConfig, new LegacyEurekaClientProvider());
    }

    /**
     * @param vipAddresses   vip地址
     * @param eurekaClientProvider
     */
    public DiscoveryEnabledNIWSServerList(String vipAddresses, Provider<EurekaClient> eurekaClientProvider) {
        this(createClientConfig(vipAddresses), eurekaClientProvider);
    }

    /**
     * 通过配置对象 和 具备服务发现的 eurekaClient 对象来初始化
     * @param clientConfig
     * @param eurekaClientProvider
     */
    public DiscoveryEnabledNIWSServerList(IClientConfig clientConfig, Provider<EurekaClient> eurekaClientProvider) {
        this.eurekaClientProvider = eurekaClientProvider;
        initWithNiwsConfig(clientConfig);
    }

    /**
     * 使用传入的config 对象进行初始化
     * @param clientConfig
     */
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        // 从配置中获取 client 的名字
        clientName = clientConfig.getClientName();
        // 获取vip地址
        vipAddresses = clientConfig.resolveDeploymentContextbasedVipAddresses();
        // 如果config 中设置了 没有vip快速失败 直接抛出异常
        if (vipAddresses == null &&
                ConfigurationManager.getConfigInstance().getBoolean("DiscoveryEnabledNIWSServerList.failFastOnNullVip", true)) {
            throw new NullPointerException("VIP address for client " + clientName + " is null");
        }
        // 是否使用https
        isSecure = clientConfig.get(CommonClientConfigKey.IsSecure, false);
        // 是否优先使用 vip地址 默认为true
        prioritizeVipAddressBasedServers = clientConfig.get(CommonClientConfigKey.PrioritizeVipAddressBasedServers, prioritizeVipAddressBasedServers);
        // 获取数据中心
        datacenter = ConfigurationManager.getDeploymentContext().getDeploymentDatacenter();
        // 获取目标地区
        targetRegion = clientConfig.get(CommonClientConfigKey.TargetRegion);

        // 是否使用ip地址
        shouldUseIpAddr = clientConfig.getOrDefault(CommonClientConfigKey.UseIPAddrForServer);

        // override client configuration and use client-defined port
        // 判断是否强制使用 config中的 port
        if (clientConfig.get(CommonClientConfigKey.ForceClientPortConfiguration, false)){
            if (isSecure) {
                // 如果使用https 从config中获取对应的 端口号
                final Integer port = clientConfig.get(CommonClientConfigKey.SecurePort);
                if (port != null) {
                    // 保存被覆盖的port
                    overridePort = port;
                    shouldUseOverridePort = true;
                } else {
                    logger.warn(clientName + " set to force client port but no secure port is set, so ignoring");
                }
            } else {
                final Integer port = clientConfig.get(CommonClientConfigKey.Port);
                if (port != null) {
                    overridePort = port;
                    shouldUseOverridePort = true;
                } else{
                    logger.warn(clientName + " set to force client port but no port is set, so ignoring");
                }
            }
        }
    }

    /**
     * 获取 刚刚初始化的服务对象  通过 eurekaClient 获取 服务列表
     * @return
     */
    @Override
    public List<DiscoveryEnabledServer> getInitialListOfServers(){
        return obtainServersViaDiscovery();
    }

    /**
     * 获取 刚更新的服务对象
     * @return
     */
    @Override
    public List<DiscoveryEnabledServer> getUpdatedListOfServers(){
        return obtainServersViaDiscovery();
    }

    /**
     * 获取刚 初始化/更新 的服务对象  如果不存在 vipAddress 会返回空列表
     * @return
     */
    private List<DiscoveryEnabledServer> obtainServersViaDiscovery() {
        List<DiscoveryEnabledServer> serverList = new ArrayList<DiscoveryEnabledServer>();

        // 不存在 eurekaClient 的情况下直接返回空的服务列表
        if (eurekaClientProvider == null || eurekaClientProvider.get() == null) {
            logger.warn("EurekaClient has not been initialized yet, returning an empty list");
            return new ArrayList<DiscoveryEnabledServer>();
        }

        //从提供者中获取eurekaClient对象
        EurekaClient eurekaClient = eurekaClientProvider.get();
        //如果vip 地址不为空
        if (vipAddresses!=null){
            for (String vipAddress : vipAddresses.split(",")) {
                // if targetRegion is null, it will be interpreted as the same region of client
                // 通过eurekaClient 去获取 eurekaServer 上的 服务实例对象
                // region 指定了获取的服务列表 因为每个 eurekaClient 会将所有的 服务实例都拉取到本地 并按照 本Region 和 其他Region 分开存放 vipAddress 指定了提供服务的地址
                // 因为在 同一机器上 不同的端口 可以开启不同的app
                List<InstanceInfo> listOfInstanceInfo = eurekaClient.getInstancesByVipAddress(vipAddress, isSecure, targetRegion);
                for (InstanceInfo ii : listOfInstanceInfo) {
                    //必须确保 服务实例是启动的
                    if (ii.getStatus().equals(InstanceStatus.UP)) {

                        //如果需要重写 port 在下面会重新构建一个 instanceInfo 对象
                        if(shouldUseOverridePort){
                            if(logger.isDebugEnabled()){
                                logger.debug("Overriding port on client name: " + clientName + " to " + overridePort);
                            }

                            // copy is necessary since the InstanceInfo builder just uses the original reference,
                            // and we don't want to corrupt the global eureka copy of the object which may be
                            // used by other clients in our system
                            // 生成一个副本对象
                            InstanceInfo copy = new InstanceInfo(ii);

                            // 更新端口
                            if(isSecure){
                                ii = new InstanceInfo.Builder(copy).setSecurePort(overridePort).build();
                            }else{
                                ii = new InstanceInfo.Builder(copy).setPort(overridePort).build();
                            }
                        }

                        // 将eureka的 instanceInfo 适配成 ribbon的 Server
                        DiscoveryEnabledServer des = createServer(ii, isSecure, shouldUseIpAddr);
                        serverList.add(des);
                    }
                }
                // 一旦有某个vip地址能够获取到服务了就直接 返回
                if (serverList.size()>0 && prioritizeVipAddressBasedServers){
                    break; // if the current vipAddress has servers, we dont use subsequent vipAddress based servers
                }
            }
        }
        // 没有设置 vip地址的情况下 返回空列表
        return serverList;
    }

    /**
     * 将 eureka的 instanceInfo 封装成 ribbon的 服务类
     * @param instanceInfo
     * @param useSecurePort
     * @param useIpAddr
     * @return
     */
    protected DiscoveryEnabledServer createServer(final InstanceInfo instanceInfo, boolean useSecurePort, boolean useIpAddr) {
        DiscoveryEnabledServer server = new DiscoveryEnabledServer(instanceInfo, useSecurePort, useIpAddr);

        // Get availabilty zone for this instance.
        EurekaClientConfig clientConfig = eurekaClientProvider.get().getEurekaClientConfig();
        String[] availZones = clientConfig.getAvailabilityZones(clientConfig.getRegion());
        // 默认情况就是返回第一个zone
        String instanceZone = InstanceInfo.getZone(availZones, instanceInfo);
        // 设置 server的zone
        server.setZone(instanceZone);

        return server;
    }

    public String getVipAddresses() {
        return vipAddresses;
    }

    public void setVipAddresses(String vipAddresses) {
        this.vipAddresses = vipAddresses;
    }

    public String toString(){
        StringBuilder sb = new StringBuilder("DiscoveryEnabledNIWSServerList:");
        sb.append("; clientName:").append(clientName);
        sb.append("; Effective vipAddresses:").append(vipAddresses);
        sb.append("; isSecure:").append(isSecure);
        sb.append("; datacenter:").append(datacenter);
        return sb.toString();
    }


    /**
     * 创建一个使用该 vip地址的 config 对象
     * @param vipAddresses
     * @return
     */
    private static IClientConfig createClientConfig(String vipAddresses) {
        IClientConfig clientConfig = ClientConfigFactory.DEFAULT.newConfig();
        clientConfig.set(Keys.DeploymentContextBasedVipAddresses, vipAddresses);
        return clientConfig;
    }
}
