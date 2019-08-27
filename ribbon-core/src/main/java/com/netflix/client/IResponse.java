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

import java.io.Closeable;
import java.net.URI;
import java.util.Map;

/**
 * Response interface for the client framework.  
 * 代表client 发起请求后返回的 响应对象
 */
public interface IResponse extends Closeable
{
   
   /**
    * Returns the raw entity if available from the response
    * 获取res 中存放的原始数据
    */
   public Object getPayload() throws ClientException;
      
   /**
    * A "peek" kinda API. Use to check if your service returned a response with an Entity
    * 判断该res 是否包含 数据
    */
   public boolean hasPayload();
   
   /**
    * @return true if the response is deemed success, for example, 200 response code for http protocol.
    * 判断本次请求是否成功
    */
   public boolean isSuccess();
   
      
   /**
    * Return the Request URI that generated this response
    * 获取请求的 url
    */
   public URI getRequestedURI();
   
   /**
    * 
    * @return Headers if any in the response.
    * 获取请求头
    */
   public Map<String, ?> getHeaders();   
}
