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

import com.google.common.base.Strings;
import com.netflix.client.ClientException;
import com.netflix.client.ClientRequest;
import com.netflix.client.DefaultLoadBalancerRetryHandler;
import com.netflix.client.IClientConfigAware;
import com.netflix.client.RetryHandler;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Timer;
import com.netflix.util.Pair;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

/**
 * A class contains APIs intended to be used be load balancing client which is subclass of this class.
 * 
 * @author awang
 * 均衡负载的 上下文对象
 */
public class LoadBalancerContext implements IClientConfigAware {
    private static final Logger logger = LoggerFactory.getLogger(LoadBalancerContext.class);

    protected String clientName = "default";

    /**
     * 内部维护的 vip 地址 可能由","拼接
     */
    protected String vipAddresses;

    // 设置默认值
    protected int maxAutoRetriesNextServer = CommonClientConfigKey.MaxAutoRetriesNextServer.defaultValue();
    protected int maxAutoRetries = CommonClientConfigKey.MaxAutoRetries.defaultValue();

    /**
     * 默认的重试处理器
     */
    protected RetryHandler defaultRetryHandler = new DefaultLoadBalancerRetryHandler();


    /**
     * 是否针对所有 请求都允许重试
     */
    protected boolean okToRetryOnAllOperations = CommonClientConfigKey.OkToRetryOnAllOperations.defaultValue();

    /**
     * 均衡负载对象
     */
    private ILoadBalancer lb;

    /**
     * netflix 下的某个组件
     */
    private volatile Timer tracer;

    public LoadBalancerContext(ILoadBalancer lb) {
        this.lb = lb;
    }

    /**
     * Delegate to {@link #initWithNiwsConfig(IClientConfig)}
     * @param clientConfig 使用传入的config 更新属性
     */
    public LoadBalancerContext(ILoadBalancer lb, IClientConfig clientConfig) {
        this.lb = lb;
        initWithNiwsConfig(clientConfig);        
    }

    public LoadBalancerContext(ILoadBalancer lb, IClientConfig clientConfig, RetryHandler handler) {
        this(lb, clientConfig);
        this.defaultRetryHandler = handler;
    }

    /**
     * Set necessary parameters from client configuration and register with Servo monitors.
     * 使用传入的config对象更新属性
     */
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        if (clientConfig == null) {
            return;    
        }
        clientName = clientConfig.getClientName();
        if (StringUtils.isEmpty(clientName)) {
            clientName = "default";
        }
        // 从config 上解析 address
        vipAddresses = clientConfig.resolveDeploymentContextbasedVipAddresses();
        maxAutoRetries = clientConfig.getOrDefault(CommonClientConfigKey.MaxAutoRetries);
        maxAutoRetriesNextServer = clientConfig.getOrDefault(CommonClientConfigKey.MaxAutoRetriesNextServer);
        okToRetryOnAllOperations = clientConfig.getOrDefault(CommonClientConfigKey.OkToRetryOnAllOperations);
        defaultRetryHandler = new DefaultLoadBalancerRetryHandler(clientConfig);

        // 获取链路对象
        tracer = getExecuteTracer();

