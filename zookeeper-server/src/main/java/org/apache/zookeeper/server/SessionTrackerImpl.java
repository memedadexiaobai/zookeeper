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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a full featured SessionTracker. It tracks session in grouped by tick
 * interval. It always rounds up the tick interval to provide a sort of grace
 * period. Sessions are thus expired in batches made up of sessions that expire
 * in a given interval.
 */
public class SessionTrackerImpl extends ZooKeeperCriticalThread implements SessionTracker {

    private static final Logger LOG = LoggerFactory.getLogger(SessionTrackerImpl.class);

    protected final ConcurrentHashMap<Long, SessionImpl> sessionsById = new ConcurrentHashMap<Long, SessionImpl>();

    private final ExpiryQueue<SessionImpl> sessionExpiryQueue;

    protected final ConcurrentMap<Long, Integer> sessionsWithTimeout;
    private final AtomicLong nextSessionId = new AtomicLong();

    public static class SessionImpl implements Session {

        SessionImpl(long sessionId, int timeout) {
            this.sessionId = sessionId;
            this.timeout = timeout;
            isClosing = false;
        }

        final long sessionId;
        final int timeout;
        boolean isClosing;

        Object owner;

        public long getSessionId() {
            return sessionId;
        }
        public int getTimeout() {
            return timeout;
        }
        public boolean isClosing() {
            return isClosing;
        }

        public String toString() {
            return "0x" + Long.toHexString(sessionId);
        }

    }

    /**
     * Generates an initial sessionId.
     *
     * <p>High order 1 byte is serverId, next 5 bytes are from timestamp, and low order 2 bytes are 0s.
     * Use {@literal ">>> 8"}, not {@literal ">> 8"} to make sure that the high order 1 byte is entirely
     * up to the server Id.
     *
     * <p>See also http://jira.apache.org/jira/browse/ZOOKEEPER-1622
     *
     * @param id server Id 服务器 ID（1 字节，0~255，集群唯一）
     * @return the session Id
     *
     * 生成一个 8 字节（64 位）全局唯一 sessionId：
     *  sessionId = 1 字节服务器 ID + 7 字节时间戳（精确到毫秒）
     *
     * [ 8位 服务器ID ] [ 56位 毫秒时间戳 ]
     * bit 63        56 55                     0
     * +---------------+-------------------------+
     * |  serverId(8b) |    timestamp (56b)      |
     * +---------------+-------------------------+
     * 好处：
     *  ✅ 全局唯一
     *      服务器 ID 占高 8 位 → 集群内不重复
     *      时间戳精确到毫秒 → 单机上不重复
     *      56 位时间戳 → 几万年不重复
     *  ✅ 有序递增
     *      sessionId 随时间递增，方便排序、过期、管理。
     *  ✅ 紧凑高效
     *      8 字节 long，网络传输、存储都极快。
     *
     * 假设：
     *  服务器 ID = 1
     *  当前时间戳 = 1700000000000
     * 生成：
     *  serverId << 56    = 0x0100000000000000
     *  timestamp 部分    = 0x00123456789ABCDE
     *  最终 sessionId    = 0x01123456789ABCDE
     */
    public static long initializeNextSessionId(long id) {
        long nextSid;
        /**
         * 把时间戳严格限制在 56 位以内！并且把最高 8 位全部清空，留给服务器 ID！
         *
         * 原始时间戳：1700000000000
         * 原始二进制：00000000 00000000 00011000 11010101 00110001 10001100 01101000 00000000
         * ---------------------------------------
         * 第一步 <<24：1813311628832768000
         * <<24 后二进制：00011000 11010101 00110001 10001100 01101000 00000000 00000000 00000000
         * ---------------------------------------
         * 第二步 >>>8：7083248549932640000
         * <<24 >>>8 后二进制：00000000 00011000 11010101 00110001 10001100 01101000 00000000 00000000
         * ---------------------------------------
         * serverId = 1
         * 最终 sessionId：8250489191891091456
         * 最终64位二进制：00000001 00011000 11010101 00110001 10001100 01101000 00000000 00000000
         *
         * 为什么要 << 24？
         *  因为 Java 的 long 是 64 位，而我们的目标是：
         *      高 8 位 = 服务器 ID
         *      低 56 位 = 时间戳
         *  时间戳（毫秒）现在是 40 多位二进制，未来很多年也不会超过 56 位。
         *  但为了 100% 确保时间戳绝对不会污染高 8 位，ZK 做了一个强制清空操作：
         *  第一步：<< 24
         *      把时间戳向左推 24 位，让最低 24 位全部变成 0。
         *  第二步：>>> 8
         *      再无符号右移 8 位，高位补 0。
         * << 24  +  >>> 8
         * = 整体左移了 16 位？
         * 不！
         * 真正效果：
         * 【高 8 位强制变成 00000000】
         * 【低 56 位保留时间戳】
         *  因为高 8 位已经是 00000000，所以 | 操作可以完美把服务器 ID 放进去，不会覆盖、不会冲突！
         *  00000000  xxxxxxxxxxxxx...（时间戳）
         *  OR
         *  00000001  00000000...（serverId <<56）
         *  =
         *  00000001  xxxxxxxxxxxxx...（最终sessionId）
         */
        nextSid = (Time.currentElapsedTime() << 24) >>> 8; // 把时间戳放到低 56 位,高 8 位清空留空
        // id << 56：把服务器 ID 左移 56 位 → 放到最高 8 位
        // |：按位或，把两部分拼起来
        nextSid = nextSid | (id << 56);
        if (nextSid == EphemeralType.CONTAINER_EPHEMERAL_OWNER) {
            ++nextSid;  // this is an unlikely edge case, but check it just in case 防止 sessionId 和特殊节点 ownerId 冲突
        }
        return nextSid;
    }

