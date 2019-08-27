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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.Server;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import com.netflix.servo.monitor.Timer;

/**
 * Prime the connections for a given Client (For those Client that
 * have a LoadBalancer that knows the set of Servers it will connect to) This is
 * mainly done to address those deployment environments (Read EC2) which benefit
 * from a firewall connection/path warmup prior to actual use for live requests.
 * <p>
 * This class is not protocol specific. Actual priming operation is delegated to 
 * instance of {@link IPrimeConnection}, which is instantiated using reflection
 * according to property {@link CommonClientConfigKey#PrimeConnectionsClassName}.
 * 
 * @author stonse
 * @author awang
 * @author aspyker
 * 主要连接
 */
public class PrimeConnections {

    /**
     * 监听器对象 包含一个 连接完成的方法
     */
    public static interface PrimeConnectionListener {
        public void primeCompleted(Server s, Throwable lastException);
    }

    /**
     * 连接结束时的统计对象
     */
    public static class PrimeConnectionEndStats {
        /**
         * 总调用次数
         */
        public final int total;
        /**
         * 成功次数
         */
        public final int success;
        /**
         * 失败次数
         */
        public final int failure;
        /**
         * 总时长
         */
        public final long totalTime;

        public PrimeConnectionEndStats(int total, int success, int failure, long totalTime) {
            this.total = total;
            this.success = success;
            this.failure = failure;
            this.totalTime = totalTime;
        }

