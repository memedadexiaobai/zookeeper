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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.TxnLogEntry;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the TxnLog interface. It provides api's
 * to access the txnlogs and add entries to it.
 * <p>
 * The format of a Transactional log is as follows:
 * <blockquote><pre>
 * LogFile:
 *     FileHeader TxnList ZeroPad
 *
 * FileHeader: {
 *     magic 4bytes (ZKLG)
 *     version 4bytes
 *     dbid 8bytes
 *   }
 *
 * TxnList:
 *     Txn || Txn TxnList
 *
 * Txn:
 *     checksum Txnlen TxnHeader Record 0x42
 *
 * checksum: 8bytes Adler32 is currently used
 *   calculated across payload -- Txnlen, TxnHeader, Record and 0x42
 *
 * Txnlen:
 *     len 4bytes
 *
 * TxnHeader: {
 *     sessionid 8bytes
 *     cxid 4bytes
 *     zxid 8bytes
 *     time 8bytes
 *     type 4bytes
 *   }
 *
 * Record:
 *     See Jute definition file for details on the various record types
 *
 * ZeroPad:
 *     0 padded to EOF (filled during preallocation stage)
 * </pre></blockquote>
 */
public class FileTxnLog implements TxnLog, Closeable {

    private static final Logger LOG;

    public static final int TXNLOG_MAGIC = ByteBuffer.wrap("ZKLG".getBytes()).getInt();

    public static final int VERSION = 2;

    public static final String LOG_FILE_PREFIX = "log";

    static final String FSYNC_WARNING_THRESHOLD_MS_PROPERTY = "fsync.warningthresholdms";
    static final String ZOOKEEPER_FSYNC_WARNING_THRESHOLD_MS_PROPERTY = "zookeeper." + FSYNC_WARNING_THRESHOLD_MS_PROPERTY;

    /** Maximum time we allow for elapsed fsync before WARNing */
    private static final long fsyncWarningThresholdMS;

    /**
     * This parameter limit the size of each txnlog to a given limit (KB).
     * It does not affect how often the system will take a snapshot [zookeeper.snapCount]
     * We roll the txnlog when either of the two limits are reached.
     * Also since we only roll the logs at transaction boundaries, actual file size can exceed
     * this limit by the maximum size of a serialized transaction.
     * The feature is disabled by default (-1)
     */
    private static final String txnLogSizeLimitSetting = "zookeeper.txnLogSizeLimitInKb";

    /**
     * The actual txnlog size limit in bytes.
     */
    private static long txnLogSizeLimit = -1;

    static {
        LOG = LoggerFactory.getLogger(FileTxnLog.class);

        /** Local variable to read fsync.warningthresholdms into */
        Long fsyncWarningThreshold;
        if ((fsyncWarningThreshold = Long.getLong(ZOOKEEPER_FSYNC_WARNING_THRESHOLD_MS_PROPERTY)) == null) {
            fsyncWarningThreshold = Long.getLong(FSYNC_WARNING_THRESHOLD_MS_PROPERTY, 1000);
        }
        fsyncWarningThresholdMS = fsyncWarningThreshold;

        Long logSize = Long.getLong(txnLogSizeLimitSetting, -1);
        if (logSize > 0) {
            LOG.info("{} = {}", txnLogSizeLimitSetting, logSize);

            // Convert to bytes
            logSize = logSize * 1024;
            txnLogSizeLimit = logSize;
        }
    }

    long lastZxidSeen;
    volatile BufferedOutputStream logStream = null;
    volatile OutputArchive oa;
    volatile FileOutputStream fos = null;

    File logDir;
    private final boolean forceSync = !System.getProperty("zookeeper.forceSync", "yes").equals("no");
    long dbId;
    private final Queue<FileOutputStream> streamsToFlush = new ArrayDeque<>();
    File logFileWrite = null;
    private FilePadding filePadding = new FilePadding();

    private ServerStats serverStats;

    private volatile long syncElapsedMS = -1L;

    /**
     * A running total of all complete log files
     * This does not include the current file being written to
     */
    private long prevLogsRunningTotal;

