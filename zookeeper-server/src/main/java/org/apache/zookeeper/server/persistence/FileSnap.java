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

package org.apache.zookeeper.server.persistence;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the snapshot interface.
 * it is responsible for storing, serializing
 * and deserializing the right snapshot.
 * and provides access to the snapshots.
 */
public class FileSnap implements SnapShot {

    File snapDir;
    SnapshotInfo lastSnapshotInfo = null;
    private volatile boolean close = false;
    private static final int VERSION = 2;
    private static final long dbId = -1;
    private static final Logger LOG = LoggerFactory.getLogger(FileSnap.class);
    public static final int SNAP_MAGIC = ByteBuffer.wrap("ZKSN".getBytes()).getInt();

    public static final String SNAPSHOT_FILE_PREFIX = "snapshot";

    public FileSnap(File snapDir) {
        this.snapDir = snapDir;
    }

    /**
     * get information of the last saved/restored snapshot
     * @return info of last snapshot
     */
    public SnapshotInfo getLastSnapshotInfo() {
        return this.lastSnapshotInfo;
    }

    /**
     * deserialize a data tree from the most recent snapshot
     * @return the zxid of the snapshot
     */
    public long deserialize(DataTree dt, Map<Long, Integer> sessions) throws IOException {
        // we run through 100 snapshots (not all of them)
        // if we cannot get it running within 100 snapshots
        // we should  give up
        List<File> snapList = findNValidSnapshots(100);//最新的100个快照
        if (snapList.size() == 0) {
            return -1L;
        }
        File snap = null;
        long snapZxid = -1;
        boolean foundValid = false;
        for (int i = 0, snapListSize = snapList.size(); i < snapListSize; i++) {
            snap = snapList.get(i);
            LOG.info("Reading snapshot {}", snap);
            snapZxid = Util.getZxidFromName(snap.getName(), SNAPSHOT_FILE_PREFIX);
            try (CheckedInputStream snapIS = SnapStream.getInputStream(snap)) {
                InputArchive ia = BinaryInputArchive.getArchive(snapIS);
                /**
                 * 总结下这个文件读取格式：
                 *  1. 读取文件头 FileHeader {@link FileHeader}，见 {@link #deserialize(DataTree, Map, InputArchive)}
                 *  2. 读取count代表有多少个会话，然后读取会话数据存入到sessions：会话id->会话超时时间,见{@link SerializeUtils#deserializeSnapshot(DataTree, InputArchive, Map)}
                 *  3. 读取map代表有多个acl数据，这里创建了2个Map，见{@link org.apache.zookeeper.server.ReferenceCountedACLCache#deserialize(InputArchive)}
                 *  4. 剩下都是节点数据了，读取path读完，见{@link DataTree#deserialize(InputArchive, String)})}
                 *  可以看出这个协议很简单，很节省存储空间，一个长度然后是具体数据这样的
                 */
                deserialize(dt, sessions, ia);
                SnapStream.checkSealIntegrity(snapIS, ia);// 校验范围：FileHeader + DataTree

                // Digest feature was added after the CRC to make it backward
                // compatible, the older code can still read snapshots which
                // includes digest.
                //
                // To check the intact, after adding digest we added another
                // CRC check.
                if (dt.deserializeZxidDigest(ia, snapZxid)) {
                    /**
                     * 为什么要设计两次 CRC？
                     *  原因 1：向后兼容性
                     *  场景：
                     *      旧版本 ZooKeeper 读取新格式的快照
                     *          会读取 FileHeader → DataTree → CRC1
                     *          验证 CRC1 通过后，认为快照有效 ✓
                     *          后面的 ZxidDigest 和 CRC2 被忽略（不会解析）
                     *      新版本 ZooKeeper 读取旧格式的快照
                     *          读取 FileHeader → DataTree → CRC1
                     *          验证 CRC1 通过 ✓
                     *          尝试读取 ZxidDigest 时遇到 EOF 异常
                     *          deserializeZxidDigest() 捕获 EOF 返回 false
                     *          不执行第二次 CRC 校验
                     *  原因 2：分层校验，更精细的完整性保证
                     *  好处：
                     *      CRC1：保护核心数据（会话、ACL、节点数据）
                     *      CRC2：专门保护 ZxidDigest（用于跨节点数据一致性校验）
                     *  如果只有一个 CRC 覆盖所有内容，当校验失败时无法定位是哪个部分损坏。分开校验可以：
                     *      精确定位问题（是数据损坏还是 Digest 损坏）
                     *      提高可靠性（即使 Digest 损坏，核心数据可能仍是好的）
                     * 📊 实际示例对比：
                     *  旧版本快照文件结构
                     *  snapshot.10000001:
                     *   [FileHeader]      // 魔数 + 版本 + dbId
                     *   [Sessions]        // 会话数据
                     *   [ACLs]           // ACL 数据
                     *   [Nodes]          // 节点数据
                     *   [CRC1]           // 校验以上所有数据
                     *   ["/"]            // 结束标记
                     *  新版本快照文件结构
                     *  snapshot.10000001:
                     *   [FileHeader]      // 魔数 + 版本 + dbId
                     *   [Sessions]        // 会话数据
                     *   [ACLs]           // ACL 数据
                     *   [Nodes]          // 节点数据
                     *   [CRC1]           // 校验以上所有数据 ← 第一次校验
                     *   ["/"]            // 结束标记
                     *   [ZxidDigest]     // ZXID 摘要（新增）
                     *   [CRC2]           // 校验 ZxidDigest ← 第二次校验
                     *   ["/"]            // 结束标记
                     * 两次 CRC 校验的设计体现了优秀的软件工程实践：
                     *  向后兼容：旧代码能读新文件，新代码能读旧文件
                     *  渐进式增强：在不破坏现有功能的前提下添加新特性
                     *  分层校验：不同层次的数据独立校验，便于问题定位
                     *  防御性编程：即使一部分损坏，另一部分仍可能可用
                     * 这正是分布式系统数据持久化设计的经典案例！
                     */
                    SnapStream.checkSealIntegrity(snapIS, ia);// 校验范围：ZxidDigest
                }

                foundValid = true;
                break;
            } catch (IOException e) {
                LOG.warn("problem reading snap file {}", snap, e);
            }
        }
        if (!foundValid) {
            throw new IOException("Not able to find valid snapshots in " + snapDir);
        }
        //最新的快照文件的zxid
        dt.lastProcessedZxid = snapZxid;
        lastSnapshotInfo = new SnapshotInfo(dt.lastProcessedZxid, snap.lastModified() / 1000);

        // compare the digest if this is not a fuzzy snapshot, we want to compare
        // and find inconsistent asap.
        if (dt.getDigestFromLoadedSnapshot() != null) {
            dt.compareSnapshotDigests(dt.lastProcessedZxid);
        }
        return dt.lastProcessedZxid;
    }

