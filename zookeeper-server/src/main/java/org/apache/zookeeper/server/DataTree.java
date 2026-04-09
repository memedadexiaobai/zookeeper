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

import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.DigestWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.audit.AuditConstants;
import org.apache.zookeeper.audit.AuditEvent.Result;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.common.PathTrie;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.watch.IWatchManager;
import org.apache.zookeeper.server.watch.WatchManagerFactory;
import org.apache.zookeeper.server.watch.WatcherMode;
import org.apache.zookeeper.server.watch.WatcherOrBitSet;
import org.apache.zookeeper.server.watch.WatchesPathReport;
import org.apache.zookeeper.server.watch.WatchesReport;
import org.apache.zookeeper.server.watch.WatchesSummary;
import org.apache.zookeeper.txn.CheckVersionTxn;
import org.apache.zookeeper.txn.CloseSessionTxn;
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maintains the tree data structure. It doesn't have any networking
 * or client connection code in it so that it can be tested in a stand alone
 * way.
 * <p>
 * The tree maintains two parallel data structures: a hashtable that maps from
 * full paths to DataNodes and a tree of DataNodes. All accesses to a path is
 * through the hashtable. The tree is traversed only when serializing to disk.
 */
public class DataTree {

    private static final Logger LOG = LoggerFactory.getLogger(DataTree.class);

    private final RateLogger RATE_LOGGER = new RateLogger(LOG, 15 * 60 * 1000);

    /**
     * This map provides a fast lookup to the datanodes. The tree is the
     * source of truth and is where all the locking occurs
     */
    private final NodeHashMap nodes;

    private IWatchManager dataWatches;

    private IWatchManager childWatches;

    /** cached total size of paths and data for all DataNodes */
    private final AtomicLong nodeDataSize = new AtomicLong(0);

    /** the root of zookeeper tree */
    private static final String rootZookeeper = "/";

    /** the zookeeper nodes that acts as the management and status node **/
    private static final String procZookeeper = Quotas.procZookeeper;

    /** this will be the string thats stored as a child of root */
    private static final String procChildZookeeper = procZookeeper.substring(1);

    /**
     * the zookeeper quota node that acts as the quota management node for
     * zookeeper
     */
    private static final String quotaZookeeper = Quotas.quotaZookeeper;

    /** this will be the string thats stored as a child of /zookeeper */
    private static final String quotaChildZookeeper = quotaZookeeper.substring(procZookeeper.length() + 1);

    /**
     * the zookeeper config node that acts as the config management node for
     * zookeeper
     */
    private static final String configZookeeper = ZooDefs.CONFIG_NODE;

    /** this will be the string thats stored as a child of /zookeeper */
    private static final String configChildZookeeper = configZookeeper.substring(procZookeeper.length() + 1);

    /**
     * the path trie that keeps track of the quota nodes in this datatree
     */
    private final PathTrie pTrie = new PathTrie();

    /**
     * over-the-wire size of znode's stat. Counting the fields of Stat class
     */
    public static final int STAT_OVERHEAD_BYTES = (6 * 8) + (5 * 4);

    /**
     * This hashtable lists the paths of the ephemeral(短暂) nodes of a session.
     */
    private final Map<Long, HashSet<String>> ephemerals = new ConcurrentHashMap<Long, HashSet<String>>();

    /**
     * This set contains the paths of all container nodes
     */
    private final Set<String> containers = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * This set contains the paths of all ttl nodes
     */
    private final Set<String> ttls = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final ReferenceCountedACLCache aclCache = new ReferenceCountedACLCache();

    // The maximum number of tree digests that we will keep in our history
    // 历史摘要记录的最大数量（循环队列）
    public static final int DIGEST_LOG_LIMIT = 1024;

    // Dump digest every 128 txns, in hex it's 80, which will make it easier
    // to align and compare between servers. 每 128 个事务记录一次摘要（选择 128 是因为 16 进制是 0x80，便于对齐和比较）
    public static final int DIGEST_LOG_INTERVAL = 128;

    // If this is not null, we are actively looking for a target zxid that we
    // want to validate the digest for 快照文件保存时的最后事务 ID
    private ZxidDigest digestFromLoadedSnapshot;

    // The digest associated with the highest zxid in the data tree.
    private volatile ZxidDigest lastProcessedZxidDigest;

    private boolean firstMismatchTxn = true;

    // Will be notified when digest mismatch event triggered.
    private final List<DigestWatcher> digestWatchers = new ArrayList<>();

    // The historical digests list.
    private LinkedList<ZxidDigest> digestLog = new LinkedList<>();

    private final DigestCalculator digestCalculator;

    @SuppressWarnings("unchecked")
    public Set<String> getEphemerals(long sessionId) {
        HashSet<String> retv = ephemerals.get(sessionId);
        if (retv == null) {
            return new HashSet<String>();
        }
        Set<String> cloned = null;
        synchronized (retv) {
            cloned = (HashSet<String>) retv.clone();
        }
        return cloned;
    }

    public Set<String> getContainers() {
        return new HashSet<String>(containers);
    }

    public Set<String> getTtls() {
        return new HashSet<String>(ttls);
    }

    public Collection<Long> getSessions() {
        return ephemerals.keySet();
    }