    /**
     * constructor for FileTxnLog. Take the directory
     * where the txnlogs are stored
     * @param logDir the directory where the txnlogs are stored
     */
    public FileTxnLog(File logDir) {
        this.logDir = logDir;
    }

    /**
     * method to allow setting preallocate size
     * of log file to pad the file.
     * @param size the size to set to in bytes
     */
    public static void setPreallocSize(long size) {
        FilePadding.setPreallocSize(size);
    }

    /**
     * Setter for ServerStats to monitor fsync threshold exceed
     * @param serverStats used to update fsyncThresholdExceedCount
     */
    @Override
    public synchronized void setServerStats(ServerStats serverStats) {
        this.serverStats = serverStats;
    }

    /**
     * Set log size limit
     */
    public static void setTxnLogSizeLimit(long size) {
        txnLogSizeLimit = size;
    }

    /**
     * Return the current on-disk size of log size. This will be accurate only
     * after commit() is called. Otherwise, unflushed txns may not be included.
     */
    public synchronized long getCurrentLogSize() {
        if (logFileWrite != null) {
            return logFileWrite.length();
        }
        return 0;
    }

    public synchronized void setTotalLogSize(long size) {
        prevLogsRunningTotal = size;
    }

    public synchronized long getTotalLogSize() {
        return prevLogsRunningTotal + getCurrentLogSize();
    }

    /**
     * creates a checksum algorithm to be used
     * @return the checksum used for this txnlog
     */
    protected Checksum makeChecksumAlgorithm() {
        return new Adler32();
    }

    /**
     * rollover the current log file to a new one.
     * @throws IOException
     */
    public synchronized void rollLog() throws IOException {
        if (logStream != null) {
            this.logStream.flush();
            prevLogsRunningTotal += getCurrentLogSize();
            this.logStream = null;
            oa = null;

            // Roll over the current log file into the running total
        }
    }

    /**
     * close all the open file handles
     * @throws IOException
     */
    public synchronized void close() throws IOException {
        if (logStream != null) {
            logStream.close();
        }
        for (FileOutputStream log : streamsToFlush) {
            log.close();
        }
    }

    /**
     * append an entry to the transaction log
     * @param hdr the header of the transaction
     * @param txn the transaction part of the entry
     * returns true iff something appended, otw false
     */
    public synchronized boolean append(TxnHeader hdr, Record txn) throws IOException {
              return append(hdr, txn, null);
    }

