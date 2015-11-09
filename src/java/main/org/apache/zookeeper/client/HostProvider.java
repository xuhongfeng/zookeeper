/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.client;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * A set of hosts a ZooKeeper client should connect to.
 * 
 * Classes implementing this interface must guarantee the following:
 * 
 * * Every call to next() returns an InetSocketAddress. So the iterator never
 * ends.
 * 
 * * The size() of a HostProvider may never be zero.
 * 
 * A HostProvider must return resolved InetSocketAddress instances on next(),
 * but it's up to the HostProvider, when it wants to do the resolving.
 * 
 * Different HostProvider could be imagined:
 * 
 * * A HostProvider that loads the list of Hosts from an URL or from DNS 
 * * A HostProvider that re-resolves the InetSocketAddress after a timeout. 
 * * A HostProvider that prefers nearby hosts.
 *
 * 维护一个服务器Socket列表
 *
 * HostProvider 会shuffle server列表
 * next() 以round-robin的方式返回一个server
 *
 * client可以调用updateServerList来动态更改server列表, HostProvider会通过对比新、旧的
 * serverList采取一定的策略来通知HostProvider的消费者(即Zookeeper client) 是否重新换一个
 * server来连接， 从而达到load balance的目的
 * 例如原来有3个server, updateServerList传进来5个server, 那么updateServerList会有
 * 40%的概率return true.  return true以为者告诉client 挑另外两个server中的一个重连
 *
 * HostProvider 的实现必须保证:
 * size() 不会返回0
 * next() 总能返回一个socket
 *
 * 不同的实现主要区别在于做DNS解析的策略
 * Zookeeper自带唯一一个实现是StaticHostProvider, 在实例化的时候即做DNS解析
 *
 */
public interface HostProvider {
    public int size();

    /**
     * The next host to try to connect to.
     * 
     * For a spinDelay of 0 there should be no wait.
     *
     * 如果已经到最后一个了, 以spin的方式等spinDelay毫秒. (所谓spin既是以类似while(true)的方式重试, 联想SpinLock)
     * spinDelay=0则不等
     *
     * 但是从StaticHostProvider的实现发现是以sleep的方式实现的
     * 
     * @param spinDelay
     *            Milliseconds to wait if all hosts have been tried once.
     */
    public InetSocketAddress next(long spinDelay);

    /**
     * Notify the HostProvider of a successful connection.
     * 
     * The HostProvider may use this notification to reset it's inner state.
     *
     * HostProvider的消费者和HostProvider.next()提供的server连接上后会回调onConnected()
     */
    public void onConnected();

    /**
     *
     * 如果需要重置HostProvider当前的连接， 则返回true. (用于load balance, 或当前连接不可用)
     *
     * Update the list of servers. This returns true if changing connections is necessary for load-balancing, false otherwise.
     * @param serverAddresses new host list
     * @param currentHost the host to which this client is currently connected
     * @return true if changing connections is necessary for load-balancing, false otherwise  
     */
    boolean updateServerList(Collection<InetSocketAddress> serverAddresses,
        InetSocketAddress currentHost);
}