    public DataNode getNode(String path) {
        return nodes.get(path);
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getWatchCount() {
        return dataWatches.size() + childWatches.size();
    }

    public int getEphemeralsCount() {
        int result = 0;
        for (HashSet<String> set : ephemerals.values()) {
            result += set.size();
        }
        return result;
    }

    /**
     * Get the size of the nodes based on path and data length.
     *
     * @return size of the data
     */
    public long approximateDataSize() {
        long result = 0;
        for (Map.Entry<String, DataNode> entry : nodes.entrySet()) {
            DataNode value = entry.getValue();
            synchronized (value) {
                result += getNodeSize(entry.getKey(), value.data);
            }
        }
        return result;
    }

    /**
     * Get the size of the node based on path and data length.
     */
    private static long getNodeSize(String path, byte[] data) {
        return (path == null ? 0 : path.length()) + (data == null ? 0 : data.length);
    }

    public long cachedApproximateDataSize() {
        return nodeDataSize.get();
    }

    /**
     * This is a pointer to the root of the DataTree. It is the source of truth,
     * but we usually use the nodes hashmap to find nodes in the tree.
     */
    private DataNode root = new DataNode(new byte[0], -1L, new StatPersisted());

    /**
     * create a /zookeeper filesystem that is the proc filesystem of zookeeper
     */
    private final DataNode procDataNode = new DataNode(new byte[0], -1L, new StatPersisted());

    /**
     * create a /zookeeper/quota node for maintaining quota properties for
     * zookeeper
     */
    private final DataNode quotaDataNode = new DataNode(new byte[0], -1L, new StatPersisted());

    public DataTree() {
        this(new DigestCalculator());
    }

    DataTree(DigestCalculator digestCalculator) {
        this.digestCalculator = digestCalculator;//摘要计算器
        nodes = new NodeHashMapImpl(digestCalculator);

        /* Rather than fight it, let root have an alias / 和 '' 是等效的 */
        nodes.put("", root);
        nodes.putWithoutDigest(rootZookeeper, root);// /

        /** add the proc node and quota node */
        root.addChild(procChildZookeeper);// zookeeper
        nodes.put(procZookeeper, procDataNode);// /zookeeper

        procDataNode.addChild(quotaChildZookeeper);// quota
        nodes.put(quotaZookeeper, quotaDataNode);// /zookeeper/quota
        // dataNode层面的结构是： / -> zookeeper -> quota 连起来是颗树

        addConfigNode();

        nodeDataSize.set(approximateDataSize());
        try {
            dataWatches = WatchManagerFactory.createWatchManager();
            childWatches = WatchManagerFactory.createWatchManager();
        } catch (Exception e) {
            LOG.error("Unexpected exception when creating WatchManager, exiting abnormally", e);
            ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
    }

    /**
     * create a /zookeeper/config node for maintaining the configuration (membership and quorum system) info for
     * zookeeper
     */
    public void addConfigNode() {
        DataNode zookeeperZnode = nodes.get(procZookeeper);
        if (zookeeperZnode != null) { // should always be the case
            zookeeperZnode.addChild(configChildZookeeper); // config
        } else {
            assert false : "There's no /zookeeper znode - this should never happen.";
        }

        nodes.put(configZookeeper, new DataNode(new byte[0], -1L, new StatPersisted()));// /zookeeper/config
        try {
            // Reconfig node is access controlled by default (ZOOKEEPER-2014).
            setACL(configZookeeper, ZooDefs.Ids.READ_ACL_UNSAFE, -1);
        } catch (KeeperException.NoNodeException e) {
            assert false : "There's no " + configZookeeper + " znode - this should never happen.";
        }
    }

    /**
     * is the path one of the special paths owned by zookeeper.
     *
     * @param path
     *            the path to be checked
     * @return true if a special path. false if not.
     */
    boolean isSpecialPath(String path) {
        return rootZookeeper.equals(path)
               || procZookeeper.equals(path)
               || quotaZookeeper.equals(path)
               || configZookeeper.equals(path);
    }

    public static void copyStatPersisted(StatPersisted from, StatPersisted to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setPzxid(from.getPzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
    }

    public static void copyStat(Stat from, Stat to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setPzxid(from.getPzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
        to.setDataLength(from.getDataLength());
        to.setNumChildren(from.getNumChildren());
    }

    /**
     * update the count/bytes of this stat data node
     *
     * @param lastPrefix
     *            the path of the node that has a quota.
     * @param bytesDiff
     *            the diff to be added to number of bytes
     * @param countDiff
     *            the diff to be added to the count
     */
    public void updateQuotaStat(String lastPrefix, long bytesDiff, int countDiff) {

        String statNodePath = Quotas.statPath(lastPrefix);
        DataNode statNode = nodes.get(statNodePath);

        StatsTrack updatedStat;
        if (statNode == null) {
            // should not happen
            LOG.error("Missing node for stat {}", statNodePath);
            return;
        }
        synchronized (statNode) {
            updatedStat = new StatsTrack(statNode.data);
            updatedStat.setCount(updatedStat.getCount() + countDiff);
            updatedStat.setBytes(updatedStat.getBytes() + bytesDiff);

            statNode.data = updatedStat.getStatsBytes();
        }
    }

    /**
     * Add a new node to the DataTree.
     * @param path
     *            Path for the new node.
     * @param data
     *            Data to store in the node.
     * @param acl
     *            Node acls
     * @param ephemeralOwner
     *            the session id that owns this node. -1 indicates this is not
     *            an ephemeral node.
     * @param zxid
     *            Transaction ID
     * @param time
     * @throws NodeExistsException
     * @throws NoNodeException
     */
    public void createNode(final String path, byte[] data, List<ACL> acl, long ephemeralOwner, int parentCVersion, long zxid, long time) throws NoNodeException, NodeExistsException {
        createNode(path, data, acl, ephemeralOwner, parentCVersion, zxid, time, null);
    }

    /**
     * Add a new node to the DataTree.
     * @param path
     *            Path for the new node.
     * @param data
     *            Data to store in the node.
     * @param acl
     *            Node acls
     * @param ephemeralOwner
     *            the session id that owns this node. -1 indicates this is not
     *            an ephemeral node.
     * @param zxid
     *            Transaction ID
     * @param time
     * @param outputStat
     *            A Stat object to store Stat output results into.
     * @throws NodeExistsException
     * @throws NoNodeException
     *
     * 主要流程
     *  1. 解析路径
     *   提取父节点路径 (parentName) 和子节点名称 (childName)
     *   创建节点的统计信息对象
     *  2. 检查父节点
     *   获取父节点，如果不存在则抛出 NoNodeException点
     *  3. 加锁执行创建（同步块）
     *   synchronized (parent) {
     *     a. 获取父节点的 ACL
     *     b. 将 ACL 添加到缓存（避免模糊快照同步时的竞态条件）
     *     c. 检查子节点是否已存在，存在则抛出 NodeExistsException
     *     d. 通知节点即将变化 (nodes.preChange)
     *     e. 更新父节点的 cversion 和 pzxid
     *        - 只有当新的 cversion 更大时才更新（处理事务重放场景）
     *     f. 创建新 DataNode 并添加到父节点的 children
     *     g. 通知节点已变化 (nodes.postChange)
     *     h. 更新节点数据大小统计
     *     i. 根据 ephemeralOwner 处理临时节点类型：
     *        - CONTAINER → 加入 containers 集合
     *        - TTL → 加入 ttls 集合
     *        - 其他临时节点 (ephemeralOwner != 0) → 加入 ephemerals 映射
     *     j. 如果需要，复制统计信息到 outputStat
     *    }
     *  4. 配额管理
     *     如果是 quotaZookeeper 路径下的节点：
     *         limitNode → 添加路径到配额树
     *         statNode → 更新配额统计
     *     更新相关配额节点的数据量和节点数统计
     *  5. 触发监听器
     *    触发新节点本身的 NodeCreated 监听
     *    触发父节点的 NodeChildrenChanged 监听
     * 关键设计点
     *  线程安全：使用 synchronized(parent) 保证对父节点操作的原子性
     *  ACL 缓存优先：先添加 ACL 到缓存，避免模糊快照同步时的竞态
     *  版本保护：通过 cversion 检查防止事务重放时覆盖更新的值
     *  临时节点分类：根据 ephemeralOwner 区分容器、TTL、普通临时节点
     *  配额追踪：自动更新路径相关的配额统计信息
     */
    public void createNode(final String path, byte[] data, List<ACL> acl, long ephemeralOwner, int parentCVersion, long zxid, long time, Stat outputStat) throws KeeperException.NoNodeException, KeeperException.NodeExistsException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        StatPersisted stat = createStat(zxid, time, ephemeralOwner);
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        List<ACL> parentAcl;
        synchronized (parent) {
            parentAcl = getACL(parent);

            // Add the ACL to ACL cache first, to avoid the ACL not being
            // created race condition during fuzzy snapshot sync.
            //
            // This is the simplest fix, which may add ACL reference count
            // again if it's already counted in in the ACL map of fuzzy
            // snapshot, which might also happen for deleteNode txn, but
            // at least it won't cause the ACL not exist issue.
            //
            // Later we can audit and delete all non-referenced ACLs from
            // ACL map when loading the snapshot/txns from disk, like what
            // we did for the global sessions.
            Long longval = aclCache.convertAcls(acl);

            Set<String> children = parent.getChildren();
            if (children.contains(childName)) {
                throw new KeeperException.NodeExistsException();
            }

            nodes.preChange(parentName, parent);
            /**
             * 在 ZooKeeper 中，parentCVersion 参数表示创建子节点时父节点应该具有的版本号。
             *  这个值在不同场景下有不同的含义：
             *    1. 正常客户端请求（parentCVersion != -1）
             *     当客户端发起创建节点请求时，会传入它期望的父节点 cversion
             *     ZooKeeper 会检查这个版本是否匹配，确保并发安全
             *     此时直接使用传入的值进行验证
             *    2. 事务重放/恢复场景（parentCVersion == -1）
             *     当从快照或事务日志恢复数据时，需要重放创建节点的事务
             *     此时传入的 parentCVersion 为 -1，表示"使用当前父节点的实际版本"
             *     系统会自动获取父节点当前的 cversion 并递增
             * 为什么要这样设计？
             *   灵活性：同一个方法既处理客户端请求，也处理内部事务重放
             *   幂等性：在模糊快照同步时，可能需要重放已存在节点的事务，自动获取当前版本可以正确更新
             *   向后兼容：旧版本的事务可能没有包含 parentCVersion，用 -1 作为默认值
             */
            if (parentCVersion == -1) {
                parentCVersion = parent.stat.getCversion();
                parentCVersion++;
            }
            // There is possibility that we'll replay txns for a node which
            // was created and then deleted in the fuzzy range, and it's not
            // exist in the snapshot, so replay the creation might revert the
            // cversion and pzxid, need to check and only update when it's
            // larger.
            if (parentCVersion > parent.stat.getCversion()) {// 确保只在版本更大时才更新，防止回退。
                parent.stat.setCversion(parentCVersion);
                parent.stat.setPzxid(zxid);
            }
            DataNode child = new DataNode(data, longval, stat);
            parent.addChild(childName);
            nodes.postChange(parentName, parent);
            nodeDataSize.addAndGet(getNodeSize(path, child.data));
            nodes.put(path, child);

            EphemeralType ephemeralType = EphemeralType.get(ephemeralOwner);
            if (ephemeralType == EphemeralType.CONTAINER) {
                containers.add(path);
            } else if (ephemeralType == EphemeralType.TTL) {
                ttls.add(path);
            } else if (ephemeralOwner != 0) {
                HashSet<String> list = ephemerals.get(ephemeralOwner);
                if (list == null) {
                    list = new HashSet<String>();
                    ephemerals.put(ephemeralOwner, list);
                }
                synchronized (list) {
                    list.add(path);
                }
            }
            //这个是对外暴露的Stat
            if (outputStat != null) {
                child.copyStat(outputStat);
            }
        }
        // now check if its one of the zookeeper node child
        if (parentName.startsWith(quotaZookeeper)) {
            // now check if its the limit node
            if (Quotas.limitNode.equals(childName)) {
                // this is the limit node
                // get the parent and add it to the trie
                pTrie.addPath(Quotas.trimQuotaPath(parentName));
            }
            if (Quotas.statNode.equals(childName)) {
                updateQuotaForPath(Quotas.trimQuotaPath(parentName));
            }
        }

        String lastPrefix = getMaxPrefixWithQuota(path);
        long bytes = data == null ? 0 : data.length;
        // also check to update the quotas for this node
        if (lastPrefix != null) {    // ok we have some match and need to update
            updateQuotaStat(lastPrefix, bytes, 1);
        }
        updateWriteStat(path, bytes);
        dataWatches.triggerWatch(path, Event.EventType.NodeCreated, acl);
        childWatches.triggerWatch(parentName.equals("") ? "/" : parentName,
            Event.EventType.NodeChildrenChanged, parentAcl);
    }

    /**
     * remove the path from the datatree
     *
     * @param path
     *            the path to of the node to be deleted
     * @param zxid
     *            the current zxid
     * @throws KeeperException.NoNodeException
     */
    public void deleteNode(String path, long zxid) throws KeeperException.NoNodeException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);

        // The child might already be deleted during taking fuzzy snapshot,
        // but we still need to update the pzxid here before throw exception
        // for no such child
        DataNode parent = nodes.get(parentName);//直接定位到对应的dataNode
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            nodes.preChange(parentName, parent);
            parent.removeChild(childName);//set集合移除子节点
            // Only update pzxid when the zxid is larger than the current pzxid,
            // otherwise we might override some higher pzxid set by a create
            // Txn, which could cause the cversion and pzxid inconsistent
            if (zxid > parent.stat.getPzxid()) {
                parent.stat.setPzxid(zxid);
            }
            nodes.postChange(parentName, parent);
        }

        DataNode node = nodes.get(path);
        if (node == null) {
            throw new KeeperException.NoNodeException();
        }
        List<ACL> acl;
        nodes.remove(path);
        synchronized (node) {
            acl = getACL(node);
            aclCache.removeUsage(node.acl);
            nodeDataSize.addAndGet(-getNodeSize(path, node.data));
        }

        // Synchronized to sync the containers and ttls change, probably
        // only need to sync on containers and ttls, will update it in a
        // separate patch.
        List<ACL> parentAcl;
        synchronized (parent) {
            parentAcl = getACL(parent);

            long eowner = node.stat.getEphemeralOwner();
            EphemeralType ephemeralType = EphemeralType.get(eowner);
            if (ephemeralType == EphemeralType.CONTAINER) {
                containers.remove(path);
            } else if (ephemeralType == EphemeralType.TTL) {
                ttls.remove(path);
            } else if (eowner != 0) {
                Set<String> nodes = ephemerals.get(eowner);
                if (nodes != null) {
                    synchronized (nodes) {
                        nodes.remove(path);
                    }
                }
            }
        }

        if (parentName.startsWith(procZookeeper) && Quotas.limitNode.equals(childName)) {
            // delete the node in the trie.
            // we need to update the trie as well
            pTrie.deletePath(Quotas.trimQuotaPath(parentName));
        }

        // also check to update the quotas for this node
        String lastPrefix = getMaxPrefixWithQuota(path);
        if (lastPrefix != null) {
            // ok we have some match and need to update
            long bytes = 0;
            synchronized (node) {
                bytes = (node.data == null ? 0 : -(node.data.length));
            }
            updateQuotaStat(lastPrefix, bytes, -1);
        }

        updateWriteStat(path, 0L);

        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                "dataWatches.triggerWatch " + path);
            ZooTrace.logTraceMessage(
                LOG,
                ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                "childWatches.triggerWatch " + parentName);
        }

        WatcherOrBitSet processed = dataWatches.triggerWatch(path, EventType.NodeDeleted, acl);
        childWatches.triggerWatch(path, EventType.NodeDeleted, acl, processed);
        childWatches.triggerWatch("".equals(parentName) ? "/" : parentName,
            EventType.NodeChildrenChanged, parentAcl);
    }

    public Stat setData(String path, byte[] data, int version, long zxid, long time) throws KeeperException.NoNodeException {
        Stat s = new Stat();
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        List<ACL> acl;
        byte[] lastdata = null;
        synchronized (n) {
            acl = getACL(n);
            lastdata = n.data;
            nodes.preChange(path, n);
            n.data = data;
            n.stat.setMtime(time);
            n.stat.setMzxid(zxid);
            n.stat.setVersion(version);
            n.copyStat(s);
            nodes.postChange(path, n);
        }

        // first do a quota check if the path is in a quota subtree.
        String lastPrefix = getMaxPrefixWithQuota(path);
        long bytesDiff = (data == null ? 0 : data.length) - (lastdata == null ? 0 : lastdata.length);
        // now update if the path is in a quota subtree.
        long dataBytes = data == null ? 0 : data.length;
        if (lastPrefix != null) {
            updateQuotaStat(lastPrefix, bytesDiff, 0);
        }
        nodeDataSize.addAndGet(getNodeSize(path, data) - getNodeSize(path, lastdata));

        updateWriteStat(path, dataBytes);
        dataWatches.triggerWatch(path, EventType.NodeDataChanged, acl);
        return s;
    }

    /**
     * If there is a quota set, return the appropriate prefix for that quota
     * Else return null
     * @param path The ZK path to check for quota
     * @return Max quota prefix, or null if none
     */
    public String getMaxPrefixWithQuota(String path) {
        // do nothing for the root.
        // we are not keeping a quota on the zookeeper
        // root node for now.
        String lastPrefix = pTrie.findMaxPrefix(path);

        if (rootZookeeper.equals(lastPrefix) || lastPrefix.isEmpty()) {
            return null;
        } else {
            return lastPrefix;
        }
    }

    public void addWatch(String basePath, Watcher watcher, int mode) {
        WatcherMode watcherMode = WatcherMode.fromZooDef(mode);
        dataWatches.addWatch(basePath, watcher, watcherMode);
        childWatches.addWatch(basePath, watcher, watcherMode);
    }

    public byte[] getData(String path, Stat stat, Watcher watcher) throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        byte[] data = null;
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            if (watcher != null) {
                dataWatches.addWatch(path, watcher);
            }
            data = n.data;
        }
        updateReadStat(path, data == null ? 0 : data.length);
        return data;
    }

    public Stat statNode(String path, Watcher watcher) throws KeeperException.NoNodeException {
        Stat stat = new Stat();
        DataNode n = nodes.get(path);
        if (watcher != null) {
            dataWatches.addWatch(path, watcher);
        }
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
        }
        updateReadStat(path, 0L);
        return stat;
    }

    public List<String> getChildren(String path, Stat stat, Watcher watcher) throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        List<String> children;
        synchronized (n) {
            if (stat != null) {
                n.copyStat(stat);
            }
            children = new ArrayList<String>(n.getChildren());

            if (watcher != null) {
                childWatches.addWatch(path, watcher);
            }
        }

        int bytes = 0;
        for (String child : children) {
            bytes += child.length();
        }
        updateReadStat(path, bytes);

        return children;
    }

    public int getAllChildrenNumber(String path) {
        //cull out these two keys:"", "/"
        if ("/".equals(path)) {
            return nodes.size() - 2;
        }

        return (int) nodes.entrySet().parallelStream().filter(entry -> entry.getKey().startsWith(path + "/")).count();
    }

    public Stat setACL(String path, List<ACL> acl, int version) throws KeeperException.NoNodeException {
        Stat stat = new Stat();
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            aclCache.removeUsage(n.acl);
            nodes.preChange(path, n);
            n.stat.setAversion(version);
            n.acl = aclCache.convertAcls(acl);
            n.copyStat(stat);
            nodes.postChange(path, n);
            return stat;
        }
    }

    public List<ACL> getACL(String path, Stat stat) throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            if (stat != null) {
                n.copyStat(stat);
            }
            return new ArrayList<ACL>(aclCache.convertLong(n.acl));
        }
    }

    public List<ACL> getACL(DataNode node) {
        synchronized (node) {
            return aclCache.convertLong(node.acl);
        }
    }

    public int aclCacheSize() {
        return aclCache.size();
    }

    public static class ProcessTxnResult {

        public long clientId;

        public int cxid;

        public long zxid;

        public int err;

        public int type;

        public String path;

        public Stat stat;

        public List<ProcessTxnResult> multiResult;

        /**
         * Equality is defined as the clientId and the cxid being the same. This
         * allows us to use hash tables to track completion of transactions.
         *
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object o) {
            if (o instanceof ProcessTxnResult) {
                ProcessTxnResult other = (ProcessTxnResult) o;
                return other.clientId == clientId && other.cxid == cxid;
            }
            return false;
        }

        /**
         * See equals() to find the rational for how this hashcode is generated.
         *
         * @see ProcessTxnResult#equals(Object)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return (int) ((clientId ^ cxid) % Integer.MAX_VALUE);
        }

    }

    //DataTree 已处理的最后一个 zxid
    public volatile long lastProcessedZxid = 0;

    public ProcessTxnResult processTxn(TxnHeader header, Record txn, TxnDigest digest) {
        ProcessTxnResult result = processTxn(header, txn);
        compareDigest(header, txn, digest);
        return result;
    }

    public ProcessTxnResult processTxn(TxnHeader header, Record txn) {
        return this.processTxn(header, txn, false);
    }

    public ProcessTxnResult processTxn(TxnHeader header, Record txn, boolean isSubTxn) {
        ProcessTxnResult rc = new ProcessTxnResult();

        try {
            rc.clientId = header.getClientId();
            rc.cxid = header.getCxid();
            rc.zxid = header.getZxid();
            rc.type = header.getType();
            rc.err = 0;
            rc.multiResult = null;
            switch (header.getType()) {
            case OpCode.create:
                CreateTxn createTxn = (CreateTxn) txn;
                rc.path = createTxn.getPath();
                createNode(
                    createTxn.getPath(),
                    createTxn.getData(),
                    createTxn.getAcl(),
                    createTxn.getEphemeral() ? header.getClientId() : 0,
                    createTxn.getParentCVersion(),
                    header.getZxid(),
                    header.getTime(),
                    null);
                break;
            case OpCode.create2:
                CreateTxn create2Txn = (CreateTxn) txn;
                rc.path = create2Txn.getPath();
                Stat stat = new Stat();
                createNode(
                    create2Txn.getPath(),
                    create2Txn.getData(),
                    create2Txn.getAcl(),
                    create2Txn.getEphemeral() ? header.getClientId() : 0,
                    create2Txn.getParentCVersion(),
                    header.getZxid(),
                    header.getTime(),
                    stat);
                rc.stat = stat;
                break;
            case OpCode.createTTL:
                CreateTTLTxn createTtlTxn = (CreateTTLTxn) txn;
                rc.path = createTtlTxn.getPath();
                stat = new Stat();
                createNode(
                    createTtlTxn.getPath(),
                    createTtlTxn.getData(),
                    createTtlTxn.getAcl(),
                    EphemeralType.TTL.toEphemeralOwner(createTtlTxn.getTtl()),
                    createTtlTxn.getParentCVersion(),
                    header.getZxid(),
                    header.getTime(),
                    stat);
                rc.stat = stat;
                break;
            case OpCode.createContainer:
                CreateContainerTxn createContainerTxn = (CreateContainerTxn) txn;
                rc.path = createContainerTxn.getPath();
                stat = new Stat();
                createNode(
                    createContainerTxn.getPath(),
                    createContainerTxn.getData(),
                    createContainerTxn.getAcl(),
                    EphemeralType.CONTAINER_EPHEMERAL_OWNER,
                    createContainerTxn.getParentCVersion(),
                    header.getZxid(),
                    header.getTime(),
                    stat);
                rc.stat = stat;
                break;
            case OpCode.delete:
            case OpCode.deleteContainer:
                DeleteTxn deleteTxn = (DeleteTxn) txn;
                rc.path = deleteTxn.getPath();
                deleteNode(deleteTxn.getPath(), header.getZxid());
                break;
            case OpCode.reconfig:
            case OpCode.setData:
                SetDataTxn setDataTxn = (SetDataTxn) txn;
                rc.path = setDataTxn.getPath();
                rc.stat = setData(
                    setDataTxn.getPath(),
                    setDataTxn.getData(),
                    setDataTxn.getVersion(),
                    header.getZxid(),
                    header.getTime());
                break;
            case OpCode.setACL:
                SetACLTxn setACLTxn = (SetACLTxn) txn;
                rc.path = setACLTxn.getPath();
                rc.stat = setACL(setACLTxn.getPath(), setACLTxn.getAcl(), setACLTxn.getVersion());
                break;
            case OpCode.closeSession:
                long sessionId = header.getClientId();
                if (txn != null) {
                    killSession(sessionId, header.getZxid(),
                            ephemerals.remove(sessionId),
                            ((CloseSessionTxn) txn).getPaths2Delete());
                } else {
                    killSession(sessionId, header.getZxid());
                }
                break;
            case OpCode.error:
                ErrorTxn errTxn = (ErrorTxn) txn;
                rc.err = errTxn.getErr();
                break;
            case OpCode.check:
                CheckVersionTxn checkTxn = (CheckVersionTxn) txn;
                rc.path = checkTxn.getPath();
                break;
            case OpCode.multi:
                MultiTxn multiTxn = (MultiTxn) txn;
                List<Txn> txns = multiTxn.getTxns();
                rc.multiResult = new ArrayList<ProcessTxnResult>();
                boolean failed = false;
                for (Txn subtxn : txns) {
                    if (subtxn.getType() == OpCode.error) {
                        failed = true;
                        break;
                    }
                }

                boolean post_failed = false;
                for (Txn subtxn : txns) {
                    ByteBuffer bb = ByteBuffer.wrap(subtxn.getData());
                    Record record = null;
                    switch (subtxn.getType()) {
                    case OpCode.create:
                    case OpCode.create2:
                        record = new CreateTxn();
                        break;
                    case OpCode.createTTL:
                        record = new CreateTTLTxn();
                        break;
                    case OpCode.createContainer:
                        record = new CreateContainerTxn();
                        break;
                    case OpCode.delete:
                    case OpCode.deleteContainer:
                        record = new DeleteTxn();
                        break;
                    case OpCode.setData:
                        record = new SetDataTxn();
                        break;
                    case OpCode.error:
                        record = new ErrorTxn();
                        post_failed = true;
                        break;
                    case OpCode.check:
                        record = new CheckVersionTxn();
                        break;
                    default:
                        throw new IOException("Invalid type of op: " + subtxn.getType());
                    }
                    assert (record != null);

                    ByteBufferInputStream.byteBuffer2Record(bb, record);

                    if (failed && subtxn.getType() != OpCode.error) {
                        int ec = post_failed ? Code.RUNTIMEINCONSISTENCY.intValue() : Code.OK.intValue();

                        subtxn.setType(OpCode.error);
                        record = new ErrorTxn(ec);
                    }

                    assert !failed || (subtxn.getType() == OpCode.error);

                    TxnHeader subHdr = new TxnHeader(
                        header.getClientId(),
                        header.getCxid(),
                        header.getZxid(),
                        header.getTime(),
                        subtxn.getType());
                    ProcessTxnResult subRc = processTxn(subHdr, record, true);
                    rc.multiResult.add(subRc);
                    if (subRc.err != 0 && rc.err == 0) {
                        rc.err = subRc.err;
                    }
                }
                break;
            }
        } catch (KeeperException e) {
            LOG.debug("Failed: {}:{}", header, txn, e);
            rc.err = e.code().intValue();
        } catch (IOException e) {
            LOG.debug("Failed: {}:{}", header, txn, e);
        }

        /*
         * Snapshots are taken lazily. When serializing a node, it's data
         * and children copied in a synchronization block on that node,
         * which means newly created node won't be in the snapshot, so
         * we won't have mismatched cversion and pzxid when replaying the
         * createNode txn.
         *
         * But there is a tricky scenario that if the child is deleted due
         * to session close and re-created in a different global session
         * after that the parent is serialized, then when replay the txn
         * because the node is belonging to a different session, replay the
         * closeSession txn won't delete it anymore, and we'll get NODEEXISTS
         * error when replay the createNode txn. In this case, we need to
         * update the cversion and pzxid to the new value.
         *
         * Note, such failures on DT should be seen only during
         * restore.
         *
         * 这段代码处理的是 ZooKeeper 事务恢复时的一个特殊场景，主要解决在重放事务日志时遇到的 NODEEXISTS 错误。
         * 这个逻辑会在以下复杂场景中被触发：
         *   子节点因会话关闭被删除
         *   之后又在不同的全局会话中被重新创建
         *   此时父节点被序列化到快照中
         *   恢复时重放创建节点的事务，因为节点属于不同的会话，不会再被删除
         *   结果：重放创建事务时报 NODEEXISTS 错误
         * 解决方案
         *  当遇到这种情况时，代码会：
         *   提取父节点路径：从完整路径中截取父节点部分
         *       例如：/a/b/c → 父节点是 /a/b
         *   更新父节点的 cversion 和 pzxid：
         *       cversion：子节点版本号
         *       pzxid：最后修改子节点的 zxid
         *       使用事务中记录的 parentCVersion 和当前的 zxid
         * 为什么这样做？
         *  这是一个数据一致性修复机制：
         *   问题：快照和事务日志之间的状态不一致
         *   解决：强制更新父节点的元数据，使其与当前实际状态匹配
         *   适用范围：只在**恢复（restore）**期间出现，正常运行时不会遇到
         * 示例说明
         *  时间线：
         *   1. 会话 A 创建 /node1（cversion=1）
         *   2. 会话 A 关闭，/node1 被删除
         *   3. 会话 B 创建 /node1（cversion=2）
         *   4. 此时对父节点做快照（cversion 还是 1）
         *   5. 服务器宕机
         *
         *  恢复时：
         *   - 加载快照：父节点 cversion=1
         *   - 重放事务：创建 /node1（期望 cversion=2）
         *   - 发现不匹配 → NODEEXISTS 错误
         *   - 执行这段代码：将父节点 cversion 更新为 2
         * 这样就解决了快照和事务日志之间的元数据不一致问题。
         */
        if (header.getType() == OpCode.create && rc.err == Code.NODEEXISTS.intValue()) {
            LOG.debug("Adjusting parent cversion for Txn: {} path: {} err: {}", header.getType(), rc.path, rc.err);
            // 调整父节点的 cversion
            int lastSlash = rc.path.lastIndexOf('/');
            String parentName = rc.path.substring(0, lastSlash);
            CreateTxn cTxn = (CreateTxn) txn;
            try {
                setCversionPzxid(parentName, cTxn.getParentCVersion(), header.getZxid());
            } catch (KeeperException.NoNodeException e) {
                LOG.error("Failed to set parent cversion for: {}", parentName, e);
                rc.err = e.code().intValue();
            }
        } else if (rc.err != Code.OK.intValue()) {
            LOG.debug("Ignoring processTxn failure hdr: {} : error: {}", header.getType(), rc.err);
        }

        /*
         * Things we can only update after the whole txn is applied to data
         * tree.
         *
         * If we update the lastProcessedZxid with the first sub txn in multi
         * and there is a snapshot in progress, it's possible that the zxid
         * associated with the snapshot only include partial of the multi op.
         *
         * When loading snapshot, it will only load the txns after the zxid
         * associated with snapshot file, which could cause data inconsistency
         * due to missing sub txns.
         *
         * To avoid this, we only update the lastProcessedZxid when the whole
         * multi-op txn is applied to DataTree.
         */
        if (!isSubTxn) {
            /*
             * A snapshot might be in progress while we are modifying the data
             * tree. If we set lastProcessedZxid prior to making corresponding
             * change to the tree, then the zxid associated with the snapshot
             * file will be ahead of its contents. Thus, while restoring from
             * the snapshot, the restore method will not apply the transaction
             * for zxid associated with the snapshot file, since the restore
             * method assumes that transaction to be present in the snapshot.
             *
             * To avoid this, we first apply the transaction and then modify
             * lastProcessedZxid.  During restore, we correctly handle the
             * case where the snapshot contains data ahead of the zxid associated
             * with the file.
             */
            if (rc.zxid > lastProcessedZxid) {
                lastProcessedZxid = rc.zxid;
            }

            if (digestFromLoadedSnapshot != null) {
                // 正在从快照恢复，先校验摘要
                compareSnapshotDigests(rc.zxid);
            } else {
                // only start recording digest when we're not in fuzzy state
                // 正常运行状态，记录摘要到历史队列
                logZxidDigest(rc.zxid, getTreeDigest());
            }
        }

        return rc;
    }

    void killSession(long session, long zxid) {
        // the list is already removed from the ephemerals
        // so we do not have to worry about synchronizing on
        // the list. This is only called from FinalRequestProcessor
        // so there is no need for synchronization. The list is not
        // changed here. Only create and delete change the list which
        // are again called from FinalRequestProcessor in sequence.
        killSession(session, zxid, ephemerals.remove(session), null);
    }

    /**
     * 当会话（session）失效时，清理该会话创建的所有临时节点（ephemeral nodes）
     * session: 失效的会话 ID
     * zxid: 事务 ID，用于记录删除操作
     * paths2DeleteLocal: 本地内存中记录的该会话的临时节点路径集合
     * paths2DeleteInTxn: 事务日志/快照中记录的待删除路径列表
     *
     * 为什么需要两个参数？
     *   场景 1：正常会话关闭
     *   // CloseSessionTxn 处理时
     *   killSession(sessionId, header.getZxid(),
     *     ephemerals.remove(sessionId),      // paths2DeleteLocal: 从内存获取
     *     ((CloseSessionTxn) txn).getPaths2Delete());  // paths2DeleteInTxn:从事务获取
     *  场景 2：简化调用
     *   // 其他场景（如测试、清理死会话）
     *   killSession(session, zxid, ephemerals.remove(session), null);
     *   // paths2DeleteInTxn = null
     *
     * 这是一个**双重保障**机制，用于处理不同场景下的数据一致性：
     * | 参数 | 来源 | 作用 |
     * |------|------|------|
     * | `paths2DeleteLocal` | 内存中的 `ephemerals` 映射 | 当前会话创建的临时节点（实时记录） |
     * | `paths2DeleteInTxn` | `CloseSessionTxn` 事务对象 | 事务日志中记录的待删除节点（持久化记录） |
     *
     * 在分布式系统中，可能出现：
     *  内存丢失：服务器重启后，ephemerals 可能不完整
     *  事务滞后：某些临时节点的创建可能在模糊快照范围内，还未持久化到事务日志
     *  所以需要同时检查两个数据源，确保不遗漏任何临时节点。
     *
     * ## 实际示例
     *
     * 假设会话 `0x123` 创建了 3 个临时节点：
     * - `/node1` - 已提交到事务日志
     * - `/node2` - 已提交到事务日志
     * - `/node3` - 刚创建，还在内存中，未提交
     *
     * **关闭会话时**：
     * ```java
     * paths2DeleteInTxn = ["/node1", "/node2"]  // 从事务获取
     * paths2DeleteLocal   = ["/node1", "/node2", "/node3"]  // 从内存获取
     *
     * 执行流程：
     * 1. 先删除 ["/node1", "/node2"]（基于事务）
     * 2. 从 local 中移除已处理的：local 变为 ["/node3"]
     * 3. 打印警告（发现有额外节点）
     * 4. 删除 ["/node3"]（基于内存）
     * ```
     * 这样就确保了**所有临时节点都被清理**，无论是已持久化的还是仅在内存中的。
     */
    void killSession(long session, long zxid, Set<String> paths2DeleteLocal,
            List<String> paths2DeleteInTxn) {
        //1. 优先处理事务中的节点
        // 如果事务中记录了要删除的节点，先执行这些删除。这确保了事务的一致性。
        if (paths2DeleteInTxn != null) {
            deleteNodes(session, zxid, paths2DeleteInTxn);
        }
        // 这确保了基于事务日志的恢复场景中，节点被正确删除

        // 2. 检查是否还有需要处理的节点 如果本地没有记录任何临时节点，直接返回。
        if (paths2DeleteLocal == null) {
            return;
        }
        // 说明这个会话没有创建任何临时节点

        // 3. 去重处理
        // 关键逻辑：
        //   从 paths2DeleteLocal 中移除已经在 paths2DeleteInTxn 中处理过的路径
        //   避免重复删除同一个节点（性能优化）
        //   如果还有剩余路径，记录警告日志（理论上不应该发生）
        if (paths2DeleteInTxn != null) {
            // explicitly check and remove to avoid potential performance
            // issue when using removeAll
            for (String path: paths2DeleteInTxn) {
                paths2DeleteLocal.remove(path);// 移除已处理的节点
            }
            if (!paths2DeleteLocal.isEmpty()) {
                // 警告：本地有额外节点不在事务中
                // 这可能发生在模糊快照同步期间
                LOG.warn(
                    "Unexpected extra paths under session {} which are not in txn 0x{}",
                    paths2DeleteLocal,
                    Long.toHexString(zxid));
            }
        }

        // 4. 删除剩余节点 删除本地记录但不在事务中的节点。
        deleteNodes(session, zxid, paths2DeleteLocal);
    }

    void deleteNodes(long session, long zxid, Iterable<String> paths2Delete) {
        for (String path : paths2Delete) {
            boolean deleted = false;
            String sessionHex = "0x" + Long.toHexString(session);
            try {
                deleteNode(path, zxid);
                deleted = true;
                LOG.debug("Deleting ephemeral node {} for session {}", path, sessionHex);
            } catch (NoNodeException e) {
                LOG.warn(
                    "Ignoring NoNodeException for path {} while removing ephemeral for dead session {}",
                        path, sessionHex);
            }
            if (ZKAuditProvider.isAuditEnabled()) {
                if (deleted) {
                    ZKAuditProvider.log(ZKAuditProvider.getZKUser(),
                            AuditConstants.OP_DEL_EZNODE_EXP, path, null, null,
                            sessionHex, null, Result.SUCCESS);
                } else {
                    ZKAuditProvider.log(ZKAuditProvider.getZKUser(),
                            AuditConstants.OP_DEL_EZNODE_EXP, path, null, null,
                            sessionHex, null, Result.FAILURE);
                }
            }
        }
    }

    /**
     * a encapsultaing class for return value
     */
    private static class Counts {

        long bytes;
        int count;

    }

    /**
     * this method gets the count of nodes and the bytes under a subtree
     *
     * @param path
     *            the path to be used
     * @param counts
     *            the int count
     * getCounts 方法用于 ZooKeeper 的配额（Quota）管理功能，具体场景包括
     * 1. 核心功能
     *  递归统计指定路径下所有子节点的：
     *      节点数量（counts.count）
     *      数据字节数（counts.bytes）
     * 2. 使用场景
     *  该方法被 updateQuotaForPath 方法调用（第 1242 行），用于：
     *      配额统计更新：当需要更新某个路径的配额使用情况时，通过此方法计算该路径及其所有子孙节点的总数和总字节数
     *      配额检查：ZooKeeper 可以为特定路径设置节点数量和存储空间的上限配额，这个方法负责统计实际使用量
     *      统计信息维护：统计结果会被保存到配额统计节点（/zookeeper/quota/<path>/stats）中
     * 3.调用链路
     * setupQuota() (初始化配额)
     *     └─> traverseNode() (遍历配额节点)
     *             └─> updateQuotaForPath() (更新配额统计)
     *                     └─> getCounts() (递归统计节点数和字节数)
     * 4. 工作流程
     *  获取指定路径的节点
     *  同步获取该节点的所有子节点
     *  统计当前节点的数据长度
     *  累加到计数器（节点数 +1，字节数增加）
     *  递归遍历所有子节点，重复上述过程
     * 这是一个典型的树形结构递归遍历算法，用于资源配额的统计和管理。
     */
    private void getCounts(String path, Counts counts) {
        DataNode node = getNode(path);
        if (node == null) {
            return;
        }
        String[] children = null;
        int len = 0;
        synchronized (node) {
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
            len = (node.data == null ? 0 : node.data.length);
        }
        // add itself
        counts.count += 1;
        counts.bytes += len;
        for (String child : children) {
            getCounts(path + "/" + child, counts);
        }
    }

    /**
     * update the quota for the given path
     *
     * @param path
     *            the path to be used
     */
    private void updateQuotaForPath(String path) {
        Counts c = new Counts();
        //获取该路径下所有的节点数和数据字节数
        getCounts(path, c);
        StatsTrack strack = new StatsTrack();
        strack.setBytes(c.bytes);
        strack.setCount(c.count);
        // 配额统计节点：/zookeeper/quota/<path>/stats
        String statPath = Quotas.statPath(path);
        DataNode node = getNode(statPath);
        // it should exist
        if (node == null) {
            LOG.warn("Missing quota stat node {}", statPath);
            return;
        }
        synchronized (node) {
            nodes.preChange(statPath, node);
            node.data = strack.getStatsBytes();
            nodes.postChange(statPath, node);
        }
    }

    /**
     * this method traverses the quota path and update the path trie and sets
     *
     * @param path
     */
    private void traverseNode(String path) {
        DataNode node = getNode(path);
        String[] children = null;
        synchronized (node) {
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
        }
        //一直到叶子结点在更新
        if (children.length == 0) {
            // this node does not have a child
            // is the leaf node
            // check if its the leaf node
            String endString = "/" + Quotas.limitNode;// zookeeper_limits
            if (path.endsWith(endString)) {// 以/zookeeper_limits结尾的节点
                // ok this is the limit node
                // get the real node and update
                // the count and the bytes
                String realPath = path.substring(Quotas.quotaZookeeper.length(), path.indexOf(endString));
                updateQuotaForPath(realPath);
                this.pTrie.addPath(realPath);
            }
            return;
        }
        for (String child : children) {
            traverseNode(path + "/" + child);
        }
    }

    /**
     * this method sets up the path trie and sets up stats for quota nodes
     */
    private void setupQuota() {
        String quotaPath = Quotas.quotaZookeeper;
        DataNode node = getNode(quotaPath);
        if (node == null) {
            return;
        }
        //配额设置
        traverseNode(quotaPath);
    }

    /**
     * this method uses a stringbuilder to create a new path for children. This
     * is faster than string appends ( str1 + str2).
     *
     * @param oa
     *            OutputArchive to write to.
     * @param path
     *            a string builder.
     * @throws IOException
     */
    void serializeNode(OutputArchive oa, StringBuilder path) throws IOException {
        String pathString = path.toString();
        DataNode node = getNode(pathString);
        if (node == null) {
            return;
        }
        String[] children = null;
        DataNode nodeCopy;
        synchronized (node) {
            StatPersisted statCopy = new StatPersisted();
            copyStatPersisted(node.stat, statCopy);
            //we do not need to make a copy of node.data because the contents
            //are never changed
            nodeCopy = new DataNode(node.data, node.acl, statCopy);
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
        }
        serializeNodeData(oa, pathString, nodeCopy);
        path.append('/');
        int off = path.length();
        for (String child : children) {
            // since this is single buffer being resused
            // we need
            // to truncate the previous bytes of string.
            path.delete(off, Integer.MAX_VALUE);
            path.append(child);
            serializeNode(oa, path);
        }
    }

    // visiable for test
    public void serializeNodeData(OutputArchive oa, String path, DataNode node) throws IOException {
        oa.writeString(path, "path");
        oa.writeRecord(node, "node");
    }

    public void serializeAcls(OutputArchive oa) throws IOException {
        aclCache.serialize(oa);
    }

    public void serializeNodes(OutputArchive oa) throws IOException {
        serializeNode(oa, new StringBuilder());
        // / marks end of stream
        // we need to check if clear had been called in between the snapshot.
        if (root != null) {
            oa.writeString("/", "path");
        }
    }

    public void serialize(OutputArchive oa, String tag) throws IOException {
        serializeAcls(oa);
        serializeNodes(oa);
    }

    public void deserialize(InputArchive ia, String tag) throws IOException {
        aclCache.deserialize(ia);
        nodes.clear();
        pTrie.clear();
        nodeDataSize.set(0);
        String path = ia.readString("path");
        while (!"/".equals(path)) {
            DataNode node = new DataNode();
            ia.readRecord(node, "node");
            nodes.put(path, node);
            synchronized (node) {
                aclCache.addUsage(node.acl);
            }
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash == -1) {
                root = node;
            } else {
                String parentPath = path.substring(0, lastSlash);
                DataNode parent = nodes.get(parentPath);//根据全路径查询父节点 DataNode
                if (parent == null) {
                    throw new IOException("Invalid Datatree, unable to find "
                                          + "parent "
                                          + parentPath
                                          + " of path "
                                          + path);
                }
                parent.addChild(path.substring(lastSlash + 1));
                long eowner = node.stat.getEphemeralOwner();
                EphemeralType ephemeralType = EphemeralType.get(eowner);
                if (ephemeralType == EphemeralType.CONTAINER) {
                    containers.add(path);
                } else if (ephemeralType == EphemeralType.TTL) {
                    ttls.add(path);
                } else if (eowner != 0) {
                    HashSet<String> list = ephemerals.get(eowner);
                    if (list == null) {
                        list = new HashSet<String>();
                        ephemerals.put(eowner, list);
                    }
                    list.add(path);
                }
            }
            path = ia.readString("path");
        }
        // have counted digest for root node with "", ignore here to avoid
        // counting twice for root node
        nodes.putWithoutDigest("/", root);

        nodeDataSize.set(approximateDataSize());

        // we are done with deserializing the
        // the datatree
        // update the quotas - create path trie
        // and also update the stat nodes
        setupQuota();

        aclCache.purgeUnused();
    }

    /**
     * Summary of the watches on the datatree.
     * @param pwriter the output to write to
     */
    public synchronized void dumpWatchesSummary(PrintWriter pwriter) {
        pwriter.print(dataWatches.toString());
    }

    /**
     * Write a text dump of all the watches on the datatree.
     * Warning, this is expensive, use sparingly!
     * @param pwriter the output to write to
     */
    public synchronized void dumpWatches(PrintWriter pwriter, boolean byPath) {
        dataWatches.dumpWatches(pwriter, byPath);
    }

    /**
     * Returns a watch report.
     *
     * @return watch report
     * @see WatchesReport
     */
    public synchronized WatchesReport getWatches() {
        return dataWatches.getWatches();
    }

    /**
     * Returns a watch report by path.
     *
     * @return watch report
     * @see WatchesPathReport
     */
    public synchronized WatchesPathReport getWatchesByPath() {
        return dataWatches.getWatchesByPath();
    }

    /**
     * Returns a watch summary.
     *
     * @return watch summary
     * @see WatchesSummary
     */
    public synchronized WatchesSummary getWatchesSummary() {
        return dataWatches.getWatchesSummary();
    }

    /**
     * Write a text dump of all the ephemerals in the datatree.
     * @param pwriter the output to write to
     */
    public void dumpEphemerals(PrintWriter pwriter) {
        pwriter.println("Sessions with Ephemerals (" + ephemerals.keySet().size() + "):");
        for (Entry<Long, HashSet<String>> entry : ephemerals.entrySet()) {
            pwriter.print("0x" + Long.toHexString(entry.getKey()));
            pwriter.println(":");
            Set<String> tmp = entry.getValue();
            if (tmp != null) {
                synchronized (tmp) {
                    for (String path : tmp) {
                        pwriter.println("\t" + path);
                    }
                }
            }
        }
    }

    public void shutdownWatcher() {
        dataWatches.shutdown();
        childWatches.shutdown();
    }

    /**
     * Returns a mapping of session ID to ephemeral znodes.
     *
     * @return map of session ID to sets of ephemeral znodes
     */
    public Map<Long, Set<String>> getEphemerals() {
        Map<Long, Set<String>> ephemeralsCopy = new HashMap<Long, Set<String>>();
        for (Entry<Long, HashSet<String>> e : ephemerals.entrySet()) {
            synchronized (e.getValue()) {
                ephemeralsCopy.put(e.getKey(), new HashSet<String>(e.getValue()));
            }
        }
        return ephemeralsCopy;
    }

    public void removeCnxn(Watcher watcher) {
        dataWatches.removeWatcher(watcher);
        childWatches.removeWatcher(watcher);
    }

    public void setWatches(long relativeZxid, List<String> dataWatches, List<String> existWatches, List<String> childWatches,
                           List<String> persistentWatches, List<String> persistentRecursiveWatches, Watcher watcher) {
        for (String path : dataWatches) {
            DataNode node = getNode(path);
            WatchedEvent e = null;
            if (node == null) {
                watcher.process(new WatchedEvent(EventType.NodeDeleted, KeeperState.SyncConnected, path));
            } else if (node.stat.getMzxid() > relativeZxid) {
                watcher.process(new WatchedEvent(EventType.NodeDataChanged, KeeperState.SyncConnected, path));
            } else {
                this.dataWatches.addWatch(path, watcher);
            }
        }
        for (String path : existWatches) {
            DataNode node = getNode(path);
            if (node != null) {
                watcher.process(new WatchedEvent(EventType.NodeCreated, KeeperState.SyncConnected, path));
            } else {
                this.dataWatches.addWatch(path, watcher);
            }
        }
        for (String path : childWatches) {
            DataNode node = getNode(path);
            if (node == null) {
                watcher.process(new WatchedEvent(EventType.NodeDeleted, KeeperState.SyncConnected, path));
            } else if (node.stat.getPzxid() > relativeZxid) {
                watcher.process(new WatchedEvent(EventType.NodeChildrenChanged, KeeperState.SyncConnected, path));
            } else {
                this.childWatches.addWatch(path, watcher);
            }
        }
        for (String path : persistentWatches) {
            this.childWatches.addWatch(path, watcher, WatcherMode.PERSISTENT);
            this.dataWatches.addWatch(path, watcher, WatcherMode.PERSISTENT);
        }
        for (String path : persistentRecursiveWatches) {
            this.childWatches.addWatch(path, watcher, WatcherMode.PERSISTENT_RECURSIVE);
            this.dataWatches.addWatch(path, watcher, WatcherMode.PERSISTENT_RECURSIVE);
        }
    }

    /**
     * This method sets the Cversion and Pzxid for the specified node to the
     * values passed as arguments. The values are modified only if newCversion
     * is greater than the current Cversion. A NoNodeException is thrown if
     * a znode for the specified path is not found.
     *
     * @param path
     *     Full path to the znode whose Cversion needs to be modified.
     *     A "/" at the end of the path is ignored.
     * @param newCversion
     *     Value to be assigned to Cversion
     * @param zxid
     *     Value to be assigned to Pzxid
     * @throws KeeperException.NoNodeException
     *     If znode not found.
     **/
    public void setCversionPzxid(String path, int newCversion, long zxid) throws KeeperException.NoNodeException {
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        DataNode node = nodes.get(path);
        if (node == null) {
            throw new KeeperException.NoNodeException(path);
        }
        synchronized (node) {
            if (newCversion == -1) {
                newCversion = node.stat.getCversion() + 1;
            }
            if (newCversion > node.stat.getCversion()) {
                nodes.preChange(path, node);
                node.stat.setCversion(newCversion);
                node.stat.setPzxid(zxid);
                nodes.postChange(path, node);
            }
        }
    }

    public boolean containsWatcher(String path, WatcherType type, Watcher watcher) {
        boolean containsWatcher = false;
        switch (type) {
        case Children:
            containsWatcher = this.childWatches.containsWatcher(path, watcher);
            break;
        case Data:
            containsWatcher = this.dataWatches.containsWatcher(path, watcher);
            break;
        case Any:
            if (this.childWatches.containsWatcher(path, watcher)) {
                containsWatcher = true;
            }
            if (this.dataWatches.containsWatcher(path, watcher)) {
                containsWatcher = true;
            }
            break;
        }
        return containsWatcher;
    }

    public boolean removeWatch(String path, WatcherType type, Watcher watcher) {
        boolean removed = false;
        switch (type) {
        case Children:
            removed = this.childWatches.removeWatcher(path, watcher);
            break;
        case Data:
            removed = this.dataWatches.removeWatcher(path, watcher);
            break;
        case Any:
            if (this.childWatches.removeWatcher(path, watcher)) {
                removed = true;
            }
            if (this.dataWatches.removeWatcher(path, watcher)) {
                removed = true;
            }
            break;
        }
        return removed;
    }

    // visible for testing
    public ReferenceCountedACLCache getReferenceCountedAclCache() {
        return aclCache;
    }

    private String getTopNamespace(String path) {
        String[] parts = path.split("/");
        return parts.length > 1 ? parts[1] : null;
    }

    private void updateReadStat(String path, long bytes) {
        String namespace = getTopNamespace(path);
        if (namespace == null) {
            return;
        }
        long totalBytes = path.length() + bytes + STAT_OVERHEAD_BYTES;
        ServerMetrics.getMetrics().READ_PER_NAMESPACE.add(namespace, totalBytes);
    }

    private void updateWriteStat(String path, long bytes) {
        String namespace = getTopNamespace(path);
        if (namespace == null) {
            return;
        }
        ServerMetrics.getMetrics().WRITE_PER_NAMESPACE.add(namespace, path.length() + bytes);
    }

    /**
     * Add the digest to the historical list, and update the latest zxid digest.
     *
     * 该方法的作用是：
     *   记录事务摘要历史：将每个 zxid（事务 ID）对应的数据树摘要值保存到历史记录中
     *   周期性打点：每隔一定间隔（默认每 128 个事务）保存一次摘要快照
     *   为数据校验提供依据：后续可以通过对比摘要值来检测数据是否损坏或不一致
     *
     *  1️⃣ 正常运行阶段：
     *  事务执行 → 数据树变更 → 计算新 digest → logZxidDigest()
     *                                     ↓
     *                     每 128 个事务保存一次 digest 快照
     *                                     ↓
     *                         保存在 digestLog 循环队列中
     *  2️⃣ 快照持久化阶段
     *  创建快照时 → serializeZxidDigest()
     *           ↓
     *  将最新的 digest 写入快照文件
     * （包含：zxid + digestVersion + digest 值）
     *
     * 3️⃣ 恢复校验阶段
     *  加载快照 → deserializeZxidDigest()
     *          ↓
     *  读取快照中的 digest 信息
     *          ↓
     *  重放事务日志 → compareSnapshotDigests()
     *             ↓
     *         到达快照 zxid 时，比较实际 digest 与快照 digest
     *             ↓
     *         ✅ 一致 → 数据完整
     *         ❌ 不一致 → 报告数据损坏
     *
     * 这个机制主要用于检测：
     * | 问题类型 | 说明 |
     * |---------|------|
     * | **磁盘损坏** | 存储介质故障导致数据位翻转 |
     * | **软件 Bug** | 代码缺陷导致数据计算错误 |
     * | **硬件故障** | 内存 ECC 错误、CPU 计算错误等 |
     * | **网络传输错误** | Leader-Follower 同步时的数据损坏 |
     * | **并发问题** | 多线程竞争导致的状态不一致 |
     *
     * 假设一个典型场景：
     * 时间线：
     * T1: Server A 正常运行
     *     - 执行事务 zxid=0x1000, digest=0xABCD
     *     - 执行事务 zxid=0x1001, digest=0xEF01
     *     - ...
     *     - 执行事务 zxid=0x1080 (128 的倍数), 记录 digest 到 digestLog
     *
     * T2: Server A 创建快照
     *     - 序列化数据树
     *     - 调用 serializeZxidDigest() 将当前 digest 写入快照
     *
     * T3: Server A 宕机
     *
     * T4: Server B 恢复数据
     *     - 加载快照，读取 digest=0xWXYZ
     *     - 重放事务日志
     *     - 当重放到 zxid=0x1080 时：
     *       * 计算当前实际 digest
     *       * 调用 compareSnapshotDigests()
     *       * ✅ 如果匹配 → 继续
     *       * ❌ 如果不匹配 → 触发告警，通知管理员
     *
     * ⚙️ 设计亮点
     *  低开销：不是每个事务都记录，而是每 128 个事务记录一次（DIGEST_LOG_INTERVAL = 128）
     *  循环队列：保持最近 1024 条记录（DIGEST_LOG_LIMIT = 1024），避免内存无限增长
     *  版本控制：包含 digestVersion 字段，支持摘要算法升级兼容
     *  双重校验：
     *   快照恢复时校验（compareSnapshotDigests）
     *   事务日志重放时校验（compareDigest）
     *  速率限制日志：使用 RateLogger 避免重复错误刷屏（15 分钟间隔）
     *
     * logZxidDigest 是 ZooKeeper 数据完整性保护体系的关键组件，它通过：
     *   ✅ 周期性记录数据树摘要快照
     *   ✅ 持久化到快照文件
     *   ✅ 恢复时校验确保数据一致性
     *   ✅ 实时检测硬件/软件故障导致的数据损坏
     * 这是一个典型的用空间换安全的设计，以极小的性能开销（每 128 个事务记录一次）提供了强大的数据完整性保障能力。
     */
    private void logZxidDigest(long zxid, long digest) {
        // ① 创建摘要记录对象（包含 zxid、摘要版本、摘要值）
        ZxidDigest zxidDigest = new ZxidDigest(zxid, digestCalculator.getDigestVersion(), digest);
        // ② 更新最新的摘要记录
        lastProcessedZxidDigest = zxidDigest;
        // ③ 周期性记录：每 DIGEST_LOG_INTERVAL (128) 个事务保存一次
        if (zxidDigest.zxid % DIGEST_LOG_INTERVAL == 0) {
            synchronized (digestLog) {
                digestLog.add(zxidDigest);
                if (digestLog.size() > DIGEST_LOG_LIMIT) {
                    digestLog.poll();
                }
            }
        }
    }

    /**
     * Serializing the digest to snapshot, this is done after the data tree
     * is being serialized, so when we replay the txns and it hits this zxid
     * we know we should be in a non-fuzzy state, and have the same digest.
     *
     * @param oa the output stream to write to
     * @return true if the digest is serialized successfully
     */
    public boolean serializeZxidDigest(OutputArchive oa) throws IOException {
        if (!ZooKeeperServer.isDigestEnabled()) {
            return false;
        }

        ZxidDigest zxidDigest = lastProcessedZxidDigest;
        if (zxidDigest == null) {
            // write an empty digest
            zxidDigest = new ZxidDigest();
        }
        zxidDigest.serialize(oa);
        return true;
    }

    /**
     * Deserializing the zxid digest from the input stream and update the
     * digestFromLoadedSnapshot.
     *
     * @param ia the input stream to read from
     * @param startZxidOfSnapshot the zxid of snapshot file
     * @return the true if it deserialized successfully
     */
    public boolean deserializeZxidDigest(InputArchive ia, long startZxidOfSnapshot) throws IOException {
        if (!ZooKeeperServer.isDigestEnabled()) {
            return false;
        }

        try {
            ZxidDigest zxidDigest = new ZxidDigest();
            zxidDigest.deserialize(ia);
            if (zxidDigest.zxid > 0) {
                digestFromLoadedSnapshot = zxidDigest;
                LOG.info("The digest in the snapshot has digest version of {}, "
                        + ", with zxid as 0x{}, and digest value as {}",
                        digestFromLoadedSnapshot.digestVersion,
                        Long.toHexString(digestFromLoadedSnapshot.zxid),
                        digestFromLoadedSnapshot.digest);
            } else {
                digestFromLoadedSnapshot = null;
                LOG.info("The digest value is empty in snapshot");
            }

            // There is possibility that the start zxid of a snapshot might
            // be larger than the digest zxid in snapshot.
            //
            // Known cases:
            //
            // The new leader set the last processed zxid to be the new
            // epoch + 0, which is not mapping to any txn, and it uses
            // this to take snapshot, which is possible if we don't
            // clean database before switching to LOOKING. In this case
            // the currentZxidDigest will be the zxid of last epoch and
            // it's smaller than the zxid of the snapshot file.
            //
            // It's safe to reset the targetZxidDigest to null and start
            // to compare digest when replaying the first txn, since it's
            // a non fuzzy snapshot.
            if (digestFromLoadedSnapshot != null && digestFromLoadedSnapshot.zxid < startZxidOfSnapshot) {
                LOG.info("The zxid of snapshot digest 0x{} is smaller "
                        + "than the known snapshot highest zxid, the snapshot "
                        + "started with zxid 0x{}. It will be invalid to use "
                        + "this snapshot digest associated with this zxid, will "
                        + "ignore comparing it.", Long.toHexString(digestFromLoadedSnapshot.zxid),
                        Long.toHexString(startZxidOfSnapshot));
                digestFromLoadedSnapshot = null;
            }

            return true;
        } catch (EOFException e) {
            LOG.warn("Got EOF exception while reading the digest, likely due to the reading an older snapshot.");
            return false;
        }
    }

    /**
     * Compares the actual tree's digest with that in the snapshot.
     * Resets digestFromLoadedSnapshot after comparision.
     *
     * 比较当前内存中数据树的摘要（digest）与从快照文件中读取的摘要是否一致
     * 检测数据损坏、存储错误或不一致问题
     * 校验完成后重置 digestFromLoadedSnapshot 标志
     *
     * @param zxid zxid
     */
    public void compareSnapshotDigests(long zxid) {
        // 条件 1：检查是否到达了快照对应的 zxid
        if (zxid == digestFromLoadedSnapshot.zxid) {
            // 条件 2：检查摘要算法版本是否一致
            if (digestCalculator.getDigestVersion() != digestFromLoadedSnapshot.digestVersion) {
                LOG.info(
                    "Digest version changed, local: {}, new: {}, skip comparing digest now.",
                    digestFromLoadedSnapshot.digestVersion,
                    digestCalculator.getDigestVersion());
                digestFromLoadedSnapshot = null; // 放弃校验
                return;
            }
            // 条件 3：核心校验 - 比较实际摘要值
            if (getTreeDigest() != digestFromLoadedSnapshot.getDigest()) {
                reportDigestMismatch(zxid);
            }
            digestFromLoadedSnapshot = null;  // ✅ 校验完成，清空调试状态
        } else if (digestFromLoadedSnapshot.zxid != 0 && zxid > digestFromLoadedSnapshot.zxid) {
            // 条件 4：超过了目标 zxid 但未找到对应事务
            RATE_LOGGER.rateLimitLog("The txn 0x{} of snapshot digest does not "
                    + "exist.", Long.toHexString(digestFromLoadedSnapshot.zxid));
        }
    }

    /**
     * Compares the digest of the tree with the digest present in transaction digest.
     * If there is any error, logs and alerts the watchers.
     *
     * @param header transaction header being applied
     * @param txn    transaction
     * @param digest transaction digest
     *
     * @return false if digest in the txn doesn't match what we have now in
     *               the data tree
     * 验证事务日志中存储的数据树摘要（digest）与当前实际数据树的摘要是否一致，用于检测数据损坏或不一致问题。
     *
     */
    public boolean compareDigest(TxnHeader header, Record txn, TxnDigest digest) {
        long zxid = header.getZxid();

        // 禁用摘要检查：如果系统未启用 digest 功能
        // 无摘要信息：如果事务日志中没有存储 digest
        if (!ZooKeeperServer.isDigestEnabled() || digest == null) {
            return true;
        }
        // do not compare digest if we're still in fuzzy state
        // 模糊状态：如果刚从 snapshot 恢复，还未完全同步到最新状态
        // 此时数据树处于"模糊期"，digest 可能不准确
        if (digestFromLoadedSnapshot != null) {
            return true;
        }
        // do not compare digest if there is digest version change 版本不匹配：digest 计算算法版本不同，无法比较
        if (digestCalculator.getDigestVersion() != digest.getVersion()) {
            RATE_LOGGER.rateLimitLog("Digest version not the same on zxid.",
                    String.valueOf(zxid));
            return true;
        }

        long logDigest = digest.getTreeDigest();
        long actualDigest = getTreeDigest();
        if (logDigest != actualDigest) {
            reportDigestMismatch(zxid);
            LOG.debug("Digest in log: {}, actual tree: {}", logDigest, actualDigest);
            if (firstMismatchTxn) {
                LOG.error("First digest mismatch on txn: {}, {}, "
                        + "expected digest is {}, actual digest is {}, ",
                        header, txn, digest, actualDigest);
                firstMismatchTxn = false;
            }
            return false;
        } else {
            RATE_LOGGER.flush();
            LOG.debug("Digests are matching for Zxid: {}, Digest in log "
                    + "and actual tree: {}", Long.toHexString(zxid), logDigest);
            return true;
        }
    }

    /**
     * Reports any mismatch in the transaction digest.
     * @param zxid zxid for which the error is being reported.
     */
    public void reportDigestMismatch(long zxid) {
        // 1. 统计指标
        ServerMetrics.getMetrics().DIGEST_MISMATCHES_COUNT.add(1);
        // 2. 限流日志
        RATE_LOGGER.rateLimitLog("Digests are not matching. Value is Zxid.", String.valueOf(zxid));

        // 3. 通知所有注册的观察者
        for (DigestWatcher watcher : digestWatchers) {
            watcher.process(zxid);
        }
    }

    public long getTreeDigest() {
        return nodes.getDigest();
    }

    public ZxidDigest getLastProcessedZxidDigest() {
        return lastProcessedZxidDigest;
    }

    public ZxidDigest getDigestFromLoadedSnapshot() {
        return digestFromLoadedSnapshot;
    }

    /**
     * Add digest mismatch event handler.
     *
     * @param digestWatcher the handler to add
     */
    public void addDigestWatcher(DigestWatcher digestWatcher) {
        digestWatchers.add(digestWatcher);
    }

    /**
     * Return all the digests in the historical digest list.
     */
    public List<ZxidDigest> getDigestLog() {
        synchronized (digestLog) {
            // Return a copy of current digest log
            return new LinkedList<ZxidDigest>(digestLog);
        }
    }

    /**
     * A helper class to maintain the digest meta associated with specific zxid.
     */
    public class ZxidDigest {

        long zxid;
        // the digest value associated with this zxid
        long digest;
        // the version when the digest was calculated
        int digestVersion;

        ZxidDigest() {
            this(0, digestCalculator.getDigestVersion(), 0);
        }

        ZxidDigest(long zxid, int digestVersion, long digest) {
            this.zxid = zxid;
            this.digestVersion = digestVersion;
            this.digest = digest;
        }

        public void serialize(OutputArchive oa) throws IOException {
            oa.writeLong(zxid, "zxid");
            oa.writeInt(digestVersion, "digestVersion");
            oa.writeLong(digest, "digest");
        }

        public void deserialize(InputArchive ia) throws IOException {
            zxid = ia.readLong("zxid");
            digestVersion = ia.readInt("digestVersion");
            // the old version is using hex string as the digest
            if (digestVersion < 2) { // 旧版本：使用十六进制字符串存储摘要
                String d = ia.readString("digest");
                if (d != null) {
                    digest = Long.parseLong(d, 16);
                }
            } else {
                // 新版本（version >= 2）：直接存储 long 值
                digest = ia.readLong("digest");
            }
        }

        public long getZxid() {
            return zxid;
        }

        public int getDigestVersion() {
            return digestVersion;
        }

        public long getDigest() {
            return digest;
        }

    }

    /**
     * Create a node stat from the given params.
     *
     * @param zxid the zxid associated with the txn
     * @param time the time when the txn is created
     * @param ephemeralOwner the owner if the node is an ephemeral
     * @return the stat
     */
    public static StatPersisted createStat(long zxid, long time, long ephemeralOwner) {
        StatPersisted stat = new StatPersisted();
        stat.setCtime(time);
        stat.setMtime(time);
        stat.setCzxid(zxid);
        stat.setMzxid(zxid);
        stat.setPzxid(zxid);
        stat.setVersion(0);
        stat.setAversion(0);
        stat.setEphemeralOwner(ephemeralOwner);
        return stat;
    }
}