    private final SessionExpirer expirer;

    public SessionTrackerImpl(SessionExpirer expirer, ConcurrentMap<Long, Integer> sessionsWithTimeout, int tickTime, long serverId, ZooKeeperServerListener listener) {
        super("SessionTracker", listener);
        this.expirer = expirer;
        this.sessionExpiryQueue = new ExpiryQueue<SessionImpl>(tickTime);
        this.sessionsWithTimeout = sessionsWithTimeout;
        this.nextSessionId.set(initializeNextSessionId(serverId));
        for (Entry<Long, Integer> e : sessionsWithTimeout.entrySet()) {
            trackSession(e.getKey(), e.getValue());
        }

        EphemeralType.validateServerId(serverId);
    }

    volatile boolean running = true;

    public void dumpSessions(PrintWriter pwriter) {
        pwriter.print("Session ");
        sessionExpiryQueue.dump(pwriter);
    }

    /**
     * Returns a mapping from time to session IDs of sessions expiring at that time.
     */
    public synchronized Map<Long, Set<Long>> getSessionExpiryMap() {
        // Convert time -> sessions map to time -> session IDs map
        Map<Long, Set<SessionImpl>> expiryMap = sessionExpiryQueue.getExpiryMap();
        Map<Long, Set<Long>> sessionExpiryMap = new TreeMap<Long, Set<Long>>();
        for (Entry<Long, Set<SessionImpl>> e : expiryMap.entrySet()) {
            Set<Long> ids = new HashSet<Long>();
            sessionExpiryMap.put(e.getKey(), ids);
            for (SessionImpl s : e.getValue()) {
                ids.add(s.sessionId);
            }
        }
        return sessionExpiryMap;
    }

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pwriter = new PrintWriter(sw);
        dumpSessions(pwriter);
        pwriter.flush();
        pwriter.close();
        return sw.toString();
    }

    @Override
    public void run() {
        try {
            while (running) {
                long waitTime = sessionExpiryQueue.getWaitTime();
                if (waitTime > 0) {
                    Thread.sleep(waitTime);
                    continue;
                }

                for (SessionImpl s : sessionExpiryQueue.poll()) {
                    ServerMetrics.getMetrics().STALE_SESSIONS_EXPIRED.add(1);
                    setSessionClosing(s.sessionId);
                    expirer.expire(s);
                }
            }
        } catch (InterruptedException e) {
            handleException(this.getName(), e);
        }
        LOG.info("SessionTrackerImpl exited loop!");
    }

    public synchronized boolean touchSession(long sessionId, int timeout) {
        SessionImpl s = sessionsById.get(sessionId);

        if (s == null) {
            logTraceTouchInvalidSession(sessionId, timeout);
            return false;
        }

        if (s.isClosing()) {
            logTraceTouchClosingSession(sessionId, timeout);
            return false;
        }

        updateSessionExpiry(s, timeout);
        return true;
    }

    private void updateSessionExpiry(SessionImpl s, int timeout) {
        logTraceTouchSession(s.sessionId, timeout, "");
        sessionExpiryQueue.update(s, timeout);
    }

    private void logTraceTouchSession(long sessionId, int timeout, String sessionStatus) {
        if (LOG.isTraceEnabled()) {
            String msg = MessageFormat.format(
                "SessionTrackerImpl --- Touch {0}session: 0x{1} with timeout {2}",
                sessionStatus,
                Long.toHexString(sessionId),
                Integer.toString(timeout));

            ZooTrace.logTraceMessage(LOG, ZooTrace.CLIENT_PING_TRACE_MASK, msg);
        }
    }

    private void logTraceTouchInvalidSession(long sessionId, int timeout) {
        logTraceTouchSession(sessionId, timeout, "invalid ");
    }

    private void logTraceTouchClosingSession(long sessionId, int timeout) {
        logTraceTouchSession(sessionId, timeout, "closing ");
    }

    public int getSessionTimeout(long sessionId) {
        return sessionsWithTimeout.get(sessionId);
    }

    public synchronized void setSessionClosing(long sessionId) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Session closing: 0x{}", Long.toHexString(sessionId));
        }

        SessionImpl s = sessionsById.get(sessionId);
        if (s == null) {
            return;
        }
        s.isClosing = true;
    }

    public synchronized void removeSession(long sessionId) {
        LOG.debug("Removing session 0x{}", Long.toHexString(sessionId));
        SessionImpl s = sessionsById.remove(sessionId);
        sessionsWithTimeout.remove(sessionId);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.SESSION_TRACE_MASK,
                "SessionTrackerImpl --- Removing session 0x" + Long.toHexString(sessionId));
        }
        if (s != null) {
            sessionExpiryQueue.remove(s);
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");

        running = false;
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(), "Shutdown SessionTrackerImpl!");
        }
    }

    public long createSession(int sessionTimeout) {
        long sessionId = nextSessionId.getAndIncrement();
        trackSession(sessionId, sessionTimeout);
        return sessionId;
    }

    @Override
    public synchronized boolean trackSession(long id, int sessionTimeout) {
        boolean added = false;

        SessionImpl session = sessionsById.get(id);
        if (session == null) {
            session = new SessionImpl(id, sessionTimeout);
        }

        // findbugs2.0.3 complains about get after put.
        // long term strategy would be use computeIfAbsent after JDK 1.8
        SessionImpl existedSession = sessionsById.putIfAbsent(id, session);

        if (existedSession != null) {
            session = existedSession;
        } else {
            added = true;
            LOG.debug("Adding session 0x{}", Long.toHexString(id));
        }

        if (LOG.isTraceEnabled()) {
            String actionStr = added ? "Adding" : "Existing";
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.SESSION_TRACE_MASK,
                "SessionTrackerImpl --- " + actionStr
                + " session 0x" + Long.toHexString(id) + " " + sessionTimeout);
        }

        updateSessionExpiry(session, sessionTimeout);
        return added;
    }

    public synchronized boolean commitSession(long id, int sessionTimeout) {
        return sessionsWithTimeout.put(id, sessionTimeout) == null;
    }

    public boolean isTrackingSession(long sessionId) {
        return sessionsById.containsKey(sessionId);
    }

    public synchronized void checkSession(long sessionId, Object owner) throws KeeperException.SessionExpiredException, KeeperException.SessionMovedException, KeeperException.UnknownSessionException {
        LOG.debug("Checking session 0x{}", Long.toHexString(sessionId));
        SessionImpl session = sessionsById.get(sessionId);

        if (session == null) {
            throw new KeeperException.UnknownSessionException();
        }

        if (session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }

        if (session.owner == null) {
            session.owner = owner;
        } else if (session.owner != owner) {
            throw new KeeperException.SessionMovedException();
        }
    }

    public synchronized void setOwner(long id, Object owner) throws SessionExpiredException {
        SessionImpl session = sessionsById.get(id);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        session.owner = owner;
    }

    public void checkGlobalSession(long sessionId, Object owner) throws KeeperException.SessionExpiredException, KeeperException.SessionMovedException {
        try {
            checkSession(sessionId, owner);
        } catch (KeeperException.UnknownSessionException e) {
            throw new KeeperException.SessionExpiredException();
        }
    }

    public long getLocalSessionCount() {
        return 0;
    }

    @Override
    public boolean isLocalSessionsEnabled() {
        return false;
    }

    public Set<Long> globalSessions() {
        return sessionsById.keySet();
    }

    public Set<Long> localSessions() {
        return Collections.emptySet();
    }
}
