package com.netflix.client;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;

import javax.annotation.Nullable;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of RetryHandler created for each request which allows for request
 * specific override
 * 特殊请求重试器
 */
public class RequestSpecificRetryHandler implements RetryHandler {

    /**
     * 降级处理器
     */
    private final RetryHandler fallback;
    /**
     * 同一server最大重试次数
     */
    private int retrySameServer = -1;
    /**
     * 不同server最大重试次数
     */
    private int retryNextServer = -1;

    // 代表针对什么场景允许重试
    private final boolean okToRetryOnConnectErrors;
    private final boolean okToRetryOnAllErrors;
    
    protected List<Class<? extends Throwable>> connectionRelated = 
            Lists.<Class<? extends Throwable>>newArrayList(SocketException.class);

    public RequestSpecificRetryHandler(boolean okToRetryOnConnectErrors, boolean okToRetryOnAllErrors) {
        this(okToRetryOnConnectErrors, okToRetryOnAllErrors, RetryHandler.DEFAULT, null);    
    }
    
    public RequestSpecificRetryHandler(boolean okToRetryOnConnectErrors, boolean okToRetryOnAllErrors, RetryHandler baseRetryHandler, @Nullable IClientConfig requestConfig) {
        Preconditions.checkNotNull(baseRetryHandler);
        this.okToRetryOnConnectErrors = okToRetryOnConnectErrors;
        this.okToRetryOnAllErrors = okToRetryOnAllErrors;
        // 使用默认的 retryHandler 作为 降级handler
        this.fallback = baseRetryHandler;
        // 如果配置中包含重试次数信息就设置 否则使用-1
        if (requestConfig != null) {
            Optional.ofNullable(requestConfig.get(CommonClientConfigKey.MaxAutoRetries)).ifPresent(
                    value -> retrySameServer = value
            );
            Optional.ofNullable(requestConfig.get(CommonClientConfigKey.MaxAutoRetriesNextServer)).ifPresent(
                    value -> retryNextServer = value
            );
        }
    }

    /**
     * 判断传入的异常是否属于 connectionException
     * @param e
     * @return
     */
    public boolean isConnectionException(Throwable e) {
        return Utils.isPresentAsCause(e, connectionRelated);
    }

    @Override
    public boolean isRetriableException(Throwable e, boolean sameServer) {
        // 代表针对任何异常 总是开启重试
        if (okToRetryOnAllErrors) {
            return true;
        } 
        else if (e instanceof ClientException) {
            ClientException ce = (ClientException) e;
            // 可能是代表 该服务器停止了 那么 一旦要求使用sameServer就不被允许
            if (ce.getErrorType() == ClientException.ErrorType.SERVER_THROTTLED) {
                return !sameServer;
            } else {
                return false;
            }
        } 
        else  {
            // 只有连接异常才允许重试
            return okToRetryOnConnectErrors && isConnectionException(e);
        }
    }

    // 以下情况 使用降级 retryHandler

    @Override
    public boolean isCircuitTrippingException(Throwable e) {
        return fallback.isCircuitTrippingException(e);
    }

    @Override
    public int getMaxRetriesOnSameServer() {
        if (retrySameServer >= 0) {
            return retrySameServer;
        }
        return fallback.getMaxRetriesOnSameServer();
    }

    @Override
    public int getMaxRetriesOnNextServer() {
        if (retryNextServer >= 0) {
            return retryNextServer;
        }
        return fallback.getMaxRetriesOnNextServer();
    }    
}
