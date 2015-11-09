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

package org.apache.zookeeper;

import org.apache.jute.BinaryInputArchive;
import org.apache.zookeeper.ClientCnxn.Packet;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A ClientCnxnSocket does the lower level communication with a socket
 * implementation.
 * 
 * This code has been moved out of ClientCnxn so that a Netty implementation can
 * be provided as an alternative to the NIO socket code.
 * 
 */
abstract class ClientCnxnSocket {
    private static final Logger LOG = LoggerFactory.getLogger(ClientCnxnSocket.class);

    protected boolean initialized;

    /**
     * This buffer is only used to read the length of the incoming message.
     */
    protected final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);

    /**
     * After the length is read, a new incomingBuffer is allocated in
     * readLength() to receive the full message.
     */
    protected ByteBuffer incomingBuffer = lenBuffer;
    protected long sentCount = 0;
    protected long recvCount = 0;
    // 最后一次接收到数据的时间
    protected long lastHeard;
    // 最后一次接发送数据的时间
    protected long lastSend;
    protected long now;
    protected ClientCnxn.SendThread sendThread;
    protected LinkedBlockingDeque<Packet> outgoingQueue;

    /**
     * The sessionId is only available here for Log and Exception messages.
     * Otherwise the socket doesn't need to know it.
     */
    /**
     * sessionId 是ClientCnxn的成员变量, 这里复制了一个只为了打Log
     */
    protected long sessionId;

    void introduce(ClientCnxn.SendThread sendThread, long sessionId,
                   LinkedBlockingDeque<Packet> outgoingQueue) {
        this.sendThread = sendThread;
        this.sessionId = sessionId;
        this.outgoingQueue = outgoingQueue;
    }

    void updateNow() {
        now = Time.currentElapsedTime();
    }

    // 距离最近一次接收到数据的时间间隔
    int getIdleRecv() {
        return (int) (now - lastHeard);
    }

    // 距离最近一次发送数据的时间间隔
    int getIdleSend() {
        return (int) (now - lastSend);
    }

    long getSentCount() {
        return sentCount;
    }

    long getRecvCount() {
        return recvCount;
    }

    void updateLastHeard() {
        this.lastHeard = now;
    }

    void updateLastSend() {
        this.lastSend = now;
    }

    void updateLastSendAndHeard() {
        this.lastSend = now;
        this.lastHeard = now;
    }

    protected void readLength() throws IOException {
        int len = incomingBuffer.getInt();
        if (len < 0 || len >= ClientCnxn.packetLen) {
            throw new IOException("Packet len" + len + " is out of range!");
        }
        incomingBuffer = ByteBuffer.allocate(len);
    }

    void readConnectResult() throws IOException {
        if (LOG.isTraceEnabled()) {
            StringBuilder buf = new StringBuilder("0x[");
            for (byte b : incomingBuffer.array()) {
                buf.append(Integer.toHexString(b) + ",");
            }
            buf.append("]");
            LOG.trace("readConnectResult " + incomingBuffer.remaining() + " "
                    + buf.toString());
        }
        ByteBufferInputStream bbis = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
        ConnectResponse conRsp = new ConnectResponse();
        conRsp.deserialize(bbia, "connect");

        // read "is read-only" flag
        boolean isRO = false;
        try {
            isRO = bbia.readBool("readOnly");
        } catch (IOException e) {
            // this is ok -- just a packet from an old server which
            // doesn't contain readOnly field
            LOG.warn("Connected to an old server; r-o mode will be unavailable");
        }

        this.sessionId = conRsp.getSessionId();
        sendThread.onConnected(conRsp.getTimeOut(), this.sessionId,
                conRsp.getPasswd(), isRO);
    }

    abstract boolean isConnected();

    abstract void connect(InetSocketAddress addr) throws IOException;

    /**
     * Returns the address to which the socket is connected.
     */
    abstract SocketAddress getRemoteSocketAddress();

    /**
     * Returns the address to which the socket is bound.
     */
    abstract SocketAddress getLocalSocketAddress();

    /**
     * Clean up resources for a fresh new socket.
     * It's called before reconnect or close.
     */
    abstract void cleanup();

    /**
     * new packets are added to outgoingQueue.
     */
    abstract void packetAdded();

    /**
     * connState is marked CLOSED and notify ClientCnxnSocket to react.
     */
    abstract void onClosing();

    /**
     * Sasl completes. Allows non-priming packgets to be sent.
     * Note that this method will only be called if Sasl starts and completes.
     */
    abstract void saslCompleted();

    /**
     * being called after ClientCnxn finish PrimeConnection
     */
    abstract void connectionPrimed();

    /**
     * Do transportation work:
     * - read packets into incomingBuffer.
     * - write outgoing queue packets.
     * - update relevant timestamp.
     *
     * @param waitTimeOut timeout in blocking wait. Unit in MilliSecond.
     * @param pendingQueue These are the packets that have been sent and
     *                     are waiting for a response.
     * @param cnxn
     * @throws IOException
     * @throws InterruptedException
     */
    abstract void doTransport(int waitTimeOut, List<Packet> pendingQueue,
            ClientCnxn cnxn)
            throws IOException, InterruptedException;

    /**
     * Close the socket.
     */
    abstract void testableCloseSocket() throws IOException;

    /**
     * Close this client.
     */
    abstract void close();

    /**
     * Send Sasl packets directly.
     * The Sasl process will send the first (requestHeader == null) packet,
     * and then block the doTransport write,
     * finally unblock it when finished.
     */
    abstract void sendPacket(Packet p) throws IOException;
}