    /**
     * 数据写入的三个阶段
     *  阶段 1: append() - 写入内存缓冲区 ❌未落盘
     *    // append() 方法中
     *    logStream = new BufferedOutputStream(fos);  // 创建缓冲流
     *    oa.writeLong(crc.getValue(), "txnEntryCRC"); // 写入缓冲区
     *    Util.writeTxnBytes(oa, buf);                 // 写入缓冲区
     *    状态: 数据在 Java 堆内存的 BufferedOutputStream 中
     *  阶段 2: {@link #commit()} 刷新到操作系统缓存 ⚠️可能未落盘
     *  阶段 3: channel.force() - 真正写入磁盘 ✅已落盘
     *    只有当 forceSync = true 时才会执行，这会触发操作系统的 fsync() 系统调用。
     *
     * append() → commit() → [可选] forceSync
     *   ↓          ↓              ↓
     * 内存缓冲  OS 缓存        磁盘 (真正落盘)
     *
     * 1️⃣ append() 方法执行后 - 数据在 Java 内存缓冲区
     * logStream = new BufferedOutputStream(fos);  // 创建带缓冲的输出流
     * oa.writeLong(crc.getValue(), "txnEntryCRC"); // 写入 CRC 校验和到缓冲区
     * Util.writeTxnBytes(oa, buf);                 // 写入事务数据到缓冲区
     * ✅ Adler32 校验和已计算并写入缓冲区 ❌ 但数据还在 Java 进程的内存中，未到达磁盘
     *
     * 2️⃣ commit() 方法调用后 - 数据推到操作系统缓存
     * public synchronized void commit() throws IOException {
     *     logStream.flush();  // 将 Java 缓冲区数据推到操作系统的文件缓存
     *     for (FileOutputStream log : streamsToFlush) {
     *         log.flush();    // 确保数据到达操作系统缓存
     * ⚠️ 数据在操作系统的 Page Cache 中，如果此时宕机可能丢失
     *
     * 3️⃣ forceSync=true 时 - 真正写入磁盘
     * if (forceSync) {
     *     FileChannel channel = log.getChannel();
     *     channel.force(false);  // 调用 fsync() 强制写入磁盘
     * }
     * ✅ 数据真正持久化到磁盘，即使宕机也不会丢失
     *
     * 🔧 控制参数：forceSync
     * private final boolean forceSync = !System.getProperty("zookeeper.forceSync", "yes").equals("no");
     * 默认值: true (通过 JVM 系统属性 zookeeper.forceSync 控制)
     * zookeeper.forceSync=yes (默认): 每次 commit 都调用 fsync，保证数据不丢失
     * zookeeper.forceSync=no: 不调用 fsync，依赖操作系统刷盘，性能更好但有数据丢失风险
     *
     * 在 ZooKeeper 的写入流程中：
     *  Leader 接收写请求 → append() 追加到内存
     *  事务提交 → commit() 刷新到 OS 缓存
     *  如果开启 forceSync → fsync() 确保落盘
     *  同时通过网络同步给 Follower
     *  多数派确认后返回客户端成功
     */
    @Override //这个过程数据还在内存缓存中 并未落盘
    public synchronized boolean append(TxnHeader hdr, Record txn, TxnDigest digest) throws IOException {
        if (hdr == null) {
            return false;
        }
        if (hdr.getZxid() <= lastZxidSeen) {
            LOG.warn(
                "Current zxid {} is <= {} for {}",
                hdr.getZxid(),
                lastZxidSeen,
                hdr.getType());
        } else {
            lastZxidSeen = hdr.getZxid();
        }
        if (logStream == null) {
            LOG.info("Creating new log file: {}", Util.makeLogName(hdr.getZxid()));

            logFileWrite = new File(logDir, Util.makeLogName(hdr.getZxid()));
            fos = new FileOutputStream(logFileWrite);
            logStream = new BufferedOutputStream(fos); // 创建缓冲流
            oa = BinaryOutputArchive.getArchive(logStream);
            // 这只是放了个文件头 数据还没刷呢
            FileHeader fhdr = new FileHeader(TXNLOG_MAGIC, VERSION, dbId);
            fhdr.serialize(oa, "fileheader");
            // Make sure that the magic number is written before padding.
            logStream.flush();
            filePadding.setCurrentSize(fos.getChannel().position());
            streamsToFlush.add(fos);
        }
        filePadding.padFile(fos.getChannel());
        byte[] buf = Util.marshallTxnEntry(hdr, txn, digest);
        if (buf == null || buf.length == 0) {
            throw new IOException("Faulty serialization for header " + "and txn");
        }
        Checksum crc = makeChecksumAlgorithm();
        crc.update(buf, 0, buf.length);
        oa.writeLong(crc.getValue(), "txnEntryCRC");
        Util.writeTxnBytes(oa, buf);

        return true;
    }

