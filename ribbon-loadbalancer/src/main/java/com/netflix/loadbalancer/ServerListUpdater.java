package com.netflix.loadbalancer;

/**
 * strategy for {@link com.netflix.loadbalancer.DynamicServerListLoadBalancer} to use for different ways
 * of doing dynamic server list updates.
 *
 * @author David Liu
 * 用于更新服务列表的 接口
 */
public interface ServerListUpdater {

    /**
     * an interface for the updateAction that actually executes a server list update
     * 内部定义了一个 执行 update 动作的接口
     */
    public interface UpdateAction {
        void doUpdate();
    }


    /**
     * start the serverList updater with the given update action
     * This call should be idempotent.
     *
     * @param updateAction
     *      开始更新
     */
    void start(UpdateAction updateAction);

    /**
     * stop the serverList updater. This call should be idempotent
     *      停止
     */
    void stop();

    /**
     * @return the last update timestamp as a {@link java.util.Date} string
     *      获取最后一次更新的时间戳
     */
    String getLastUpdate();

    /**
     * @return the number of ms that has elapsed since last update
     *      应该是当前时间 距离上次更新的 时间差
     */
    long getDurationSinceLastUpdateMs();

    /**
     * @return the number of update cycles missed, if valid
     *      获取错过的更新周期数
     */
    int getNumberMissedCycles();

    /**
     * @return the number of threads used, if vaid
     *      获取核心线程数
     */
    int getCoreThreads();
}
