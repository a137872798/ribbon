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
package com.netflix.client.config;

/**
 * Defines the key used in {@link IClientConfig}. See {@link CommonClientConfigKey}
 * for the commonly defined client configuration keys.
 * 
 * @author awang
 *		config 一般是 key:value 的形式  这里 增加了 关于 key 的功能
 */
public interface IClientConfigKey<T> {

    @SuppressWarnings("rawtypes")
	final class Keys extends CommonClientConfigKey {
        private Keys(String configKey) {
            super(configKey);
        }
    }
    
	/**
	 * @return string representation of the key used for hash purpose.
	 */
	String key();
	
	/**
     * @return Data type for the key. For example, Integer.class.
	 * 返回该config 是 什么类型 比如 port 可能就是 Integer 类型
	 */
	Class<T> type();

	/**
	 * 返回该配置的默认值
	 * @return
	 */
	default T defaultValue() { return null; }

	/**
	 * 处理该 configKey 属性 并返回一个新的 configKey
	 * @param args
	 * @return
	 */
	default IClientConfigKey<T> format(Object ... args) {
		return create(String.format(key(), args), type(), defaultValue());
	}

	default IClientConfigKey<T> create(String key, Class<T> type, T defaultValue) {
		return new IClientConfigKey<T>() {

			@Override
			public int hashCode() {
				return key().hashCode();
			}

			@Override
			public boolean equals(Object obj) {
				if (obj instanceof IClientConfigKey) {
					return key().equals(((IClientConfigKey)obj).key());
				}
				return false;
			}

			@Override
			public String toString() {
				return key();
			}

			@Override
			public String key() {
				return key;
			}

			@Override
			public Class<T> type() {
				return type;
			}

			@Override
			public T defaultValue() { return defaultValue; }
		};
	}
}
