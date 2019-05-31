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
 * Given that
 * {@link IRule} can be cascaded, this {@link RetryRule} class allows adding a retry logic to an existing Rule.
 * 
 * @author stonse
 * 		可重试规则
 */
public class RetryRule extends AbstractLoadBalancerRule {
	/**
	 * 轮询规则 看来每次获取失败后 会通过轮询规则获取下一个服务对象
	 */
	IRule subRule = new RoundRobinRule();
	/**
	 * 每次尝试重试的最大耗时 如果超过该时间 则抛出异常
	 */
	long maxRetryMillis = 500;

	public RetryRule() {
	}

	/**
	 * 可以选择使用指定的 规则进行重试
	 * @param subRule
	 */
	public RetryRule(IRule subRule) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	}

	public RetryRule(IRule subRule, long maxRetryMillis) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
		this.maxRetryMillis = (maxRetryMillis > 0) ? maxRetryMillis : 500;
	}

	public void setRule(IRule subRule) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	}

	public IRule getRule() {
		return subRule;
	}

	public void setMaxRetryMillis(long maxRetryMillis) {
		if (maxRetryMillis > 0) {
			this.maxRetryMillis = maxRetryMillis;
		} else {
			this.maxRetryMillis = 500;
		}
	}

	public long getMaxRetryMillis() {
		return maxRetryMillis;
	}

	
	
	@Override
	public void setLoadBalancer(ILoadBalancer lb) {		
		super.setLoadBalancer(lb);
		subRule.setLoadBalancer(lb);
	}

	/*
	 * Loop if necessary. Note that the time CAN be exceeded depending on the
	 * subRule, because we're not spawning additional threads and returning
	 * early.
	 */
	public Server choose(ILoadBalancer lb, Object key) {
		//首先获取当前时间
		long requestTime = System.currentTimeMillis();
		//获取终止时间
		long deadline = requestTime + maxRetryMillis;

		Server answer = null;
		//尝试获取服务对象
		answer = subRule.choose(key);

		if (((answer == null) || (!answer.isAlive()))
				&& (System.currentTimeMillis() < deadline)) {

			//生成一个 会在指定时间中断自身(线程) 的任务 并非真的终止线程 而是设置一个标识
			InterruptTask task = new InterruptTask(deadline
					- System.currentTimeMillis());

			//自旋 检测标识是否被设置 未被标识的情况下就不断的自旋
			while (!Thread.interrupted()) {
				answer = subRule.choose(key);

				if (((answer == null) || (!answer.isAlive()))
						&& (System.currentTimeMillis() < deadline)) {
					/* pause and retry hoping it's transient */
					//该方法是本线程尽可能让出cpu分片 使得其他线程能正常执行 在下次归还cpu分片时 继续检测当前线程状态
					Thread.yield();
				} else {
					break;
				}
			}

			task.cancel();
		}

		if ((answer == null) || (!answer.isAlive())) {
			return null;
		} else {
			return answer;
		}
	}

	@Override
	public Server choose(Object key) {
		return choose(getLoadBalancer(), key);
	}
}
