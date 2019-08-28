package com.netflix.loadbalancer;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A default strategy for the dynamic server list updater to update.
 * (refactored and moved here from {@link com.netflix.loadbalancer.DynamicServerListLoadBalancer})
 *
 * @author David Liu
 *      默认的自动更新服务列表的实现
 */
public class PollingServerListUpdater implements ServerListUpdater {

    private static final Logger logger = LoggerFactory.getLogger(PollingServerListUpdater.class);

    /**
     * 服务列表更新时间
     */
    private static long LISTOFSERVERS_CACHE_UPDATE_DELAY = 1000; // msecs;
    /**
     * 重复间隔???
     */
    private static int LISTOFSERVERS_CACHE_REPEAT_INTERVAL = 30 * 1000; // msecs;
    private static int POOL_SIZE = 2;

    /**
     * 构建一个线程池对象
     */
    private static class LazyHolder {
        static ScheduledExecutorService _serverListRefreshExecutor = null;

        static {
            _serverListRefreshExecutor = Executors.newScheduledThreadPool(POOL_SIZE, new ThreadFactoryBuilder()
                    .setNameFormat("PollingServerListUpdater-%d")
                    .setDaemon(true)
                    .build());
        }
    }

    private static ScheduledExecutorService getRefreshExecutor() {
        return LazyHolder._serverListRefreshExecutor;
    }

    /**
     * 当前是否处于活跃状态 也就是 是否有执行任务
     */
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    /**
     * 最后更新时间
     */
    private volatile long lastUpdated = System.currentTimeMillis();
    /**
     * 首次开始的延迟
     */
    private final long initialDelayMs;
    /**
     * 更新服务列表的延迟
     */
    private final long refreshIntervalMs;

    /**
     * 更新的结果对象
     */
    private volatile ScheduledFuture<?> scheduledFuture;

    public PollingServerListUpdater() {
        this(LISTOFSERVERS_CACHE_UPDATE_DELAY, LISTOFSERVERS_CACHE_REPEAT_INTERVAL);
    }

    public PollingServerListUpdater(IClientConfig clientConfig) {
        this(LISTOFSERVERS_CACHE_UPDATE_DELAY, getRefreshIntervalMs(clientConfig));
    }

    public PollingServerListUpdater(final long initialDelayMs, final long refreshIntervalMs) {
        this.initialDelayMs = initialDelayMs;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /**
     * 启动 服务列表更新 为什么要套2层锁
     * @param updateAction
     */
    @Override
    public synchronized void start(final UpdateAction updateAction) {
        //CAS 保证只能启动一次
        if (isActive.compareAndSet(false, true)) {
            final Runnable wrapperRunnable = () ->  {
                // 可能是isActive 在 被CAS 处理后 又被 修改为false 那么这时 就要关闭之前开启着的任务
                if (!isActive.get()) {
                    //如果 定时结果没有关闭 就执行关闭
                    if (scheduledFuture != null) {
                        scheduledFuture.cancel(true);
                    }
                    return;
                }
                try {
                    //执行更新任务
                    updateAction.doUpdate();
                    //更新 时间戳
                    lastUpdated = System.currentTimeMillis();
                } catch (Exception e) {
                    logger.warn("Failed one update cycle", e);
                }
            };

            scheduledFuture = getRefreshExecutor().scheduleWithFixedDelay(
                    wrapperRunnable,
                    initialDelayMs,
                    refreshIntervalMs,
                    TimeUnit.MILLISECONDS
            );
        } else {
            logger.info("Already active, no-op");
        }
    }

    /**
     * stop 就是关闭 future
     */
    @Override
    public synchronized void stop() {
        if (isActive.compareAndSet(true, false)) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(true);
            }
        } else {
            logger.info("Not active, no-op");
        }
    }

    @Override
    public String getLastUpdate() {
        return new Date(lastUpdated).toString();
    }

    @Override
    public long getDurationSinceLastUpdateMs() {
        return System.currentTimeMillis() - lastUpdated;
    }

    /**
     * 代表经过了几个循环
     * @return
     */
    @Override
    public int getNumberMissedCycles() {
        if (!isActive.get()) {
            return 0;
        }
        return (int) ((int) (System.currentTimeMillis() - lastUpdated) / refreshIntervalMs);
    }

    @Override
    public int getCoreThreads() {
        return POOL_SIZE;
    }

    /**
     * 从配置中获取 重复时间间隔
     * @param clientConfig
     * @return
     */
    private static long getRefreshIntervalMs(IClientConfig clientConfig) {
        return clientConfig.get(CommonClientConfigKey.ServerListRefreshInterval, LISTOFSERVERS_CACHE_REPEAT_INTERVAL);
    }
}