        @Override
        public String toString() {
            return "PrimeConnectionEndStats [total=" + total + ", success="
                + success + ", failure=" + failure + ", totalTime="
                + totalTime + "]";
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(PrimeConnections.class);

    // affordance to change the URI we connect to while "priming"
    // default of "/" is good for most - but if its heavy operation on
    // the server side, then a more lightweight URI can be chosen
    String primeConnectionsURIPath = "/";

    /**
     * Executor service for executing asynchronous requests.
     * 执行异步请求的线程池
     */
    private ExecutorService executorService;

    /**
     * 最多允许5条线程
     */
    private int maxExecutorThreads = 5;

    private long executorThreadTimeout = 30000;

    private String name = "default";

    private float primeRatio = 1.0f;

    /**
     * 最大重试次数 9次
     */
    int maxRetries = 9;

    long maxTotalTimeToPrimeConnections = 30 * 1000; // default time

    long totalTimeTaken = 0; // Total time taken

    /**
     * 默认开启异步
     */
    private boolean aSync = true;
        
    Counter totalCounter;
    Counter successCounter;
    Timer initialPrimeTimer;

    /**
     * 内部维护了 实际的连接对象
     */
    private IPrimeConnection connector;

    private PrimeConnectionEndStats stats;

    private PrimeConnections() {
    }

    public PrimeConnections(String name, IClientConfig niwsClientConfig) {
        int maxRetriesPerServerPrimeConnection = CommonClientConfigKey.MaxRetriesPerServerPrimeConnection.defaultValue();
        int maxTotalTimeToPrimeConnections =  CommonClientConfigKey.MaxTotalTimeToPrimeConnections.defaultValue();
        try {
            maxRetriesPerServerPrimeConnection = niwsClientConfig.getOrDefault(CommonClientConfigKey.MaxRetriesPerServerPrimeConnection);
        } catch (Exception e) {
            logger.warn("Invalid maxRetriesPerServerPrimeConnection");
        }
        try {
            maxTotalTimeToPrimeConnections = niwsClientConfig.getOrDefault(CommonClientConfigKey.MaxTotalTimeToPrimeConnections);
        } catch (Exception e) {
            logger.warn("Invalid maxTotalTimeToPrimeConnections");
        }
        final String primeConnectionsURI = niwsClientConfig.getOrDefault(CommonClientConfigKey.PrimeConnectionsURI);
        float primeRatio = niwsClientConfig.getOrDefault(CommonClientConfigKey.MinPrimeConnectionsRatio);
        // 获取默认实现类  HttpPrimeConnection
        final String className = niwsClientConfig.getOrDefault(CommonClientConfigKey.PrimeConnectionsClassName);
        try {
            connector = (IPrimeConnection) Class.forName(className).newInstance();
            connector.initWithNiwsConfig(niwsClientConfig);
        } catch (Exception e) {
            throw new RuntimeException("Unable to initialize prime connections", e);
        }
        //安装
        setUp(name, maxRetriesPerServerPrimeConnection, 
                maxTotalTimeToPrimeConnections, primeConnectionsURI, primeRatio);        
    }
        
    public PrimeConnections(String name, int maxRetries, 
            long maxTotalTimeToPrimeConnections, String primeConnectionsURI) {
        setUp(name, maxRetries, maxTotalTimeToPrimeConnections, primeConnectionsURI, CommonClientConfigKey.MinPrimeConnectionsRatio.defaultValue());
    }

    public PrimeConnections(String name, int maxRetries, 
            long maxTotalTimeToPrimeConnections, String primeConnectionsURI, float primeRatio) {
        setUp(name, maxRetries, maxTotalTimeToPrimeConnections, primeConnectionsURI, primeRatio);
    }

    /**
     * 设置connection 相关属性
     * @param name
     * @param maxRetries
     * @param maxTotalTimeToPrimeConnections
     * @param primeConnectionsURI
     * @param primeRatio
     */
    private void setUp(String name, int maxRetries, 
            long maxTotalTimeToPrimeConnections, String primeConnectionsURI, float primeRatio) {        
        this.name = name;
        this.maxRetries = maxRetries;
        this.maxTotalTimeToPrimeConnections = maxTotalTimeToPrimeConnections;
        this.primeConnectionsURIPath = primeConnectionsURI;        
        this.primeRatio = primeRatio;
        executorService = new ThreadPoolExecutor(1 /* minimum */,
                maxExecutorThreads /* max threads */,
                executorThreadTimeout /*
                                       * timeout - same property as create
                                       * timeout
                                       */, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>()
                /* Bounded queue with FIFO- bounded to max tasks */,
                new ASyncPrimeConnectionsThreadFactory(name) /*
                                                              * So we can give
                                                              * our Thread a
                                                              * name
                                                              */
        );        
        totalCounter = Monitors.newCounter(name + "_PrimeConnection_TotalCounter");
        successCounter = Monitors.newCounter(name + "_PrimeConnection_SuccessCounter");
        initialPrimeTimer = Monitors.newTimer(name + "_initialPrimeConnectionsTimer", TimeUnit.MILLISECONDS);
        Monitors.registerObject(name + "_PrimeConnection", this);
    }
    
    /**
     * Prime connections, blocking until configured percentage (default is 100%) of target servers are primed 
     * or max time is reached.
     * 
     * @see CommonClientConfigKey#MinPrimeConnectionsRatio
     * @see CommonClientConfigKey#MaxTotalTimeToPrimeConnections
     * 
     */
    public void primeConnections(List<Server> servers) {
        if (servers == null || servers.size() == 0) {
            logger.debug("No server to prime");
            return;
        }
        // 将指定server 都设置成未准备
        for (Server server: servers) {
            server.setReadyToServe(false);
        }
        int totalCount = (int) (servers.size() * primeRatio); 
        final CountDownLatch latch = new CountDownLatch(totalCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount= new AtomicInteger(0);
        // 使用栅栏进行调用
        primeConnectionsAsync(servers, new PrimeConnectionListener()  {            
            @Override
            public void primeCompleted(Server s, Throwable lastException) {
                if (lastException == null) {
                    // 代表某个server 初始化成功
                    successCount.incrementAndGet();
                    s.setReadyToServe(true);
                } else {
                    failureCount.incrementAndGet();
                }
                latch.countDown();
            }
        }); 
                
        Stopwatch stopWatch = initialPrimeTimer.start();
        try {
            latch.await(maxTotalTimeToPrimeConnections, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logger.error("Priming connection interrupted", e);
        } finally {
            stopWatch.stop();
        }

        // 生成统计对象
        stats = new PrimeConnectionEndStats(totalCount, successCount.get(), failureCount.get(), stopWatch.getDuration(TimeUnit.MILLISECONDS));

        printStats(stats);
    }

    public PrimeConnectionEndStats getEndStats() {
        return stats;
    }

    private void printStats(PrimeConnectionEndStats stats) {
        if (stats.total != stats.success) {
            logger.info("Priming Connections not fully successful");
        } else {
            logger.info("Priming connections fully successful");
        }
        logger.debug("numServers left to be 'primed'="
                + (stats.total - stats.success));
        logger.debug("numServers successfully 'primed'=" + stats.success);
        logger
                .debug("numServers whose attempts not complete exclusively due to max time allocated="
                        + (stats.total - (stats.success + stats.failure)));
        logger.debug("Total Time Taken=" + stats.totalTime
                + " msecs, out of an allocated max of (msecs)="
                + maxTotalTimeToPrimeConnections);
        logger.debug("stats = " + stats);
    }

    /*
    private void makeConnectionsASync() {
        Callable<Void> ft = new Callable<Void>() {
            public Void call() throws Exception {
                logger.info("primeConnections ...");
                makeConnections();
                return null;
            }
        };
        outerExecutorService.submit(ft);
    }
    */
    
    /**
     * Prime servers asynchronously.
     * 
     * @param servers
     * @param listener
     */
    public List<Future<Boolean>> primeConnectionsAsync(final List<Server> servers, final PrimeConnectionListener listener) {
        if (servers == null) {
            return Collections.emptyList();
        }
        List<Server> allServers = new ArrayList<Server>();
        allServers.addAll(servers);
        if (allServers.size() == 0){
            logger.debug("RestClient:" + name + ". No nodes/servers to prime connections");
            return Collections.emptyList();
        }        

        logger.info("Priming Connections for RestClient:" + name
                + ", numServers:" + allServers.size());
        List<Future<Boolean>> ftList = new ArrayList<Future<Boolean>>();
        for (Server s : allServers) {
            // prevent the server to be used by load balancer
            // will be set to true when priming is done
            s.setReadyToServe(false);
            if (aSync) {
                Future<Boolean> ftC = null;
                try {
                    // 异步处理
                    ftC = makeConnectionASync(s, listener);
                    ftList.add(ftC);
                }
                catch (RejectedExecutionException ree) {
                    logger.error("executor submit failed", ree);
                }
                catch (Exception e) {
                    logger.error("general error", e);
                    // It does not really matter if there was an exception,
                    // the goal here is to attempt "priming/opening" the route
                    // in ec2 .. actual http results do not matter
                }
            } else {
                // 连接到server
                connectToServer(s, listener);
            }
        }   
        return ftList;
    }

    /**
     * 进行异步连接 (将本次任务提交给线程池)
     * @param s
     * @param listener
     * @return
     * @throws InterruptedException
     * @throws RejectedExecutionException
     */
    private Future<Boolean> makeConnectionASync(final Server s, 
            final PrimeConnectionListener listener) throws InterruptedException, RejectedExecutionException {
        Callable<Boolean> ftConn = new Callable<Boolean>() {
            public Boolean call() throws Exception {
                logger.debug("calling primeconnections ...");
                return connectToServer(s, listener);
            }
        };
        return executorService.submit(ftConn);
    }

    public void shutdown() {
        executorService.shutdown();
        Monitors.unregisterObject(name + "_PrimeConnection", this);
    }

    /**
     * 连接到 server
     * @param s
     * @param listener
     * @return
     */
    private Boolean connectToServer(final Server s, final PrimeConnectionListener listener) {
        int tryNum = 0;
        Exception lastException = null;
        totalCounter.increment();
        boolean success = false;
        do {
            try {
                logger.debug("Executing PrimeConnections request to server {} with path {}, tryNum={}",
                	s, primeConnectionsURIPath, tryNum);
                success = connector.connect(s, primeConnectionsURIPath);
                successCounter.increment();
                lastException = null;
                break;
            } catch (Exception e) {
                // It does not really matter if there was an exception,
                // the goal here is to attempt "priming/opening" the route
                // in ec2 .. actual http results do not matter
                logger.debug("Error connecting to server: {}", e.getMessage());
                lastException = e;
                sleepBeforeRetry(tryNum);
            } 
            logger.debug("server:{}, result={}, tryNum={}, maxRetries={}", s, success, tryNum, maxRetries);
            // 捕获到异常 沉睡后进行重试
            tryNum++;
        } while (!success && (tryNum <= maxRetries));
        // set the alive flag so that it can be used by load balancers
        if (listener != null) {
            try {
                // 完成后触发监听器
                listener.primeCompleted(s, lastException);
            } catch (Exception e) {
                logger.error("Error calling PrimeComplete listener for server '{}'", s, e);
            }
        }
        logger.debug("Either done, or quitting server:{}, result={}, tryNum={}, maxRetries={}", 
        	s, success, tryNum, maxRetries);
        return success;
    }

    private void sleepBeforeRetry(int tryNum) {
        try {
            int sleep = (tryNum + 1) * 100;
            logger.debug("Sleeping for " + sleep + "ms ...");
            Thread.sleep(sleep); // making this seconds based is too slow
            // i.e. 200ms, 400 ms, 800ms, 1600ms etc.
        } catch (InterruptedException ex) {
        }
    }

    /**
     * 异步连接线程工厂
     */
    static class ASyncPrimeConnectionsThreadFactory implements ThreadFactory {
        /**
         * 组号
         */
        private static final AtomicInteger groupNumber = new AtomicInteger(1);
        /**
         * 线程组对象
         */
        private final ThreadGroup group;
        /**
         * 线程编号
         */
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        /**
         * 名称前缀
         */
        private final String namePrefix;

        ASyncPrimeConnectionsThreadFactory(String name) {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup(); // NOPMD
            namePrefix = "ASyncPrimeConnectionsThreadFactory-" + name + "-"
                    + groupNumber.getAndIncrement() + "-thread-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix
                    + threadNumber.getAndIncrement(), 0);
            if (!t.isDaemon())
                t.setDaemon(true);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}
