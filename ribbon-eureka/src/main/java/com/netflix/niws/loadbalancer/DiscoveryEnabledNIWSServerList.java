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

    String datacenter;
    String targetRegion;

    int overridePort = CommonClientConfigKey.Port.defaultValue();
    boolean shouldUseOverridePort = false;
    boolean shouldUseIpAddr = false;

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

    public DiscoveryEnabledNIWSServerList(String vipAddresses, Provider<EurekaClient> eurekaClientProvider) {
        this(createClientConfig(vipAddresses), eurekaClientProvider);
    }

    public DiscoveryEnabledNIWSServerList(IClientConfig clientConfig, Provider<EurekaClient> eurekaClientProvider) {
        this.eurekaClientProvider = eurekaClientProvider;
        initWithNiwsConfig(clientConfig);
    }

    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        clientName = clientConfig.getClientName();
        vipAddresses = clientConfig.resolveDeploymentContextbasedVipAddresses();
        if (vipAddresses == null &&
                ConfigurationManager.getConfigInstance().getBoolean("DiscoveryEnabledNIWSServerList.failFastOnNullVip", true)) {
            throw new NullPointerException("VIP address for client " + clientName + " is null");
        }
        isSecure = clientConfig.get(CommonClientConfigKey.IsSecure, false);
        prioritizeVipAddressBasedServers = clientConfig.get(CommonClientConfigKey.PrioritizeVipAddressBasedServers, prioritizeVipAddressBasedServers);
        datacenter = ConfigurationManager.getDeploymentContext().getDeploymentDatacenter();
        targetRegion = clientConfig.get(CommonClientConfigKey.TargetRegion);

        shouldUseIpAddr = clientConfig.getOrDefault(CommonClientConfigKey.UseIPAddrForServer);

        // override client configuration and use client-defined port
        if (clientConfig.get(CommonClientConfigKey.ForceClientPortConfiguration, false)){
            if (isSecure) {
                final Integer port = clientConfig.get(CommonClientConfigKey.SecurePort);
                if (port != null) {
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
     * 获取 刚刚初始化的服务对象
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

        if (eurekaClientProvider == null || eurekaClientProvider.get() == null) {
            logger.warn("EurekaClient has not been initialized yet, returning an empty list");
            return new ArrayList<DiscoveryEnabledServer>();
        }

        //从提供者中获取eurekaClient 对象 provider 是 javax 的某种规范 类似与 spring 的 beanInject
        EurekaClient eurekaClient = eurekaClientProvider.get();
        //如果vip 地址不为空
        if (vipAddresses!=null){
            for (String vipAddress : vipAddresses.split(",")) {
                // if targetRegion is null, it will be interpreted as the same region of client
                // 通过eurekaClient 去获取 eurekaServer 上的 服务实例对象
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
                            InstanceInfo copy = new InstanceInfo(ii);

                            if(isSecure){
                                ii = new InstanceInfo.Builder(copy).setSecurePort(overridePort).build();
                            }else{
                                ii = new InstanceInfo.Builder(copy).setPort(overridePort).build();
                            }
                        }

                        //将 instanceInfo 对象还原成 server 对象并返回
                        DiscoveryEnabledServer des = createServer(ii, isSecure, shouldUseIpAddr);
                        serverList.add(des);
                    }
                }
                if (serverList.size()>0 && prioritizeVipAddressBasedServers){
                    break; // if the current vipAddress has servers, we dont use subsequent vipAddress based servers
                }
            }
        }
        return serverList;
    }

    protected DiscoveryEnabledServer createServer(final InstanceInfo instanceInfo, boolean useSecurePort, boolean useIpAddr) {
        DiscoveryEnabledServer server = new DiscoveryEnabledServer(instanceInfo, useSecurePort, useIpAddr);

        // Get availabilty zone for this instance.
        EurekaClientConfig clientConfig = eurekaClientProvider.get().getEurekaClientConfig();
        String[] availZones = clientConfig.getAvailabilityZones(clientConfig.getRegion());
        String instanceZone = InstanceInfo.getZone(availZones, instanceInfo);
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


    private static IClientConfig createClientConfig(String vipAddresses) {
        IClientConfig clientConfig = ClientConfigFactory.DEFAULT.newConfig();
        clientConfig.set(Keys.DeploymentContextBasedVipAddresses, vipAddresses);
        return clientConfig;
    }
}
