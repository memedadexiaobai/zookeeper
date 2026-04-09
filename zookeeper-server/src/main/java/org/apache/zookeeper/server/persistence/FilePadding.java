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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilePadding {

    private static final Logger LOG;
    private static long preAllocSize = 65536 * 1024;
    /**
     * 类型：直接字节缓冲区（Direct ByteBuffer）
     * 大小：仅 1 字节
     * 优势：直接缓冲区在 native 内存中分配，适合 I/O 操作，避免 JVM 堆内存拷贝
     */
    private static final ByteBuffer fill = ByteBuffer.allocateDirect(1);

    static {
        LOG = LoggerFactory.getLogger(FileTxnLog.class);

        String size = System.getProperty("zookeeper.preAllocSize");
        if (size != null) {
            try {
                preAllocSize = Long.parseLong(size) * 1024;
            } catch (NumberFormatException e) {
                LOG.warn("{} is not a valid value for preAllocSize", size);
            }
        }
    }

    private long currentSize;

    /**
     * Getter of preAllocSize has been added for testing
     */
    public static long getPreAllocSize() {
        return preAllocSize;
    }

    /**
     * method to allow setting preallocate size
     * of log file to pad the file.
     *
     * @param size the size to set to in bytes
     */
    public static void setPreallocSize(long size) {
        preAllocSize = size;
    }

    public void setCurrentSize(long currentSize) {
        this.currentSize = currentSize;
    }

    /**
     * pad the current file to increase its size to the next multiple of preAllocSize greater than the current size and position
     *
     * @param fileChannel the fileChannel of the file to be padded
     * @throws IOException
     */
    long padFile(FileChannel fileChannel) throws IOException {
        long newFileSize = calculateFileSizeWithPadding(fileChannel.position(), currentSize, preAllocSize);
        if (currentSize != newFileSize) {//说明扩容了
            /**
             * (ByteBuffer) fill.position(0) 这是一个类型转换 + 重置位置的组合操作：
             * // fill 是 ByteBuffer 类型
             * // fill.position() 返回当前读取位置（int 类型）
             * // fill.position(0) 将位置重置为 0，返回 ByteBuffer 本身
             *
             * // 为什么要强制类型转换？
             * // 因为 fill.position(0) 返回 Buffer 类型（Java NIO 的父类）
             * // 而 FileChannel.write() 需要 ByteBuffer 参数
             * // 所以需要 (ByteBuffer) 强制转换
             * 执行流程：
             *  fill.position(0) - 将缓冲区位置重置为 0
             *  (ByteBuffer) - 将返回的 Buffer 转换为 ByteBuffer
             *  最终传入一个位置为 0、容量为 1 字节的 ByteBuffer
             *
             *  newFileSize - fill.remaining()
             *   // fill.remaining() 返回什么？
             *  // remaining() = limit - position
             *  // 由于 position 刚被设为 0，limit = capacity = 1
             *  // 所以 fill.remaining() = 1 - 0 = 1
             *
             *  // 因此表达式变为：
             *  fileChannel.write(fill, newFileSize - 1);
             *  第二个参数的含义：
             *   这是 FileChannel.write(ByteBuffer src, long position) 方法
             *   第二个参数指定写入的起始位置（文件中的绝对位置）
             *   newFileSize - 1 表示从新文件大小的最后一个字节位置开始写入
             *
             *  为什么这样能预分配空间？
             *  // 示例：假设 newFileSize = 64MB (67108864 字节)
             * fileChannel.write(fill, 67108863);  // 在第 67108863 个字节位置写入 1 字节
             *
             * // FileChannel 的特性：
             * // 当你在文件的某个位置写入数据时，如果该位置超出当前文件大小，
             * // 文件系统会自动扩展文件到所需大小
             * // 中间的空洞（hole）会被填充为 0（稀疏文件特性）
             *
             * 实际效果：
             * 只在文件的最后一个字节写入 1 字节数据
             * 操作系统自动将文件扩展到 newFileSize 大小
             * 中间的数据全部为 0（无需实际写入，利用稀疏文件机制）
             *
             * 为什么不从头到尾填充？
             * 对比两种方案：
             *
             * | ❌ 低效方案 | ✅ 当前方案 |
             * |------------|------------|
             * | 循环写入 64MB 数据 | 只写 1 字节 |
             * | 需要 64MB 次系统调用 | 只需 1 次系统调用 |
             * | 实际写入 64MB 磁盘空间 | 利用稀疏文件，几乎不占实际空间 |
             * | 性能极差 | 性能极高 |
             *
             * 初始状态：
             * 文件实际大小：32MB
             * 需要扩展到：64MB
             *
             * 执行 write(fill, 67108863):
             * ┌─────────────────────────────────┐
             * │  已写入数据 (32MB)               │
             * ├─────────────────────────────────┤
             * │  空洞 (32MB - 1 字节)             │ ← 操作系统层面的"空洞"，不占实际空间
             * ├─────────────────────────────────┤
             * │  最后 1 字节 (写入 0x00)          │ ← fileChannel.write 的位置
             * └─────────────────────────────────┘
             *                                     ↑
             *                               文件总大小：64MB
             *
             * 关键技术点总结
             *  稀疏文件（Sparse File）：利用操作系统的稀疏文件特性，空洞不占用实际磁盘空间
             *  直接缓冲区：使用 allocateDirect 提高 I/O 性能
             *  单次写入扩容：通过写入最后一个字节触发文件扩展，避免全量填充
             *  类型转换技巧：(ByteBuffer) 转换以匹配方法签名
             *  位置重置：每次使用前必须 position(0)，因为缓冲区可能被复用
             */
            fileChannel.write((ByteBuffer) fill.position(0), newFileSize - fill.remaining());
            currentSize = newFileSize;
        }
        return currentSize;
    }

    /**
     * Calculates a new file size with padding. We only return a new size if
     * the current file position is sufficiently close (less than 4K) to end of
     * file and preAllocSize is &gt; 0.
     *
     * @param position     the point in the file we have written to
     * @param fileSize     application keeps track of the current file size
     * @param preAllocSize how many bytes to pad
     * @return the new file size. It can be the same as fileSize if no
     * padding was done.
     */
    // VisibleForTesting
    public static long calculateFileSizeWithPadding(long position, long fileSize, long preAllocSize) {
        // If preAllocSize is positive and we are within 4KB of the known end of the file calculate a new file size
        if (preAllocSize > 0 && position + 4096 >= fileSize) {
            // If we have written more than we have previously preallocated we need to make sure the new
            // file size is larger than what we already have
            if (position > fileSize) {
                fileSize = position + preAllocSize;
                // 调整为预分配代大小的整数倍
                fileSize -= fileSize % preAllocSize;
            } else {
                fileSize += preAllocSize;//没到文件末尾直接扩容
            }
        }

        return fileSize;
    }

}