    /**
     * Find the log file that starts at, or just before, the snapshot. Return
     * this and all subsequent logs. Results are ordered by zxid of file,
     * ascending order.
     * @param logDirList array of files
     * @param snapshotZxid return files at, or before this zxid
     * @return log files that starts at, or just before, the snapshot and subsequent ones
     * 查找包含指定 snapshotZxid 及之后所有事务的 log 文件集合
     *
     * 假设有以下 log 文件（按 zxid 排序）：
     *  log.100 (起始 zxid = 100)
     *  log.200 (起始 zxid = 200)
     *  log.300 (起始 zxid = 300)
     *  log.400 (起始 zxid = 400)
     * 调用示例：
     *   // 场景 1: snapshotZxid = 250
     *   getLogFiles(files, 250)
     *   // 返回：[log.200, log.300, log.400]
     *   // 解释：250 在 log.200 中，需要从这个文件开始恢复
     *
     *   // 场景 2: snapshotZxid = 300
     *   getLogFiles(files, 300)
     *   // 返回：[log.300, log.400]
     *   // 解释：从正好包含 300 的文件开始
     *
     *   // 场景 3: getLastLoggedZxid() 调用
     *   getLogFiles(files, 0)
     *   // 返回：[log.100, log.200, log.300, log.400]
     *   // 解释：获取所有文件来查找最大的 zxid
     */
    public static File[] getLogFiles(File[] logDirList, long snapshotZxid) {
        // 1. 将 log 文件按 zxid 升序排序
        List<File> files = Util.sortDataDir(logDirList, LOG_FILE_PREFIX, true);
        long logZxid = 0;
        // Find the log file that starts before or at the same time as the
        // zxid of the snapshot
        // 2. 找到起始 zxid <= snapshotZxid 的最大 zxid 对应的文件
        for (File f : files) {
            long fzxid = Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX);
            if (fzxid > snapshotZxid) {
                break; // 超过 snapshotZxid 就停止
            }
            // the files
            // are sorted with zxid's
            if (fzxid > logZxid) {
                logZxid = fzxid;  // 记录不超过 snapshotZxid 的最大 zxid
            }
        }
        // 3. 返回从该文件开始的所有后续文件
        List<File> v = new ArrayList<File>(5);
        for (File f : files) {
            long fzxid = Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX);
            if (fzxid < logZxid) {
                continue; // 跳过比目标文件更早的文件
            }
            v.add(f);  // 添加目标文件及其之后的所有文件
        }
        return v.toArray(new File[0]);

    }

    /**
     * get the last zxid that was logged in the transaction logs
     * @return the last zxid logged in the transaction logs
     */
    public long getLastLoggedZxid() {
        File[] files = getLogFiles(logDir.listFiles(), 0);
        long maxLog = files.length > 0 ? Util.getZxidFromName(files[files.length - 1].getName(), LOG_FILE_PREFIX) : -1;

        // if a log file is more recent we must scan it to find
        // the highest zxid 获取到最新的一个zxid
        long zxid = maxLog;
        try (FileTxnLog txn = new FileTxnLog(logDir); TxnIterator itr = txn.read(maxLog)) {
            while (true) {
                if (!itr.next()) {
                    break;
                }
                TxnHeader hdr = itr.getHeader();
                zxid = hdr.getZxid();
            }
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        }
        return zxid;
    }

    /**
     * commit the logs. make sure that everything hits the
     * disk
     *
     * FileOutputStream.flush()
     *  作用: 将操作系统缓冲区的数据强制写入磁盘
     *  缓冲位置: 操作系统的 Page Cache（内核缓冲区）
     *  触发时机: 通常在关闭流或显式调用 flush 时
     *  性能: 频繁调用会影响性能（系统调用开销大）
     * BufferedOutputStream.flush()
     *  作用: 将Java 应用缓冲区的数据推送到下层流（并间接触发下层流的 flush）
     *  缓冲位置: Java 堆内存中的字节数组缓冲区
     *  触发时机: 缓冲区满、手动调用 flush、或关闭流时
     *  性能: 减少系统调用次数，提高 I/O 效率
     *
     * Java 应用空间                    操作系统内核空间                    磁盘
     * ┌─────────────┐              ┌─────────────────┐              ┌──────────┐
     * │   Heap      │              │   Page Cache    │              │          │
     * │  ┌───────┐  │  write()     │  ┌───────────┐  │  fsync()     │  文件    │
     * │  │Buffer │───────────────>│  │ OS Buffer │───────────────>│          │
     * │  │(8KB)  │  │ flush()      │  │           │  │ force(false)│          │
     * │  └───────┘  │              │  └───────────┘  │              │          │
     * └─────────────┘              └─────────────────┘              └──────────┘
     *      ↑                            ↑                                ↑
     *      │                            │                                │
     * BufferedOutputStream       FileOutputStream                    物理磁盘
     * flush()                    flush()
     * 清空 Java 缓冲区            触发内核刷新
     * 调用 fos.flush()
     *
     * | 特性 | FileOutputStream.flush() | BufferedOutputStream.flush() |
     * |------|-------------------------|------------------------------|
     * | **缓冲位置** | 操作系统内核缓冲区 (Page Cache) | Java 堆内存字节数组 (默认 8KB) |
     * | **作用** | 请求 OS 将数据写入磁盘 | 将 Java 缓冲区数据推送到下层流 |
     * | **系统调用** | 可能触发 `fsync()`/`fdatasync()` | 间接触发下层流的 flush |
     * | **性能影响** | 较大（用户态→内核态切换） | 较小（纯内存操作） |
     * | **数据安全性** | 中等（OS 崩溃仍可能丢失） | 低（JVM 崩溃就丢失） |
     * | **调用时机** | 关闭流、手动调用、或 OS 决定 | 缓冲区满、手动调用、关闭流 |
     *
     *
     * 使用 BufferedOutputStream 的原因：
     *  减少系统调用: 事务日志是高频写入操作，缓冲可以显著提升性能
     *  批量写入: 积累多个小事务后一次性推送给 OS
     *  可控的持久化: 通过显式调用 commit() 控制何时刷新，平衡性能和可靠性
     */
    public synchronized void commit() throws IOException {
        if (logStream != null) {
            logStream.flush(); // 将缓冲区数据推到操作系统的文件缓存
        }
        for (FileOutputStream log : streamsToFlush) {
            log.flush();   // 确保数据到达操作系统缓存
            if (forceSync) {  // 可选的强制同步
                long startSyncNS = System.nanoTime();

                FileChannel channel = log.getChannel();
                channel.force(false); // 真正强制写入磁盘

                syncElapsedMS = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startSyncNS);
                if (syncElapsedMS > fsyncWarningThresholdMS) {
                    if (serverStats != null) {
                        serverStats.incrementFsyncThresholdExceedCount();
                    }

                    LOG.warn(
                        "fsync-ing the write ahead log in {} took {}ms which will adversely effect operation latency."
                            + "File size is {} bytes. See the ZooKeeper troubleshooting guide",
                        Thread.currentThread().getName(),
                        syncElapsedMS,
                        channel.size());
                }

                ServerMetrics.getMetrics().FSYNC_TIME.add(syncElapsedMS);
            }
        }
        while (streamsToFlush.size() > 1) {
            streamsToFlush.poll().close();
        }

        // Roll the log file if we exceed the size limit
        if (txnLogSizeLimit > 0) {
            long logSize = getCurrentLogSize();

            if (logSize > txnLogSizeLimit) {
                LOG.debug("Log size limit reached: {}", logSize);
                rollLog();
            }
        }
    }

    /**
     *
     * @return elapsed sync time of transaction log in milliseconds
     */
    public long getTxnLogSyncElapsedTime() {
        return syncElapsedMS;
    }

    /**
     * start reading all the transactions from the given zxid
     * @param zxid the zxid to start reading transactions from
     * @return returns an iterator to iterate through the transaction
     * logs
     */
    public TxnIterator read(long zxid) throws IOException {
        return read(zxid, true);
    }

    /**
     * start reading all the transactions from the given zxid.
     *
     * @param zxid the zxid to start reading transactions from
     * @param fastForward true if the iterator should be fast forwarded to point
     *        to the txn of a given zxid, else the iterator will point to the
     *        starting txn of a txnlog that may contain txn of a given zxid
     * @return returns an iterator to iterate through the transaction logs
     */
    public TxnIterator read(long zxid, boolean fastForward) throws IOException {
        return new FileTxnIterator(logDir, zxid, fastForward);
    }

    /**
     * truncate the current transaction logs
     * @param zxid the zxid to truncate the logs to
     * @return true if successful false if not
     */
    public boolean truncate(long zxid) throws IOException {
        try (FileTxnIterator itr = new FileTxnIterator(this.logDir, zxid)) {
            PositionInputStream input = itr.inputStream;
            if (input == null) {
                throw new IOException("No log files found to truncate! This could "
                                      + "happen if you still have snapshots from an old setup or "
                                      + "log files were deleted accidentally or dataLogDir was changed in zoo.cfg.");
            }
            long pos = input.getPosition();
            // now, truncate at the current position
            RandomAccessFile raf = new RandomAccessFile(itr.logFile, "rw");
            raf.setLength(pos);
            raf.close();
            while (itr.goToNextLog()) {
                if (!itr.logFile.delete()) {
                    LOG.warn("Unable to truncate {}", itr.logFile);
                }
            }
        }
        return true;
    }

    /**
     * read the header of the transaction file
     * @param file the transaction file to read
     * @return header that was read from the file
     * @throws IOException
     */
    private static FileHeader readHeader(File file) throws IOException {
        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(file));
            InputArchive ia = BinaryInputArchive.getArchive(is);
            FileHeader hdr = new FileHeader();
            hdr.deserialize(ia, "fileheader");
            return hdr;
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                LOG.warn("Ignoring exception during close", e);
            }
        }
    }

    /**
     * the dbid of this transaction database
     * @return the dbid of this database
     */
    public long getDbId() throws IOException {
        FileTxnIterator itr = new FileTxnIterator(logDir, 0);
        FileHeader fh = readHeader(itr.logFile);
        itr.close();
        if (fh == null) {
            throw new IOException("Unsupported Format.");
        }
        return fh.getDbid();
    }

    /**
     * the forceSync value. true if forceSync is enabled, false otherwise.
     * @return the forceSync value
     */
    public boolean isForceSync() {
        return forceSync;
    }

    /**
     * a class that keeps track of the position
     * in the input stream. The position points to offset
     * that has been consumed by the applications. It can
     * wrap buffered input streams to provide the right offset
     * for the application.
     */
    static class PositionInputStream extends FilterInputStream {

        long position;
        protected PositionInputStream(InputStream in) {
            super(in);
            position = 0;
        }

        @Override
        public int read() throws IOException {
            int rc = super.read();
            if (rc > -1) {
                position++;
            }
            return rc;
        }

        public int read(byte[] b) throws IOException {
            int rc = super.read(b);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int rc = super.read(b, off, len);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }

        @Override
        public long skip(long n) throws IOException {
            long rc = super.skip(n);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }
        public long getPosition() {
            return position;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void mark(int readLimit) {
            throw new UnsupportedOperationException("mark");
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("reset");
        }

    }

    /**
     * this class implements the txnlog iterator interface
     * which is used for reading the transaction logs
     */
    public static class FileTxnIterator implements TxnLog.TxnIterator {

        File logDir;
        long zxid;
        TxnHeader hdr;
        Record record;
        TxnDigest digest;
        File logFile;
        InputArchive ia;
        static final String CRC_ERROR = "CRC check failed";

        PositionInputStream inputStream = null;
        //stored files is the list of files greater than
        //the zxid we are looking for.
        private ArrayList<File> storedFiles;

        /**
         * create an iterator over a transaction database directory
         * @param logDir the transaction database directory
         * @param zxid the zxid to start reading from
         * @param fastForward   true if the iterator should be fast forwarded to
         *        point to the txn of a given zxid, else the iterator will
         *        point to the starting txn of a txnlog that may contain txn of
         *        a given zxid
         * @throws IOException
         */
        public FileTxnIterator(File logDir, long zxid, boolean fastForward) throws IOException {
            this.logDir = logDir;
            this.zxid = zxid;
            init();

            if (fastForward && hdr != null) {
                while (hdr.getZxid() < zxid) {
                    if (!next()) {//找到zxid对应的log内容
                        break;
                    }
                }
            }
        }

        /**
         * create an iterator over a transaction database directory
         * @param logDir the transaction database directory
         * @param zxid the zxid to start reading from
         * @throws IOException
         */
        public FileTxnIterator(File logDir, long zxid) throws IOException {
            this(logDir, zxid, true);
        }

        /**
         * initialize to the zxid specified
         * this is inclusive of the zxid
         * @throws IOException
         */
        void init() throws IOException {
            //要遍历的文件
            storedFiles = new ArrayList<>();
            //降序排练
            List<File> files = Util.sortDataDir(
                FileTxnLog.getLogFiles(logDir.listFiles(), 0),
                LOG_FILE_PREFIX,
                false);
            //zxid对应的内容可能在小一号的log中，因此这里拿到大于zxid的log和小于zxid的第一个log
            for (File f : files) {
                if (Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX) >= zxid) {
                    storedFiles.add(f);
                } else if (Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX) < zxid) {
                    // add the last logfile that is less than the zxid
                    storedFiles.add(f);
                    break;
                }
            }
            goToNextLog();
            next();
        }

        /**
         * Return total storage size of txnlog that will return by this iterator.
         */
        public long getStorageSize() {
            long sum = 0;
            for (File f : storedFiles) {
                sum += f.length();
            }
            return sum;
        }

        /**
         * go to the next logfile
         * @return true if there is one and false if there is no
         * new file to be read
         * @throws IOException
         */
        private boolean goToNextLog() throws IOException {
            if (storedFiles.size() > 0) {
                this.logFile = storedFiles.remove(storedFiles.size() - 1);
                ia = createInputArchive(this.logFile);
                return true;
            }
            return false;
        }

        /**
         * read the header from the inputarchive
         * @param ia the inputarchive to be read from
         * @param is the inputstream
         * @throws IOException
         */
        protected void inStreamCreated(InputArchive ia, InputStream is) throws IOException {
            FileHeader header = new FileHeader();
            header.deserialize(ia, "fileheader");
            if (header.getMagic() != FileTxnLog.TXNLOG_MAGIC) {
                throw new IOException("Transaction log: " + this.logFile
                                      + " has invalid magic number "
                                      + header.getMagic() + " != " + FileTxnLog.TXNLOG_MAGIC);
            }
        }

        /**
         * Invoked to indicate that the input stream has been created.
         * @param logFile the file to read.
         * @throws IOException
         **/
        protected InputArchive createInputArchive(File logFile) throws IOException {
            if (inputStream == null) {
                inputStream = new PositionInputStream(new BufferedInputStream(new FileInputStream(logFile)));
                LOG.debug("Created new input stream: {}", logFile);
                ia = BinaryInputArchive.getArchive(inputStream);
                inStreamCreated(ia, inputStream);
                LOG.debug("Created new input archive: {}", logFile);
            }
            return ia;
        }

        /**
         * create a checksum algorithm
         * @return the checksum algorithm
         */
        protected Checksum makeChecksumAlgorithm() {
            return new Adler32();
        }

        /**
         * the iterator that moves to the next transaction
         * @return true if there is more transactions to be read
         * false if not.
         */
        public boolean next() throws IOException {
            if (ia == null) {
                return false;
            }
            try {
                long crcValue = ia.readLong("crcvalue");
                byte[] bytes = Util.readTxnBytes(ia);
                // Since we preallocate, we define EOF to be an
                if (bytes == null || bytes.length == 0) {
                    throw new EOFException("Failed to read " + logFile);
                }
                // EOF or corrupted record
                // validate CRC
                Checksum crc = makeChecksumAlgorithm();
                crc.update(bytes, 0, bytes.length);
                if (crcValue != crc.getValue()) {
                    throw new IOException(CRC_ERROR);
                }
                TxnLogEntry logEntry = SerializeUtils.deserializeTxn(bytes);
                hdr = logEntry.getHeader();
                record = logEntry.getTxn();
                digest = logEntry.getDigest();
            } catch (EOFException e) {
                LOG.debug("EOF exception", e);
                inputStream.close();
                inputStream = null;
                ia = null;
                hdr = null;
                // this means that the file has ended
                // we should go to the next file
                if (!goToNextLog()) {
                    return false;
                }
                // if we went to the next log file, we should call next() again
                return next();
            } catch (IOException e) {
                inputStream.close();
                throw e;
            }
            return true;
        }

        /**
         * return the current header
         * @return the current header that
         * is read
         */
        public TxnHeader getHeader() {
            return hdr;
        }

        /**
         * return the current transaction
         * @return the current transaction
         * that is read
         */
        public Record getTxn() {
            return record;
        }

        public TxnDigest getDigest() {
            return digest;
        }

        /**
         * close the iterator
         * and release the resources.
         */
        public void close() throws IOException {
            if (inputStream != null) {
                inputStream.close();
            }
        }

    }

}