    /**
     * deserialize the datatree from an inputarchive
     * @param dt the datatree to be serialized into
     * @param sessions the sessions to be filled up
     * @param ia the input archive to restore from
     * @throws IOException
     */
    public void deserialize(DataTree dt, Map<Long, Integer> sessions, InputArchive ia) throws IOException {
        FileHeader header = new FileHeader();
        // int magic;
        // int version;
        // long dbid;
        header.deserialize(ia, "fileheader");
        if (header.getMagic() != SNAP_MAGIC) {
            throw new IOException("mismatching magic headers " + header.getMagic() + " !=  " + FileSnap.SNAP_MAGIC);
        }
        SerializeUtils.deserializeSnapshot(dt, ia, sessions);
    }

    /**
     * find the most recent snapshot in the database.
     * @return the file containing the most recent snapshot
     */
    public File findMostRecentSnapshot() throws IOException {
        List<File> files = findNValidSnapshots(1);
        if (files.size() == 0) {
            return null;
        }
        return files.get(0);
    }

    /**
     * find the last (maybe) valid n snapshots. this does some
     * minor checks on the validity of the snapshots. It just
     * checks for / at the end of the snapshot. This does
     * not mean that the snapshot is truly valid but is
     * valid with a high probability. also, the most recent
     * will be first on the list.
     * @param n the number of most recent snapshots
     * @return the last n snapshots (the number might be
     * less than n in case enough snapshots are not available).
     * @throws IOException
     */
    protected List<File> findNValidSnapshots(int n) throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), SNAPSHOT_FILE_PREFIX, false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f : files) {
            // we should catch the exceptions
            // from the valid snapshot and continue
            // until we find a valid one
            try {
                if (SnapStream.isValidSnapshot(f)) {
                    list.add(f);
                    count++;
                    if (count == n) {
                        break;
                    }
                }
            } catch (IOException e) {
                LOG.warn("invalid snapshot {}", f, e);
            }
        }
        return list;
    }

    /**
     * find the last n snapshots. this does not have
     * any checks if the snapshot might be valid or not
     * @param n the number of most recent snapshots
     * @return the last n snapshots
     * @throws IOException
     */
    public List<File> findNRecentSnapshots(int n) throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), SNAPSHOT_FILE_PREFIX, false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f : files) {
            if (count == n) {
                break;
            }
            if (Util.getZxidFromName(f.getName(), SNAPSHOT_FILE_PREFIX) != -1) {
                count++;
                list.add(f);
            }
        }
        return list;
    }

    /**
     * serialize the datatree and sessions
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param oa the output archive to serialize into
     * @param header the header of this snapshot
     * @throws IOException
     */
    protected void serialize(
        DataTree dt,
        Map<Long, Integer> sessions,
        OutputArchive oa,
        FileHeader header) throws IOException {
        // this is really a programmatic error and not something that can
        // happen at runtime
        if (header == null) {
            throw new IllegalStateException("Snapshot's not open for writing: uninitialized header");
        }
        header.serialize(oa, "fileheader");
        SerializeUtils.serializeSnapshot(dt, oa, sessions);
    }

    /**
     * serialize the datatree and session into the file snapshot
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param snapShot the file to store snapshot into
     * @param fsync sync the file immediately after write
     */
    public synchronized void serialize(
        DataTree dt,
        Map<Long, Integer> sessions,
        File snapShot,
        boolean fsync) throws IOException {
        if (!close) {
            try (CheckedOutputStream snapOS = SnapStream.getOutputStream(snapShot, fsync)) {
                OutputArchive oa = BinaryOutputArchive.getArchive(snapOS);
                FileHeader header = new FileHeader(SNAP_MAGIC, VERSION, dbId);
                serialize(dt, sessions, oa, header);
                SnapStream.sealStream(snapOS, oa);

                // Digest feature was added after the CRC to make it backward
                // compatible, the older code cal still read snapshots which
                // includes digest.
                //
                // To check the intact, after adding digest we added another
                // CRC check.
                if (dt.serializeZxidDigest(oa)) {
                    SnapStream.sealStream(snapOS, oa);
                }

                lastSnapshotInfo = new SnapshotInfo(
                    Util.getZxidFromName(snapShot.getName(), SNAPSHOT_FILE_PREFIX),
                    snapShot.lastModified() / 1000);
            }
        } else {
            throw new IOException("FileSnap has already been closed");
        }
    }

    private void writeChecksum(CheckedOutputStream crcOut, OutputArchive oa) throws IOException {
        long val = crcOut.getChecksum().getValue();
        oa.writeLong(val, "val");
        oa.writeString("/", "path");
    }

    private void checkChecksum(CheckedInputStream crcIn, InputArchive ia) throws IOException {
        long checkSum = crcIn.getChecksum().getValue();
        long val = ia.readLong("val");
        // read and ignore "/" written by writeChecksum
        ia.readString("path");
        if (val != checkSum) {
            throw new IOException("CRC corruption");
        }
    }

    /**
     * synchronized close just so that if serialize is in place
     * the close operation will block and will wait till serialize
     * is done and will set the close flag
     */
    @Override
    public synchronized void close() throws IOException {
        close = true;
    }

}
