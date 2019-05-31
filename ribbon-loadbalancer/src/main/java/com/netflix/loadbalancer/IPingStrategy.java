package com.netflix.loadbalancer;

/**
 * Defines the strategy, used to ping all servers, registered in
 * <c>com.netflix.loadbalancer.BaseLoadBalancer</c>. You would
 * typically create custom implementation of this interface, if you
 * want your servers to be pinged in parallel. <b>Please note,
 * that implementations of this interface should be immutable.</b>
 *
 * @author Dmitry_Cherkas
 * @see Server
 * @see IPing
 *      通过一个 能检测服务是否存活的对象 和一组服务信息 能返回对应的 ping结果
 */
public interface IPingStrategy {

    boolean[] pingServers(IPing ping, Server[] servers);
}
