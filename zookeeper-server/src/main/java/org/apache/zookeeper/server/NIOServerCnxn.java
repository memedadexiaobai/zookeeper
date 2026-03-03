/*
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

package org.apache.zookeeper.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.ClientCnxn;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.NIOServerCnxnFactory.SelectorThread;
import org.apache.zookeeper.server.command.CommandExecutor;
import org.apache.zookeeper.server.command.FourLetterCommands;
import org.apache.zookeeper.server.command.NopCommand;
import org.apache.zookeeper.server.command.SetTraceMaskCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class handles communication with clients using NIO. There is one per
 * client, but only one thread doing the communication.
 */
public class NIOServerCnxn extends ServerCnxn {

    private static final Logger LOG = LoggerFactory.getLogger(NIOServerCnxn.class);

    private final NIOServerCnxnFactory factory;

    private final SocketChannel sock;

    private final SelectorThread selectorThread;

    private final SelectionKey sk;

    private boolean initialized;

    private final ByteBuffer lenBuffer = ByteBuffer.allocate(4);

    protected ByteBuffer incomingBuffer = lenBuffer;

    private final Queue<ByteBuffer> outgoingBuffers = new LinkedBlockingQueue<ByteBuffer>();

    private int sessionTimeout;

    /**
     * This is the id that uniquely identifies the session of a client. Once
     * this session is no longer active, the ephemeral nodes will go away.
     */
    private long sessionId;

    /**
     * Client socket option for TCP keepalive
     */
    private final boolean clientTcpKeepAlive = Boolean.getBoolean("zookeeper.clientTcpKeepAlive");

    public NIOServerCnxn(ZooKeeperServer zk, SocketChannel sock, SelectionKey sk, NIOServerCnxnFactory factory, SelectorThread selectorThread) throws IOException {
        super(zk);
        this.sock = sock;
        this.sk = sk;
        this.factory = factory;
        this.selectorThread = selectorThread;
        if (this.factory.login != null) {
            this.zooKeeperSaslServer = new ZooKeeperSaslServer(factory.login);
        }
        sock.socket().setTcpNoDelay(true);
        /* set socket linger to false, so that socket close does not block */
        sock.socket().setSoLinger(false, -1);
        sock.socket().setKeepAlive(clientTcpKeepAlive);
        InetAddress addr = ((InetSocketAddress) sock.socket().getRemoteSocketAddress()).getAddress();
        addAuthInfo(new Id("ip", addr.getHostAddress()));
        this.sessionTimeout = factory.sessionlessCnxnTimeout;
    }

    /* Send close connection packet to the client, doIO will eventually
     * close the underlying machinery (like socket, selectorkey, etc...)
     */
    public void sendCloseSession() {
        sendBuffer(ServerCnxnFactory.closeConn);
    }

    /**
     * send buffer without using the asynchronous
     * calls to selector and then close the socket
     * @param bb
     */
    void sendBufferSync(ByteBuffer bb) {
        try {
            /* configure socket to be blocking
             * so that we dont have to do write in
             * a tight while loop
             */
            if (bb != ServerCnxnFactory.closeConn) {
                if (sock.isOpen()) {
                    sock.configureBlocking(true);
                    sock.write(bb);
                }
                packetSent();
            }
        } catch (IOException ie) {
            LOG.error("Error sending data synchronously ", ie);
        }
    }