        Monitors.registerObject("Client_" + clientName, this);
    }

    public Timer getExecuteTracer() {
        if (tracer == null) {
            synchronized(this) {
                if (tracer == null) {
                    tracer = Monitors.newTimer(clientName + "_LoadBalancerExecutionTimer", TimeUnit.MILLISECONDS);                    
                }
            }
        } 
        return tracer;        
    }

    public String getClientName() {
        return clientName;
    }

    public ILoadBalancer getLoadBalancer() {
        return lb;    
    }

    public void setLoadBalancer(ILoadBalancer lb) {
        this.lb = lb;
    }

    /**
     * Use {@link #getRetryHandler()} 
     */
    @Deprecated
    public int getMaxAutoRetriesNextServer() {
        return maxAutoRetriesNextServer;
    }

    /**
     * Use {@link #setRetryHandler(RetryHandler)} 
     */
    @Deprecated
    public void setMaxAutoRetriesNextServer(int maxAutoRetriesNextServer) {
        this.maxAutoRetriesNextServer = maxAutoRetriesNextServer;
    }

    /**
     * Use {@link #getRetryHandler()} 
     */
    @Deprecated
    public int getMaxAutoRetries() {
        return maxAutoRetries;
    }

    /**
     * Use {@link #setRetryHandler(RetryHandler)} 
     */
    @Deprecated
    public void setMaxAutoRetries(int maxAutoRetries) {
        this.maxAutoRetries = maxAutoRetries;
    }

    /**
     * 获取最深层的异常对象
     * @param e
     * @return
     */
    protected Throwable getDeepestCause(Throwable e) {
        if(e != null) {
            int infiniteLoopPreventionCounter = 10;
            while (e.getCause() != null && infiniteLoopPreventionCounter > 0) {
                infiniteLoopPreventionCounter--;
                e = e.getCause();
            }
        }
        return e;
    }

    /**
     * 判断给的异常是否是给定的类型
     * @param throwableToSearchIn
     * @param throwableToSearchFor
     * @return
     */
    private boolean isPresentAsCause(Throwable throwableToSearchIn,
            Class<? extends Throwable> throwableToSearchFor) {
        return isPresentAsCauseHelper(throwableToSearchIn, throwableToSearchFor) != null;
    }

    static Throwable isPresentAsCauseHelper(Throwable throwableToSearchIn,
            Class<? extends Throwable> throwableToSearchFor) {
        int infiniteLoopPreventionCounter = 10;
        while (throwableToSearchIn != null && infiniteLoopPreventionCounter > 0) {
            infiniteLoopPreventionCounter--;
            if (throwableToSearchIn.getClass().isAssignableFrom(
                    throwableToSearchFor)) {
                return throwableToSearchIn;
            } else {
                throwableToSearchIn = throwableToSearchIn.getCause();
            }
        }
        return null;
    }

    /**
     * 生成 niws 异常对象
     * @param uri
     * @param e
     * @return
     */
    protected ClientException generateNIWSException(String uri, Throwable e){
        ClientException niwsClientException;
        // 如果是 超时就生成超时异常
        if (isPresentAsCause(e, java.net.SocketTimeoutException.class)) {
            niwsClientException = generateTimeoutNIWSException(uri, e);
        // 未知端口就生成未知端口异常
        }else if (e.getCause() instanceof java.net.UnknownHostException){
            niwsClientException = new ClientException(
                    ClientException.ErrorType.UNKNOWN_HOST_EXCEPTION,
                    "Unable to execute RestClient request for URI:" + uri,
                    e);
        // 连接异常
        }else if (e.getCause() instanceof java.net.ConnectException){
            niwsClientException = new ClientException(
                    ClientException.ErrorType.CONNECT_EXCEPTION,
                    "Unable to execute RestClient request for URI:" + uri,
                    e);
        // 路由异常
        }else if (e.getCause() instanceof java.net.NoRouteToHostException){
            niwsClientException = new ClientException(
                    ClientException.ErrorType.NO_ROUTE_TO_HOST_EXCEPTION,
                    "Unable to execute RestClient request for URI:" + uri,
                    e);
        }else if (e instanceof ClientException){
            niwsClientException = (ClientException)e;
        }else {
            niwsClientException = new ClientException(
                    ClientException.ErrorType.GENERAL,
                    "Unable to execute RestClient request for URI:" + uri,
                    e);
        }
        return niwsClientException;
    }

    private boolean isPresentAsCause(Throwable throwableToSearchIn,
            Class<? extends Throwable> throwableToSearchFor, String messageSubStringToSearchFor) {
        // 找到 指定class 的异常对象
        Throwable throwableFound = isPresentAsCauseHelper(throwableToSearchIn, throwableToSearchFor);
        if(throwableFound != null) {
            // 判断是否包含该异常信息
            return throwableFound.getMessage().contains(messageSubStringToSearchFor);
        }
        return false;
    }

    /**
     * 生成超时异常
     * @param uri
     * @param e
     * @return
     */
    private ClientException generateTimeoutNIWSException(String uri, Throwable e){
        ClientException niwsClientException;
        if (isPresentAsCause(e, java.net.SocketTimeoutException.class,
                "Read timed out")) {
            niwsClientException = new ClientException(
                    ClientException.ErrorType.READ_TIMEOUT_EXCEPTION,
                    "Unable to execute RestClient request for URI:" + uri + ":"
                            + getDeepestCause(e).getMessage(), e);
        } else {
            niwsClientException = new ClientException(
                    ClientException.ErrorType.SOCKET_TIMEOUT_EXCEPTION,
                    "Unable to execute RestClient request for URI:" + uri + ":"
                            + getDeepestCause(e).getMessage(), e);
        }
        return niwsClientException;
    }

    /**
     * 记录统计信息
     * @param stats
     * @param responseTime 响应的时间
     */
    private void recordStats(ServerStats stats, long responseTime) {
    	if (stats == null) {
    		return;
    	}
    	// 较少一个 当前活跃的请求
        stats.decrementActiveRequestsCount();
    	// 增加总请求数
        stats.incrementNumRequests();
        // 记录响应时间
        stats.noteResponseTime(responseTime);
    }

    /**
     * 记录请求完成时间
     * @param stats
     * @param response
     * @param e
     * @param responseTime
     */
    protected void noteRequestCompletion(ServerStats stats, Object response, Throwable e, long responseTime) {
    	if (stats == null) {
    		return;
    	}
        noteRequestCompletion(stats, response, e, responseTime, null);
    }
    
    
    /**
     * This is called after a response is received or an exception is thrown from the client
     * to update related stats.  
     */
    public void noteRequestCompletion(ServerStats stats, Object response, Throwable e, long responseTime, RetryHandler errorHandler) {
    	if (stats == null) {
    		return;
    	}
        try {
    	    // 记录响应结果
            recordStats(stats, responseTime);
            RetryHandler callErrorHandler = errorHandler == null ? getRetryHandler() : errorHandler;
            if (callErrorHandler != null && response != null) {
                // 将某个计数值清零
                stats.clearSuccessiveConnectionFailureCount();
            } else if (callErrorHandler != null && e != null) {
                if (callErrorHandler.isCircuitTrippingException(e)) {
                    stats.incrementSuccessiveConnectionFailureCount();                    
                    stats.addToFailureCount();
                } else {
                    stats.clearSuccessiveConnectionFailureCount();
                }
            }
        } catch (Exception ex) {
            logger.error("Error noting stats for client {}", clientName, ex);
        }            
    }

    /**
     * This is called after an error is thrown from the client
     * to update related stats.
     * 标记出现了异常
     */
    protected void noteError(ServerStats stats, ClientRequest request, Throwable e, long responseTime) {
    	if (stats == null) {
    		return;
    	}
        try {
            recordStats(stats, responseTime);
            RetryHandler errorHandler = getRetryHandler();
            if (errorHandler != null && e != null) {
                if (errorHandler.isCircuitTrippingException(e)) {
                    // 增加失败次数
                    stats.incrementSuccessiveConnectionFailureCount();                    
                    stats.addToFailureCount();
                } else {
                    stats.clearSuccessiveConnectionFailureCount();
                }
            }
        } catch (Exception ex) {
            logger.error("Error noting stats for client {}", clientName, ex);
        }            
    }

    /**
     * This is called after a response is received from the client
     * to update related stats.  
     */
    protected void noteResponse(ServerStats stats, ClientRequest request, Object response, long responseTime) {
    	if (stats == null) {
    		return;
    	}
        try {
            recordStats(stats, responseTime);
            RetryHandler errorHandler = getRetryHandler();
            if (errorHandler != null && response != null) {
                stats.clearSuccessiveConnectionFailureCount();
            } 
        } catch (Exception ex) {
            logger.error("Error noting stats for client {}", clientName, ex);
        }            
    }

    /**
     * This is usually called just before client execute a request.
     * 标记开始请求了 增加 stats 的数值
     */
    public void noteOpenConnection(ServerStats serverStats) {
        if (serverStats == null) {
            return;
        }
        try {
            serverStats.incrementActiveRequestsCount();
        } catch (Exception ex) {
            logger.error("Error noting stats for client {}", clientName, ex);
        }            
    }


    /**
     * Derive scheme and port from a partial URI. For example, for HTTP based client, the URI with 
     * only path "/" should return "http" and 80, whereas the URI constructed with scheme "https" and
     * path "/" should return "https" and 443.
     * This method is called by {@link #getServerFromLoadBalancer(java.net.URI, Object)} and
     * {@link #reconstructURIWithServer(Server, java.net.URI)} methods to get the complete executable URI.
     * 从 url 中解析出 ip 和 port
     */
    protected Pair<String, Integer> deriveSchemeAndPortFromPartialUri(URI uri) {
        boolean isSecure = false;
        String scheme = uri.getScheme();
        if (scheme != null) {
            isSecure =  scheme.equalsIgnoreCase("https");
        }
        int port = uri.getPort();
        //这里使用了 http 默认监听端口 80
        if (port < 0 && !isSecure){
            port = 80;
            //这里 使用https 默认监听端口 443
        } else if (port < 0 && isSecure){
            port = 443;
        }
        if (scheme == null){
            if (isSecure) {
                scheme = "https";
            } else {
                scheme = "http";
            }
        }
        return new Pair<String, Integer>(scheme, port);
    }

    /**
     * Get the default port of the target server given the scheme of vip address if it is available. 
     * Subclass should override it to provider protocol specific default port number if any.
     * 
     * @param scheme from the vip address. null if not present.
     * @return 80 if scheme is http, 443 if scheme is https, -1 else.
     * 获取默认端口
     */
    protected int getDefaultPortFromScheme(String scheme) {
        if (scheme == null) {
            return -1;
        }
        if (scheme.equals("http")) {
            return 80;
        } else if (scheme.equals("https")) {
            return 443;
        } else {
            return -1;
        }
    }


    /**
     * Derive the host and port from virtual address if virtual address is indeed contains the actual host 
     * and port of the server. This is the final resort to compute the final URI in {@link #getServerFromLoadBalancer(java.net.URI, Object)}
     * if there is no load balancer available and the request URI is incomplete. Sub classes can override this method
     * to be more accurate or throws ClientException if it does not want to support virtual address to be the
     * same as physical server address.
     * <p>
     *  The virtual address is used by certain load balancers to filter the servers of the same function 
     *  to form the server pool. 
     *  
     */
    protected  Pair<String, Integer> deriveHostAndPortFromVipAddress(String vipAddress) 
            throws URISyntaxException, ClientException {
        Pair<String, Integer> hostAndPort = new Pair<String, Integer>(null, -1);
        URI uri = new URI(vipAddress);
        String scheme = uri.getScheme();
        if (scheme == null) {
            uri = new URI("http://" + vipAddress);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new ClientException("Unable to derive host/port from vip address " + vipAddress);
        }
        int port = uri.getPort();
        if (port < 0) {
            port = getDefaultPortFromScheme(scheme);
        }
        if (port < 0) {
            throw new ClientException("Unable to derive host/port from vip address " + vipAddress);
        }
        hostAndPort.setFirst(host);
        hostAndPort.setSecond(port);
        return hostAndPort;
    }

    /**
     * 判断当前 url 是否是vipAddress
     * @param vipEmbeddedInUri
     * @return
     */
    private boolean isVipRecognized(String vipEmbeddedInUri) {
        if (vipEmbeddedInUri == null) {
            return false;
        }
        if (vipAddresses == null) {
            return false;
        }
        String[] addresses = vipAddresses.split(",");
        for (String address: addresses) {
            if (vipEmbeddedInUri.equalsIgnoreCase(address.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compute the final URI from a partial URI in the request. The following steps are performed:
     * <ul>
     * <li> if host is missing and there is a load balancer, get the host/port from server chosen from load balancer
     * <li> if host is missing and there is no load balancer, try to derive host/port from virtual address set with the client
     * <li> if host is present and the authority part of the URI is a virtual address set for the client, 
     * and there is a load balancer, get the host/port from server chosen from load balancer
     * <li> if host is present but none of the above applies, interpret the host as the actual physical address
     * <li> if host is missing but none of the above applies, throws ClientException
     * </ul>
     *
     * @param original Original URI passed from caller
     */
    public Server getServerFromLoadBalancer(@Nullable URI original, @Nullable Object loadBalancerKey) throws ClientException {
        String host = null;
        int port = -1;
        if (original != null) {
            host = original.getHost();
        }
        if (original != null) {
            Pair<String, Integer> schemeAndPort = deriveSchemeAndPortFromPartialUri(original);        
            port = schemeAndPort.second();
        }

        // Various Supported Cases
        // The loadbalancer to use and the instances it has is based on how it was registered
        // In each of these cases, the client might come in using Full Url or Partial URL
        // 获取 均衡负载对象
        ILoadBalancer lb = getLoadBalancer();
        if (host == null) {
            // Partial URI or no URI Case
            // well we have to just get the right instances from lb - or we fall back
            if (lb != null){
                // 通过 选择键 和 均衡负载对象 能否获取到 server 对象
                Server svc = lb.chooseServer(loadBalancerKey);
                if (svc == null){
                    throw new ClientException(ClientException.ErrorType.GENERAL,
                            "Load balancer does not have available server for client: "
                                    + clientName);
                }
                host = svc.getHost();
                if (host == null){
                    throw new ClientException(ClientException.ErrorType.GENERAL,
                            "Invalid Server for :" + svc);
                }
                logger.debug("{} using LB returned Server: {} for request {}", new Object[]{clientName, svc, original});
                return svc;
            } else {
                // No Full URL - and we dont have a LoadBalancer registered to
                // obtain a server
                // if we have a vipAddress that came with the registration, we
                // can use that else we
                // bail out
                // 如果没有设置 均衡负载对象
                if (vipAddresses != null && vipAddresses.contains(",")) {
                    throw new ClientException(
                            ClientException.ErrorType.GENERAL,
                            "Method is invoked for client " + clientName + " with partial URI of ("
                            + original
                            + ") with no load balancer configured."
                            + " Also, there are multiple vipAddresses and hence no vip address can be chosen"
                            + " to complete this partial uri");
                } else if (vipAddresses != null) {
                    try {
                        Pair<String,Integer> hostAndPort = deriveHostAndPortFromVipAddress(vipAddresses);
                        host = hostAndPort.first();
                        port = hostAndPort.second();
                    } catch (URISyntaxException e) {
                        throw new ClientException(
                                ClientException.ErrorType.GENERAL,
                                "Method is invoked for client " + clientName + " with partial URI of ("
                                + original
                                + ") with no load balancer configured. "
                                + " Also, the configured/registered vipAddress is unparseable (to determine host and port)");
                    }
                } else {
                    throw new ClientException(
                            ClientException.ErrorType.GENERAL,
                            this.clientName
                            + " has no LoadBalancer registered and passed in a partial URL request (with no host:port)."
                            + " Also has no vipAddress registered");
                }
            }
        } else {
            // Full URL Case
            // This could either be a vipAddress or a hostAndPort or a real DNS
            // if vipAddress or hostAndPort, we just have to consult the loadbalancer
            // but if it does not return a server, we should just proceed anyways
            // and assume its a DNS
            // For restClients registered using a vipAddress AND executing a request
            // by passing in the full URL (including host and port), we should only
            // consult lb IFF the URL passed is registered as vipAddress in Discovery
            boolean shouldInterpretAsVip = false;

            if (lb != null) {
                // 判断是否应该使用vip
                shouldInterpretAsVip = isVipRecognized(original.getAuthority());
            }
            if (shouldInterpretAsVip) {
                Server svc = lb.chooseServer(loadBalancerKey);
                if (svc != null){
                    host = svc.getHost();
                    if (host == null){
                        throw new ClientException(ClientException.ErrorType.GENERAL,
                                "Invalid Server for :" + svc);
                    }
                    logger.debug("using LB returned Server: {} for request: {}", svc, original);
                    return svc;
                } else {
                    // just fall back as real DNS
                    logger.debug("{}:{} assumed to be a valid VIP address or exists in the DNS", host, port);
                }
            } else {
                // consult LB to obtain vipAddress backed instance given full URL
                //Full URL execute request - where url!=vipAddress
                logger.debug("Using full URL passed in by caller (not using load balancer): {}", original);
            }
        }
        // end of creating final URL
        if (host == null){
            throw new ClientException(ClientException.ErrorType.GENERAL,"Request contains no HOST to talk to");
        }
        // just verify that at this point we have a full URL

        // 默认情况下 直接使用 ip port 生成一个server 对象
        return new Server(host, port);
    }

    /**
     * 根据 服务信息 来构造url
     * @param server
     * @param original
     * @return
     */
    public URI reconstructURIWithServer(Server server, URI original) {
        //获取 服务本身的主机
        String host = server.getHost();
        //获取 服务端口
        int port = server.getPort();
        //获取协议信息
        String scheme = server.getScheme();

        //判断端口号和 ip 是否一致 如果一致情况下 应该是不需要做处理
        //如果将服务名拼接在 scheme 后面 name 获取到的 host 就是服务名
        if (host.equals(original.getHost()) 
                && port == original.getPort()
                && scheme == original.getScheme()) {
            return original;
        }
        if (scheme == null) {
            scheme = original.getScheme();
        }
        if (scheme == null) {
            scheme = deriveSchemeAndPortFromPartialUri(original).first();
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://");
            if (!Strings.isNullOrEmpty(original.getRawUserInfo())) {
                sb.append(original.getRawUserInfo()).append("@");
            }
            //这里就是单纯的 替换 host 和 port
            sb.append(host);
            if (port >= 0) {
                sb.append(":").append(port);
            }
            sb.append(original.getRawPath());
            if (!Strings.isNullOrEmpty(original.getRawQuery())) {
                sb.append("?").append(original.getRawQuery());
            }
            if (!Strings.isNullOrEmpty(original.getRawFragment())) {
                sb.append("#").append(original.getRawFragment());
            }
            URI newURI = new URI(sb.toString());
            return newURI;            
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取下个server 的重试次数
     * @param overriddenClientConfig
     * @return
     */
    protected int getRetriesNextServer(IClientConfig overriddenClientConfig) {
        int numRetries = maxAutoRetriesNextServer;
        if (overriddenClientConfig != null) {
            numRetries = overriddenClientConfig.get(CommonClientConfigKey.MaxAutoRetriesNextServer, maxAutoRetriesNextServer);
        }
        return numRetries;
    }

    /**
     * 获取服务对象对应的统计对象
     * @param server
     * @return
     */
    public final ServerStats getServerStats(Server server) {
        ServerStats serverStats = null;
        ILoadBalancer lb = this.getLoadBalancer();
        // 获取均衡负载对象
        if (lb instanceof AbstractLoadBalancer){
            // 获取 负载对象的 统计对象
            LoadBalancerStats lbStats = ((AbstractLoadBalancer) lb).getLoadBalancerStats();
            // 通过server 定位到 server统计对象
            serverStats = lbStats.getSingleServerStat(server);
        }
        return serverStats;

    }

    /**
     * 获取 同一服务器的 重试次数
     * @param overriddenClientConfig
     * @return
     */
    protected int getNumberRetriesOnSameServer(IClientConfig overriddenClientConfig) {
        int numRetries =  maxAutoRetries;
        if (overriddenClientConfig!=null){
            try {
                numRetries = overriddenClientConfig.get(CommonClientConfigKey.MaxAutoRetries, maxAutoRetries);
            } catch (Exception e) {
                logger.warn("Invalid maxRetries requested for RestClient:" + this.clientName);
            }
        }
        return numRetries;
    }

    /**
     * 处理相同服务  这里只是判断 当前次数 是否超过了最大值
     * @param server
     * @param currentRetryCount
     * @param maxRetries
     * @param e
     * @return
     */
    public boolean handleSameServerRetry(Server server, int currentRetryCount, int maxRetries, Throwable e) {
        if (currentRetryCount > maxRetries) {
            return false;
        }
        logger.debug("Exception while executing request which is deemed retry-able, retrying ..., SAME Server Retry Attempt#: {}",  
                currentRetryCount, server);
        return true;
    }

    public final RetryHandler getRetryHandler() {
        return defaultRetryHandler;
    }

    public final void setRetryHandler(RetryHandler retryHandler) {
        this.defaultRetryHandler = retryHandler;
    }

    public final boolean isOkToRetryOnAllOperations() {
        return okToRetryOnAllOperations;
    }

    public final void setOkToRetryOnAllOperations(boolean okToRetryOnAllOperations) {
        this.okToRetryOnAllOperations = okToRetryOnAllOperations;
    }
}
