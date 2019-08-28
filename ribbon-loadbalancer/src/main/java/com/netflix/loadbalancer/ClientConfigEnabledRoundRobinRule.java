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

/**
 * This class essentially contains the RoundRobinRule class defined in the
 * loadbalancer package
 * 
 * @author stonse
 *      根据客户端配置的规则对象  实际上内部就是委托给 roundRobin 进行负载
 */
public class ClientConfigEnabledRoundRobinRule extends AbstractLoadBalancerRule {

    /**
     * 内部维护一个轮询规则对象
     */
    RoundRobinRule roundRobinRule = new RoundRobinRule();

    /**
     * 没有使用新config对象覆写东西 而是直接创建了一个 RoundRobinRule 对象
     * @param clientConfig
     */
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        roundRobinRule = new RoundRobinRule();
    }

    @Override
    public void setLoadBalancer(ILoadBalancer lb) {
    	super.setLoadBalancer(lb);
    	roundRobinRule.setLoadBalancer(lb);
    }
    
    @Override
    public Server choose(Object key) {
        if (roundRobinRule != null) {
            return roundRobinRule.choose(key);
        } else {
            throw new IllegalArgumentException(
                    "This class has not been initialized with the RoundRobinRule class");
        }
    }

}