    /**
     * sendBuffer pushes a byte buffer onto the outgoing buffer queue for
     * asynchronous writes.
     */
    public void sendBuffer(ByteBuffer... buffers) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Add a buffer to outgoingBuffers, sk {} is valid: {}", sk, sk.isValid());
        }

        synchronized (outgoingBuffers) {
            for (ByteBuffer buffer : buffers) {
                outgoingBuffers.add(buffer);
            }
            outgoingBuffers.add(packetSentinel);
        }
        requestInterestOpsUpdate();
    }

    /**
     * When read on socket failed, this is typically because client closed the
     * connection. In most cases, the client does this when the server doesn't
     * respond within 2/3 of session timeout. This possibly indicates server
     * health/performance issue, so we need to log and keep track of stat
     *
     * @throws EndOfStreamException
     */
    private void handleFailedRead() throws EndOfStreamException {
        setStale();
        ServerMetrics.getMetrics().CONNECTION_DROP_COUNT.add(1);
        throw new EndOfStreamException("Unable to read additional data from client,"
                                       + " it probably closed the socket:"
                                       + " address = " + sock.socket().getRemoteSocketAddress() + ","
                                       + " session = 0x" + Long.toHexString(sessionId),
                                       DisconnectReason.UNABLE_TO_READ_FROM_CLIENT);
    }

    /** Read the request payload (everything following the length prefix) */
    private void readPayload() throws IOException, InterruptedException, ClientCnxnLimitException {
        if (incomingBuffer.remaining() != 0) { // have we read length bytes? 应该把incomingBuffer中数据读取完，这里还没读完 再读取下
            int rc = sock.read(incomingBuffer); // sock is non-blocking, so ok
            if (rc < 0) {
                handleFailedRead();
            }
        }

        if (incomingBuffer.remaining() == 0) { // have we read length bytes? 全部读取完了
            incomingBuffer.flip();
            // 这里是总共收到的字节数 flip切换模式，4 + incomingBuffer.remaining() 就是本轮读取到的所有的字节数
            packetReceived(4 + incomingBuffer.remaining());
            if (!initialized) {//未初始化请求
                readConnectRequest();//连接请求处理
            } else {
                readRequest();//普通请求
            }
            // 恢复下incomingBuffer 处理下一批数据
            lenBuffer.clear();
            incomingBuffer = lenBuffer;
        }
    }

    /**
     * This boolean tracks whether the connection is ready for selection or
     * not. A connection is marked as not ready for selection while it is
     * processing an IO request. The flag is used to gatekeep pushing interest
     * op updates onto the selector.
     */
    private final AtomicBoolean selectable = new AtomicBoolean(true);

    public boolean isSelectable() {
        return sk.isValid() && selectable.get();
    }

    public void disableSelectable() {
        selectable.set(false);
    }

    public void enableSelectable() {
        selectable.set(true);
    }

    private void requestInterestOpsUpdate() {
        if (isSelectable()) {
            selectorThread.addInterestOpsUpdateRequest(sk);
        }
    }

    void handleWrite(SelectionKey k) throws IOException {
        if (outgoingBuffers.isEmpty()) {
            return;
        }

        /*
         * This is going to reset the buffer position to 0 and the
         * limit to the size of the buffer, so that we can fill it
         * with data from the non-direct buffers that we need to
         * send.
         */
        ByteBuffer directBuffer = NIOServerCnxnFactory.getDirectBuffer();
        if (directBuffer == null) {
            ByteBuffer[] bufferList = new ByteBuffer[outgoingBuffers.size()];
            // Use gathered write call. This updates the positions of the
            // byte buffers to reflect the bytes that were written out.
            sock.write(outgoingBuffers.toArray(bufferList));

            // Remove the buffers that we have sent
            ByteBuffer bb;
            while ((bb = outgoingBuffers.peek()) != null) {
                if (bb == ServerCnxnFactory.closeConn) {
                    throw new CloseRequestException("close requested", DisconnectReason.CLIENT_CLOSED_CONNECTION);
                }
                if (bb == packetSentinel) {
                    packetSent();
                }
                if (bb.remaining() > 0) {
                    break;
                }
                outgoingBuffers.remove();
            }
        } else {
            directBuffer.clear();

            for (ByteBuffer b : outgoingBuffers) {
                if (directBuffer.remaining() < b.remaining()) {
                    /*
                     * When we call put later, if the directBuffer is to
                     * small to hold everything, nothing will be copied,
                     * so we've got to slice the buffer if it's too big.
                     */
                    b = (ByteBuffer) b.slice().limit(directBuffer.remaining());
                }
                /*
                 * put() is going to modify the positions of both
                 * buffers, put we don't want to change the position of
                 * the source buffers (we'll do that after the send, if
                 * needed), so we save and reset the position after the
                 * copy
                 */
                int p = b.position();
                directBuffer.put(b);
                b.position(p);
                if (directBuffer.remaining() == 0) {
                    break;
                }
            }
            /*
             * Do the flip: limit becomes position, position gets set to
             * 0. This sets us up for the write.
             */
            directBuffer.flip();

            int sent = sock.write(directBuffer);

            ByteBuffer bb;

            // Remove the buffers that we have sent
            while ((bb = outgoingBuffers.peek()) != null) {
                if (bb == ServerCnxnFactory.closeConn) {
                    throw new CloseRequestException("close requested", DisconnectReason.CLIENT_CLOSED_CONNECTION);
                }
                if (bb == packetSentinel) {
                    packetSent();
                }
                if (sent < bb.remaining()) {
                    /*
                     * We only partially sent this buffer, so we update
                     * the position and exit the loop.
                     */
                    bb.position(bb.position() + sent);
                    break;
                }
                /* We've sent the whole buffer, so drop the buffer */
                sent -= bb.remaining();
                outgoingBuffers.remove();
            }
        }
    }

    /**
     * Only used in order to allow testing
     */
    protected boolean isSocketOpen() {
        return sock.isOpen();
    }

    /**
     * Handles read/write IO on connection.
     *
     * Zookeeper 是 TCP 长连接（不是短连接，不是 HTTP）
     *  一个客户端 只建立一条 TCP 连接，全程复用
     *  连接建立时：只触发一次 OP_ACCEPT
     *  连接建立后：永远只触发 OP_READ / OP_WRITE
     *  直到客户端断开，才会关闭连接
     *
     * 为什么必须长连接？
     *  Session 会话机制（ZK 核心）
     *      会话 ID 绑定在 TCP 连接上
     *      心跳（ping）通过这条连接发送
     *      断开 = 会话失效
     *  高并发请求复用一条连接
     *      create /get/set /delete 都走这一条连接
     *  心跳保活
     *      客户端每隔 tickTime 发送心跳
     *      服务端感知客户端存活
     *
     * accept 只在【第一次建立连接】时触发一次！流程：
     *  客户端 connect → 服务端 TCP 握手
     *  服务端 OP_ACCEPT 事件触发
     *  AcceptThread 处理 accept()
     *  生成新的 SocketChannel
     *  注册到 SelectorThread（监听 OP_READ）
     *  此后永远不会再触发 OP_ACCEPT（同一个连接不会再次 accept）
     * accept = 建立连接的那一瞬间，仅此一次！
     *
     * 连接建立成功后：
     *  不再有 accept
     *  不再有 connect
     *  只会出现两种事件：
     *      OP_READ：客户端发请求来了
     *      OP_WRITE：服务端要回包给客户端
     * Zookeeper 交互使用自定义的二进制协议，格式非常简单：
     * [ len ][ request payload ]
     * len：int（4 字节）
     * payload：ZK 的 Request 二进制序列化
     * [ len ][ response payload ]
     *
     * 客户端断开
     * → 发送 FIN→ 服务端内核接收→ 标记 socket 可读→ Selector 唤醒 → OP_READ→ 进入 read () 方法→ read () 返回 -1→ ZK 知道：客户端断开了→ 关闭连接、取消 SelectionKey、清理会话
     * ① 正常断开（客户端 close）→ OP_READ→ read () = -1→ 正常关闭
     * ② 异常断开（断网 / 崩溃）→ 内核等待超时→ 仍然触发 OP_READ→ read () = -1→ 同样关闭
     */
    void doIO(SelectionKey k) throws InterruptedException {
        try {
            if (!isSocketOpen()) {
                LOG.warn("trying to do i/o on a null socket for session: 0x{}", Long.toHexString(sessionId));

                return;
            }
            if (k.isReadable()) {
                // rc=本次读取的字节数 -1代表客户端断开连接
                // rc > 0 读到了有效数据= 读到了 rc 个字节= 正常情况
                // rc == 0 没读到数据，但连接还活着= 缓冲区空了= 不是错误，只是暂时没数据
                // rc < 0（固定返回 -1） 客户端已经断开连接（EOF）= 流结束= TCP 收到 FIN= 客户端关闭连接= ZK 必须 close () 这个连接
                int rc = sock.read(incomingBuffer);
                if (rc < 0) {
                    try {
                        handleFailedRead();
                    } catch (EndOfStreamException e) {
                        // no stacktrace. this case is very common, and it is usually not a problem.
                        LOG.info("{}", e.getMessage());
                        // expecting close to log session closure
                        close(e.getReason());
                        return;
                    }
                }
                // 返回的的值是 limit - position
                // ==0 时候说明走到头了 读满了
                if (incomingBuffer.remaining() == 0) {//缓冲区满了
                    boolean isPayload;
                    /**
                     * lenBuffer:
                     *  初始化：position=1 limit=4 capacity=4
                     *  新请求都从读取
                     *  incomingBuffer 在读取时候会发生变化，这里直接用==比较lenBuffer是否是同一个对象，来判断是否是新一个请求的开始
                      */
                    if (incomingBuffer == lenBuffer) { // start of next request
                        incomingBuffer.flip();
                        // 如果是一些命令的话 返回false，否则是true
                        isPayload = readLength(k);
                        incomingBuffer.clear();
                    } else {
                        // continuation
                        isPayload = true;
                    }
                    if (isPayload) { // not the case for 4letterword
                        readPayload();
                    } else {
                        // four letter words take care
                        // need not do anything else
                        return;
                    }
                }
            }
            if (k.isWritable()) {
                handleWrite(k);

                if (!initialized && !getReadInterest() && !getWriteInterest()) {
                    throw new CloseRequestException("responded to info probe", DisconnectReason.INFO_PROBE);
                }
            }
        } catch (CancelledKeyException e) {
            LOG.warn("CancelledKeyException causing close of session: 0x{}", Long.toHexString(sessionId));

            LOG.debug("CancelledKeyException stack trace", e);

            close(DisconnectReason.CANCELLED_KEY_EXCEPTION);
        } catch (CloseRequestException e) {
            // expecting close to log session closure
            close();
        } catch (EndOfStreamException e) {
            LOG.warn("Unexpected exception", e);
            // expecting close to log session closure
            close(e.getReason());
        } catch (ClientCnxnLimitException e) {
            // Common case exception, print at debug level
            ServerMetrics.getMetrics().CONNECTION_REJECTED.add(1);
            LOG.warn("Closing session 0x{}", Long.toHexString(sessionId), e);
            close(DisconnectReason.CLIENT_CNX_LIMIT);
        } catch (IOException e) {
            LOG.warn("Close of session 0x{}", Long.toHexString(sessionId), e);
            close(DisconnectReason.IO_EXCEPTION);
        }
    }

    protected void readRequest() throws IOException {
        zkServer.processPacket(this, incomingBuffer);
    }

    // returns whether we are interested in writing, which is determined
    // by whether we have any pending buffers on the output queue or not
    private boolean getWriteInterest() {
        return !outgoingBuffers.isEmpty();
    }

    // returns whether we are interested in taking new requests, which is
    // determined by whether we are currently throttled or not
    private boolean getReadInterest() {
        return !throttled.get();
    }

    // throttled：节流
    private final AtomicBoolean throttled = new AtomicBoolean(false);

    // Throttle acceptance of new requests. If this entailed a state change,
    // register an interest op update request with the selector.
    //
    // Don't support wait disable receive in NIO, ignore the parameter
    public void disableRecv(boolean waitDisableRecv) {
        if (throttled.compareAndSet(false, true)) {
            requestInterestOpsUpdate();
        }
    }

    // Disable throttling and resume acceptance of new requests. If this
    // entailed a state change, register an interest op update request with
    // the selector.
    public void enableRecv() {
        if (throttled.compareAndSet(true, false)) {
            requestInterestOpsUpdate();
        }
    }

    private void readConnectRequest() throws IOException, InterruptedException, ClientCnxnLimitException {
        if (!isZKServerRunning()) {
            throw new IOException("ZooKeeperServer not running");
        }
        zkServer.processConnectRequest(this, incomingBuffer);
        initialized = true;
    }

    /**
     * This class wraps the sendBuffer method of NIOServerCnxn. It is
     * responsible for chunking up the response to a client. Rather
     * than cons'ing up a response fully in memory, which may be large
     * for some commands, this class chunks up the result.
     */
    private class SendBufferWriter extends Writer {

        private StringBuffer sb = new StringBuffer();

        /**
         * Check if we are ready to send another chunk.
         * @param force force sending, even if not a full chunk
         */
        private void checkFlush(boolean force) {
            if ((force && sb.length() > 0) || sb.length() > 2048) {
                sendBufferSync(ByteBuffer.wrap(sb.toString().getBytes(UTF_8)));
                // clear our internal buffer
                sb.setLength(0);
            }
        }

        @Override
        public void close() throws IOException {
            if (sb == null) {
                return;
            }
            checkFlush(true);
            sb = null; // clear out the ref to ensure no reuse
        }

        @Override
        public void flush() throws IOException {
            checkFlush(true);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            sb.append(cbuf, off, len);
            checkFlush(false);
        }

    }
    /** Return if four letter word found and responded to, otw false **/
    private boolean checkFourLetterWord(final SelectionKey k, final int len) throws IOException {
        // We take advantage of the limited size of the length to look
        // for cmds. They are all 4-bytes which fits inside of an int
        // zk cmd都是4个字节的长度，这里通过 ByteBuffer的wrap方法，生成 int -> cmd 的映射，通过 int 来判断是否是支持的命令
        if (!FourLetterCommands.isKnown(len)) {
            return false;
        }

        //拿到具体的命令
        String cmd = FourLetterCommands.getCommandString(len);
        // 记录收到的包
        packetReceived(4);

        /** cancel the selection key to remove the socket handling from selector.
         *  This is to prevent netcat problem wherein netcat immediately closes the sending side
         *  after sending the  commands and still keeps the receiving channel open.
         * The idea is to remove the selectionkey from the selector
         * so that the selector does not notice the closed read on the
         * socket channel and keep the socket alive to write the data to
         * and makes sure to close the socket after its done writing the data
         *
         * netcat（nc）连接 ZK 时，比如：echo stat | nc localhost 2181
         * netcat 的行为是这样的：
         *  发送 stat 命令
         *  立刻关闭发送方向（shut down output）
         *  但 Socket 连接还没有完全关闭（还处于 CLOSE_WAIT）
         * 对 ZK 来说会发生什么灾难？
         *  ZK 读到 stat 命令
         *  准备回复
         *  但此时 客户端发送端已关闭
         *  Selector 会不断触发 OP_READ 事件
         *  ZK 不断读，但每次都读到 -1（EOF）
         *  死循环！CPU 100%！
         * 这就是 netcat problem！
         *
         * k.cancel() 做了什么？
         * 把这个 SelectionKey 从 Selector 中注销！→ Selector 不再监听这个连接的任何事件（OP_READ / OP_WRITE）→ 不会再触发空读循环→ 连接安全关闭
         *
         * 作用：强制注销 SelectionKey，避免 netcat 类客户端 “半关闭” 连接导致的空轮询、死循环、CPU 100%
         * 一句话总结：
         * netcat 问题 = 发完数据立刻关发送端，但不关连接 → ZK 无限触发读事件k.cancel () = 直接把这个连接从 Selector 里踢出去 → 彻底解决死循环
         *
         * 哪些客户端会触发这个问题？
         *  netcat (nc)
         *  curl
         *  简单脚本客户端
         *  异常断开的客户端
         *  4 字母命令（stat/ruok/conf）
         */
        if (k != null) {
            try {
                k.cancel();
            } catch (Exception e) {
                LOG.error("Error cancelling command selection key", e);
            }
        }

        final PrintWriter pwriter = new PrintWriter(new BufferedWriter(new SendBufferWriter()));

        // ZOOKEEPER-2693: don't execute 4lw if it's not enabled. 里边加了个白名单，哪些命令允许执行，比如srvr 这是zk需要的
        if (!FourLetterCommands.isEnabled(cmd)) {
            LOG.debug("Command {} is not executed because it is not in the whitelist.", cmd);
            NopCommand nopCmd = new NopCommand(
                pwriter,
                this,
                cmd + " is not executed because it is not in the whitelist.");
            nopCmd.start();
            return true;
        }

        LOG.info("Processing {} command from {}", cmd, sock.socket().getRemoteSocketAddress());

        if (len == FourLetterCommands.setTraceMaskCmd) {// stmk命令
            incomingBuffer = ByteBuffer.allocate(8);
            int rc = sock.read(incomingBuffer);
            if (rc < 0) {
                throw new IOException("Read error");
            }
            incomingBuffer.flip();
            long traceMask = incomingBuffer.getLong();
            ZooTrace.setTextTraceLevel(traceMask);
            SetTraceMaskCommand setMask = new SetTraceMaskCommand(pwriter, this, traceMask);
            setMask.start();
            return true;
        } else {
            CommandExecutor commandExecutor = new CommandExecutor();
            return commandExecutor.execute(this, pwriter, len, zkServer, factory);
        }
    }

    /** Reads the first 4 bytes of lenBuffer, which could be true length or
     *  four letter word.
     *
     * @param k selection key
     * @return true if length read, otw false (wasn't really the length)
     * @throws IOException if buffer size exceeds maxBuffer size
     */
    private boolean readLength(SelectionKey k) throws IOException {
        // Read the length, now get the buffer
        int len = lenBuffer.getInt();
        if (!initialized && checkFourLetterWord(sk, len)) {
            return false;
        }
        if (len < 0 || len > BinaryInputArchive.maxBuffer) {
            throw new IOException("Len error. "
                    + "A message from " +  this.getRemoteSocketAddress() + " with advertised length of " + len
                    + " is either a malformed message or too large to process"
                    + " (length is greater than jute.maxbuffer=" + BinaryInputArchive.maxBuffer + ")");
        }
        if (!isZKServerRunning()) {
            throw new IOException("ZooKeeperServer not running");
        }
        // checkRequestSize will throw IOException if request is rejected
        zkServer.checkRequestSizeWhenReceivingMessage(len);
        incomingBuffer = ByteBuffer.allocate(len);
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionTimeout()
     */
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    /**
     * Used by "dump" 4-letter command to list all connection in
     * cnxnExpiryMap
     */
    @Override
    public String toString() {
        return "ip: " + sock.socket().getRemoteSocketAddress() + " sessionId: 0x" + Long.toHexString(sessionId);
    }

    /**
     * Close the cnxn and remove it from the factory cnxns list.
     */
    @Override
    public void close(DisconnectReason reason) {
        disconnectReason = reason;
        close();
    }

    private void close() {
        setStale();
        if (!factory.removeCnxn(this)) {
            return;
        }

        if (zkServer != null) {
            zkServer.removeCnxn(this);
        }

        if (sk != null) {
            try {
                // need to cancel this selection key from the selector
                sk.cancel();
            } catch (Exception e) {
                LOG.debug("ignoring exception during selectionkey cancel", e);
            }
        }

        closeSock();
    }

    /**
     * Close resources associated with the sock of this cnxn.
     */
    private void closeSock() {
        if (!sock.isOpen()) {
            return;
        }

        String logMsg = String.format(
            "Closed socket connection for client %s %s",
            sock.socket().getRemoteSocketAddress(),
            sessionId != 0
                ? "which had sessionid 0x" + Long.toHexString(sessionId)
                : "(no session established for client)"
            );
        LOG.debug(logMsg);

        closeSock(sock);
    }

    /**
     * Close resources associated with a sock.
     */
    public static void closeSock(SocketChannel sock) {
        if (!sock.isOpen()) {
            return;
        }

        try {
            /*
             * The following sequence of code is stupid! You would think that
             * only sock.close() is needed, but alas, it doesn't work that way.
             * If you just do sock.close() there are cases where the socket
             * doesn't actually close...
             */
            sock.socket().shutdownOutput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            LOG.debug("ignoring exception during output shutdown", e);
        }
        try {
            sock.socket().shutdownInput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            LOG.debug("ignoring exception during input shutdown", e);
        }
        try {
            sock.socket().close();
        } catch (IOException e) {
            LOG.debug("ignoring exception during socket close", e);
        }
        try {
            sock.close();
        } catch (IOException e) {
            LOG.debug("ignoring exception during socketchannel close", e);
        }
    }

    private static final ByteBuffer packetSentinel = ByteBuffer.allocate(0);

    @Override
    public int sendResponse(ReplyHeader h, Record r, String tag, String cacheKey, Stat stat, int opCode) {
        int responseSize = 0;
        try {
            ByteBuffer[] bb = serialize(h, r, tag, cacheKey, stat, opCode);
            responseSize = bb[0].getInt();
            bb[0].rewind();
            sendBuffer(bb);
            decrOutstandingAndCheckThrottle(h);
        } catch (Exception e) {
            LOG.warn("Unexpected exception. Destruction averted.", e);
        }
        return responseSize;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#process(org.apache.zookeeper.proto.WatcherEvent)
     */
    @Override
    public void process(WatchedEvent event, List<ACL> znodeAcl) {
        try {
            zkServer.checkACL(this, znodeAcl, ZooDefs.Perms.READ, getAuthInfo(), event.getPath(), null);
        } catch (KeeperException.NoAuthException e) {
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(
                    LOG,
                    ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                    "Not delivering event " + event + " to 0x" + Long.toHexString(this.sessionId) + " (filtered by ACL)");
            }
            return;
        }
        ReplyHeader h = new ReplyHeader(ClientCnxn.NOTIFICATION_XID, -1L, 0);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                "Deliver event " + event + " to 0x" + Long.toHexString(this.sessionId) + " through " + this);
        }

        // Convert WatchedEvent to a type that can be sent over the wire
        WatcherEvent e = event.getWrapper();

        // The last parameter OpCode here is used to select the response cache.
        // Passing OpCode.error (with a value of -1) means we don't care, as we don't need
        // response cache on delivering watcher events.
        int responseSize = sendResponse(h, e, "notification", null, null, ZooDefs.OpCode.error);
        ServerMetrics.getMetrics().WATCH_BYTES.add(responseSize);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionId()
     */
    @Override
    public long getSessionId() {
        return sessionId;
    }

    @Override
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
        factory.addSession(sessionId, this);
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
        factory.touchCnxn(this);
    }

    @Override
    public int getInterestOps() {
        if (!isSelectable()) {
            return 0;
        }
        int interestOps = 0;
        if (getReadInterest()) {
            interestOps |= SelectionKey.OP_READ;
        }
        if (getWriteInterest()) {
            interestOps |= SelectionKey.OP_WRITE;
        }
        return interestOps;
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        if (!sock.isOpen()) {
            return null;
        }
        return (InetSocketAddress) sock.socket().getRemoteSocketAddress();
    }

    public InetAddress getSocketAddress() {
        if (!sock.isOpen()) {
            return null;
        }
        return sock.socket().getInetAddress();
    }

    @Override
    protected ServerStats serverStats() {
        if (zkServer == null) {
            return null;
        }
        return zkServer.serverStats();
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public Certificate[] getClientCertificateChain() {
        throw new UnsupportedOperationException("SSL is unsupported in NIOServerCnxn");
    }

    @Override
    public void setClientCertificateChain(Certificate[] chain) {
        throw new UnsupportedOperationException("SSL is unsupported in NIOServerCnxn");
    }

}
