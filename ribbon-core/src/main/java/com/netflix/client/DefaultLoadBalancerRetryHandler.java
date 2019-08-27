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

import com.google.common.collect.Lists;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;

/**
 * A default {@link RetryHandler}. The implementation is limited to
 * known exceptions in java.net. Specific client implementation should provide its own
 * {@link RetryHandler}
 * 
 * @author awang
 * 默认的重试处理器  这里只负责处理 java.net 可能出现的异常
 */
public class DefaultLoadBalancerRetryHandler implements RetryHandler {

    /**
     * 维护了 java.net 相关的可重试异常
     */
    @SuppressWarnings("unchecked")
    private List<Class<? extends Throwable>> retriable = 
            Lists.<Class<? extends Throwable>>newArrayList(ConnectException.class, SocketTimeoutException.class);

    /**
     * 电路联系???
     */
    @SuppressWarnings("unchecked")
    private List<Class<? extends Throwable>> circuitRelated = 
            Lists.<Class<? extends Throwable>>newArrayList(SocketException.class, SocketTimeoutException.class);

    /**
     * 从同一 server 进行重试的最大次数
     */
    protected final int retrySameServer;
    /**
     * 从不同 server 进行重试的最大次数
     */
    protected final int retryNextServer;
    /**
     * 是否允许重试
     */
    protected final boolean retryEnabled;

    /**
     * 默认情况不可重试
     */
    public DefaultLoadBalancerRetryHandler() {
        this.retrySameServer = 0;
        this.retryNextServer = 0;
        this.retryEnabled = false;
    }
    
    public DefaultLoadBalancerRetryHandler(int retrySameServer, int retryNextServer, boolean retryEnabled) {
        this.retrySameServer = retrySameServer;
        this.retryNextServer = retryNextServer;
        this.retryEnabled = retryEnabled;
    }

    /**
     * 使用配置中的属性去初始化对象
     * @param clientConfig
     */
    public DefaultLoadBalancerRetryHandler(IClientConfig clientConfig) {
        this.retrySameServer = clientConfig.getOrDefault(CommonClientConfigKey.MaxAutoRetries);
        this.retryNextServer = clientConfig.getOrDefault(CommonClientConfigKey.MaxAutoRetriesNextServer);
        this.retryEnabled = clientConfig.getOrDefault(CommonClientConfigKey.OkToRetryOnAllOperations);
    }

    /**
     * 判断当前异常是否是 可重试
     * @param e the original exception
     * @param sameServer if true, the method is trying to determine if retry can be
     *        done on the same server. Otherwise, it is testing whether retry can be
     *        done on a different server
     * @return
     */
    @Override
    public boolean isRetriableException(Throwable e, boolean sameServer) {
        // 首先确保允许重试
        if (retryEnabled) {
            // 如果尝试继续访问同一server
            if (sameServer) {
                // 如果传入的 异常属于该list 就可以重试
                return Utils.isPresentAsCause(e, getRetriableExceptions());
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if {@link SocketException} or {@link SocketTimeoutException} is a cause in the Throwable.
     */
    @Override
    public boolean isCircuitTrippingException(Throwable e) {
        return Utils.isPresentAsCause(e, getCircuitRelatedExceptions());        
    }

    @Override
    public int getMaxRetriesOnSameServer() {
        return retrySameServer;
    }

    @Override
    public int getMaxRetriesOnNextServer() {
        return retryNextServer;
    }
    
    protected List<Class<? extends Throwable>> getRetriableExceptions() {
        return retriable;
    }
    
    protected List<Class<? extends Throwable>>  getCircuitRelatedExceptions() {
        return circuitRelated;
    }
}
