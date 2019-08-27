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
package com.netflix.client;

import java.net.ConnectException;

/**
 * A handler that determines if an exception is retriable for load balancer,
 * and if an exception or error response should be treated as circuit related failures
 * so that the load balancer can avoid such server.
 *  
 * @author awang
 * 具备判断 是否是可重试异常 以及避开之前的 server 进行重试的能力
 */
public interface RetryHandler {

    public static final RetryHandler DEFAULT = new DefaultLoadBalancerRetryHandler();
    
    /**
     * Test if an exception is retriable for the load balancer
     * 
     * @param e the original exception
     * @param sameServer if true, the method is trying to determine if retry can be 
     *        done on the same server. Otherwise, it is testing whether retry can be
     *        done on a different server
     *                   测试异常是否是可重试的
     */
    public boolean isRetriableException(Throwable e, boolean sameServer);

    /**
     * Test if an exception should be treated as circuit failure. For example, 
     * a {@link ConnectException} is a circuit failure. This is used to determine
     * whether successive exceptions of such should trip the circuit breaker to a particular
     * host by the load balancer. If false but a server response is absent, 
     * load balancer will also close the circuit upon getting such exception.
     * 判断是否为 电路故障???
     */
    public boolean isCircuitTrippingException(Throwable e);
        
    /**
     * @return Number of maximal retries to be done on one server
     * 获取相同 server 的最大重试次数
     */
    public int getMaxRetriesOnSameServer();

    /**
     * @return Number of maximal different servers to retry
     * 获取不同server 的最大重试次数
     */
    public int getMaxRetriesOnNextServer();
}
