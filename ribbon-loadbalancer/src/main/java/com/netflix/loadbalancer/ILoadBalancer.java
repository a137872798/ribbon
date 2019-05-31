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

import java.util.List;

/**
 * Interface that defines the operations for a software loadbalancer. A typical
 * loadbalancer minimally need a set of servers to loadbalance for, a method to
 * mark a particular server to be out of rotation and a call that will choose a
 * server from the existing list of server.
 * 
 * @author stonse
 * 		均衡负载接口 该接口内部维护了一组服务
 */
public interface ILoadBalancer {

	/**
	 * Initial list of servers.
	 * This API also serves to add additional ones at a later time
	 * The same logical server (host:port) could essentially be added multiple times
	 * (helpful in cases where you want to give more "weightage" perhaps ..)
	 * 
	 * @param newServers new servers to add
	 *                   为负载对象 加入一组服务
	 */
	public void addServers(List<Server> newServers);
	
	/**
	 * Choose a server from load balancer.
	 * 
	 * @param key An object that the load balancer may use to determine which server to return. null if 
	 *         the load balancer does not use this parameter.
	 * @return server chosen
	 */
	public Server chooseServer(Object key);
	
	/**
	 * To be called by the clients of the load balancer to notify that a Server is down
	 * else, the LB will think its still Alive until the next Ping cycle - potentially
	 * (assuming that the LB Impl does a ping)
	 * 
	 * @param server Server to mark as down
	 *        标识某个服务处于下线状态 否则会进入下次均衡负载的 候选服务
	 */
	public void markServerDown(Server server);
	
	/**
	 * @deprecated 2016-01-20 This method is deprecated in favor of the
	 * cleaner {@link #getReachableServers} (equivalent to availableOnly=true)
	 * and {@link #getAllServers} API (equivalent to availableOnly=false).
	 *
	 * Get the current list of servers.
	 *
	 * @param availableOnly if true, only live and available servers should be returned
	 */
	@Deprecated
	public List<Server> getServerList(boolean availableOnly);

	/**
	 * @return Only the servers that are up and reachable.
	 * 		仅返回当前可用服务列表
     */
    public List<Server> getReachableServers();

    /**
     * @return All known servers, both reachable and unreachable.
	 * 		包含可用的和不可用的
     */
	public List<Server> getAllServers();
}
