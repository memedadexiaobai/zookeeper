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

import java.util.Random;
import org.apache.zookeeper.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements a token-bucket based rate limiting mechanism with optional
 * probabilistic dropping inspired by the BLUE queue management algorithm [1].
 *
 * The throttle provides the {@link #checkLimit(int)} method which provides
 * a binary yes/no decision.
 *
 * The core token bucket algorithm starts with an initial set of tokens based
 * on the <code>maxTokens</code> setting. Tokens are dispensed each
 * {@link #checkLimit(int)} call, which fails if there are not enough tokens to
 * satisfy a given request.
 *
 * The token bucket refills over time, providing <code>fillCount</code> tokens
 * every <code>fillTime</code> milliseconds, capping at <code>maxTokens</code>.
 *
 * This design allows the throttle to allow short bursts to pass, while still
 * capping the total number of requests per time interval.
 *
 * One issue with a pure token bucket approach for something like request or
 * connection throttling is that the wall clock arrival time of requests affects
 * the probability of a request being allowed to pass or not. Under constant
 * load this can lead to request starvation for requests that constantly arrive
 * later than the majority.
 *
 * In an attempt to combat this, this throttle can also provide probabilistic
 * dropping. This is enabled anytime <code>freezeTime</code> is set to a value
 * other than <code>-1</code>.
 *
 * The probabilistic algorithm starts with an initial drop probability of 0, and
 * adjusts this probability roughly every <code>freezeTime</code> milliseconds.
 * The first request after <code>freezeTime</code>, the algorithm checks the
 * token bucket. If the token bucket is empty, the drop probability is increased
 * by <code>dropIncrease</code> up to a maximum of <code>1</code>. Otherwise, if
 * the bucket has a token deficit less than <code>decreasePoint * maxTokens</code>,
 * the probability is decreased by <code>dropDecrease</code>.
 *
 * Given a call to {@link #checkLimit(int)}, requests are first dropped randomly
 * based on the current drop probability, and only surviving requests are then
 * checked against the token bucket.
 *
 * When under constant load, the probabilistic algorithm will adapt to a drop
 * frequency that should keep requests within the token limit. When load drops,
 * the drop probability will decrease, eventually returning to zero if possible.
 *
 * [1] "BLUE: A New Class of Active Queue Management Algorithms"
 **/

public class BlueThrottle {
    private static final Logger LOG = LoggerFactory.getLogger(BlueThrottle.class);

    private int maxTokens;
    private int fillTime;
    private int fillCount;
    private int tokens;
    private long lastTime;

    private int freezeTime;
    private long lastFreeze;
    private double dropIncrease;
    private double dropDecrease;
    private double decreasePoint;
    private double drop;

    Random rng;

    public static final String CONNECTION_THROTTLE_TOKENS = "zookeeper.connection_throttle_tokens";
    private static final int  DEFAULT_CONNECTION_THROTTLE_TOKENS;

    public static final String CONNECTION_THROTTLE_FILL_TIME = "zookeeper.connection_throttle_fill_time";
    private static final int DEFAULT_CONNECTION_THROTTLE_FILL_TIME;

    public static final String CONNECTION_THROTTLE_FILL_COUNT = "zookeeper.connection_throttle_fill_count";
    private static final int DEFAULT_CONNECTION_THROTTLE_FILL_COUNT;

    public static final String CONNECTION_THROTTLE_FREEZE_TIME = "zookeeper.connection_throttle_freeze_time";
    private static final int DEFAULT_CONNECTION_THROTTLE_FREEZE_TIME;

    public static final String CONNECTION_THROTTLE_DROP_INCREASE = "zookeeper.connection_throttle_drop_increase";
    private static final double DEFAULT_CONNECTION_THROTTLE_DROP_INCREASE;

    public static final String CONNECTION_THROTTLE_DROP_DECREASE = "zookeeper.connection_throttle_drop_decrease";
    private static final double DEFAULT_CONNECTION_THROTTLE_DROP_DECREASE;

    public static final String CONNECTION_THROTTLE_DECREASE_RATIO = "zookeeper.connection_throttle_decrease_ratio";
    private static final double DEFAULT_CONNECTION_THROTTLE_DECREASE_RATIO;

    public static final String WEIGHED_CONNECTION_THROTTLE = "zookeeper.connection_throttle_weight_enabled";
    private static boolean connectionWeightEnabled;

    public static final String GLOBAL_SESSION_WEIGHT = "zookeeper.connection_throttle_global_session_weight";
    private static final int DEFAULT_GLOBAL_SESSION_WEIGHT;

    public static final String LOCAL_SESSION_WEIGHT = "zookeeper.connection_throttle_local_session_weight";
    private static final int DEFAULT_LOCAL_SESSION_WEIGHT;

    public static final String RENEW_SESSION_WEIGHT = "zookeeper.connection_throttle_renew_session_weight";
    private static final int DEFAULT_RENEW_SESSION_WEIGHT;

    // for unit tests only
    protected  static void setConnectionWeightEnabled(boolean enabled) {
        connectionWeightEnabled = enabled;
        logWeighedThrottlingSetting();
    }

    private static void logWeighedThrottlingSetting() {
        if (connectionWeightEnabled) {
            LOG.info("Weighed connection throttling is enabled. "
                    + "But it will only be effective if connection throttling is enabled");
            LOG.info(
                    "The weights for different session types are: global {} renew {} local {}",
                    DEFAULT_GLOBAL_SESSION_WEIGHT,
                    DEFAULT_RENEW_SESSION_WEIGHT,
                    DEFAULT_LOCAL_SESSION_WEIGHT
            );
        } else {
            LOG.info("Weighed connection throttling is disabled");
        }
    }

    static {
        int tokens = Integer.getInteger(CONNECTION_THROTTLE_TOKENS, 0);
        int fillCount = Integer.getInteger(CONNECTION_THROTTLE_FILL_COUNT, 1);

        connectionWeightEnabled = Boolean.getBoolean(WEIGHED_CONNECTION_THROTTLE);

        // if not specified, the weights for a global session, a local session, and a renew session
        // are 3, 1, 2 respectively. The weight for a global session is 3 because in our connection benchmarking,
        // the throughput of global sessions is about one third of that of local sessions. Renewing a session
        // requires is more expensive than establishing a local session and cheaper than creating a global session so
        // its default weight is set to 2.
        int globalWeight = Integer.getInteger(GLOBAL_SESSION_WEIGHT, 3);
        int localWeight = Integer.getInteger(LOCAL_SESSION_WEIGHT, 1);
        int renewWeight = Integer.getInteger(RENEW_SESSION_WEIGHT, 2);

        //DEFAULT_GLOBAL_SESSION_WEIGHT 必须大于等于 localWeight 小于等于0的情况下为3
        if (globalWeight <= 0) {
            LOG.warn("Invalid global session weight {}. It should be larger than 0", globalWeight);
            DEFAULT_GLOBAL_SESSION_WEIGHT = 3;
        } else if (globalWeight < localWeight) {
            LOG.warn(
                "The global session weight {} is less than the local session weight {}. Use the local session weight.",
                globalWeight,
                localWeight);
            DEFAULT_GLOBAL_SESSION_WEIGHT = localWeight;
        } else {
            DEFAULT_GLOBAL_SESSION_WEIGHT = globalWeight;
        }

        //DEFAULT_LOCAL_SESSION_WEIGHT 最小是1
        if (localWeight <= 0) {
            LOG.warn("Invalid local session weight {}. It should be larger than 0", localWeight);
            DEFAULT_LOCAL_SESSION_WEIGHT = 1;
        } else {
            DEFAULT_LOCAL_SESSION_WEIGHT = localWeight;
        }

        // DEFAULT_RENEW_SESSION_WEIGHT 必须大于等于 localWeight 小于等于0的情况下 是2
        if (renewWeight <= 0) {
            LOG.warn("Invalid renew session weight {}. It should be larger than 0", renewWeight);
            DEFAULT_RENEW_SESSION_WEIGHT = 2;
        } else if (renewWeight < localWeight) {
            LOG.warn(
                "The renew session weight {} is less than the local session weight {}. Use the local session weight.",
                renewWeight,
                localWeight);
            DEFAULT_RENEW_SESSION_WEIGHT = localWeight;
        } else {
            DEFAULT_RENEW_SESSION_WEIGHT = renewWeight;
        }

        // This is based on the assumption that tokens set in config are for global sessions
        DEFAULT_CONNECTION_THROTTLE_TOKENS = connectionWeightEnabled
                ? DEFAULT_GLOBAL_SESSION_WEIGHT * tokens : tokens;
        DEFAULT_CONNECTION_THROTTLE_FILL_COUNT = connectionWeightEnabled
                ? DEFAULT_GLOBAL_SESSION_WEIGHT * fillCount : fillCount;

        DEFAULT_CONNECTION_THROTTLE_FILL_TIME = Integer.getInteger(CONNECTION_THROTTLE_FILL_TIME, 1);
        DEFAULT_CONNECTION_THROTTLE_FREEZE_TIME = Integer.getInteger(CONNECTION_THROTTLE_FREEZE_TIME, -1);

        DEFAULT_CONNECTION_THROTTLE_DROP_INCREASE = getDoubleProp(CONNECTION_THROTTLE_DROP_INCREASE, 0.02);
        DEFAULT_CONNECTION_THROTTLE_DROP_DECREASE = getDoubleProp(CONNECTION_THROTTLE_DROP_DECREASE, 0.002);
        DEFAULT_CONNECTION_THROTTLE_DECREASE_RATIO = getDoubleProp(CONNECTION_THROTTLE_DECREASE_RATIO, 0);

        logWeighedThrottlingSetting();
    }

    /* Varation of Integer.getInteger for real number properties */
    private static double getDoubleProp(String name, double def) {
        String val = System.getProperty(name);
        if (val != null) {
            return Double.parseDouble(val);
        } else {
            return def;
        }
    }

    public BlueThrottle() {
        // Disable throttling by default (maxTokens = 0)
        this.maxTokens = DEFAULT_CONNECTION_THROTTLE_TOKENS;
        this.fillTime = DEFAULT_CONNECTION_THROTTLE_FILL_TIME;
        this.fillCount = DEFAULT_CONNECTION_THROTTLE_FILL_COUNT;
        this.tokens = maxTokens;
        this.lastTime = Time.currentElapsedTime();

        // Disable BLUE throttling by default (freezeTime = -1)
        this.freezeTime = DEFAULT_CONNECTION_THROTTLE_FREEZE_TIME;
        this.lastFreeze = Time.currentElapsedTime();
        this.dropIncrease = DEFAULT_CONNECTION_THROTTLE_DROP_INCREASE;
        this.dropDecrease = DEFAULT_CONNECTION_THROTTLE_DROP_DECREASE;
        this.decreasePoint = DEFAULT_CONNECTION_THROTTLE_DECREASE_RATIO;
        this.drop = 0;

        this.rng = new Random();
    }

    public synchronized void setMaxTokens(int max) {
        int deficit = maxTokens - tokens;
        maxTokens = max;
        tokens = max - deficit;
    }

    public synchronized void setFillTime(int time) {
        fillTime = time;
    }

    public synchronized void setFillCount(int count) {
        fillCount = count;
    }

    public synchronized void setFreezeTime(int time) {
        freezeTime = time;
    }

    public synchronized void setDropIncrease(double increase) {
        dropIncrease = increase;
    }

    public synchronized void setDropDecrease(double decrease) {
        dropDecrease = decrease;
    }

    public synchronized void setDecreasePoint(double ratio) {
        decreasePoint = ratio;
    }

    public synchronized int getMaxTokens() {
        return maxTokens;
    }

    public synchronized int getFillTime() {
        return fillTime;
    }

    public synchronized int getFillCount() {
        return fillCount;
    }

    public synchronized int getFreezeTime() {
        return freezeTime;
    }

    public synchronized double getDropIncrease() {
        return dropIncrease;
    }

    public synchronized double getDropDecrease() {
        return dropDecrease;
    }

    public synchronized double getDecreasePoint() {
        return decreasePoint;
    }

    public synchronized double getDropChance() {
        return drop;
    }

    public synchronized int getDeficit() {
        return maxTokens - tokens;
    }

    public int getRequiredTokensForGlobal() {
        return BlueThrottle.DEFAULT_GLOBAL_SESSION_WEIGHT;
    }

    public int getRequiredTokensForLocal() {
        return BlueThrottle.DEFAULT_LOCAL_SESSION_WEIGHT;
    }

    public int getRequiredTokensForRenew() {
        return BlueThrottle.DEFAULT_RENEW_SESSION_WEIGHT;
    }

    public boolean isConnectionWeightEnabled() {
        return BlueThrottle.connectionWeightEnabled;
    }

    /**
     *
     * checkBlue(now) → 蓝色算法（自适应概率丢包）
     *  软限流
     *  负载高了 → 按概率拒绝连接
     *  负载下降 → 自动恢复
     *  作用：平滑削峰、防止雪崩、让流量慢慢降
     * tokens < need → 令牌桶（Token Bucket）
     *  硬限流
     *  超过令牌数 → 直接拒绝
     *  作用：绝对保护，绝不允许超过最大处理能力
     *
     * 优势：
     *  优势 1：软限流 + 硬限流 = 绝对安全 + 体验平滑
     *  单独用令牌桶（硬限流）缺点：
     *      流量一超，瞬间大量拒绝
     *      客户端突刺流量会剧烈抖动
     *      体验差，容易引起大面积报错
     *  单独用蓝色算法（概率丢包）缺点：
     *      是柔性调节，无法 100% 挡住超大规模流量
     *      极端情况下还是可能压垮服务
     *  结合后：
     *      压力不大时：蓝色算法温柔调节
     *      压力上来时：概率丢包平滑拒绝
     *      压力爆表时：令牌桶直接硬挡
     *  → 既平滑又绝对安全！
     * 优势 2：应对雪崩重连（大规模客户端重连）
     *  这是 ZK 最关键的场景：服务重启 → 10 万客户端瞬间重连！
     *  结合后的效果：
     *      蓝色算法先挡一波（概率丢包，慢慢放连接）
     *      令牌桶兜底（绝不超过最大承载）
     *      服务不会被瞬间冲垮
     *      客户端不会大面积报错
     *      流量慢慢恢复，平滑无抖动
     * 优势 3：自适应 + 精准控制
     *  蓝色算法：根据系统延迟、负载、队列积压动态调整拒绝概率
     *  令牌桶：控制最大并发连接数
     * 组合 =
     *  智能自适应 + 绝对上限控制 = 企业级最强限流！
     * 优势 4：防止流量突刺（Burst traffic）
     *  令牌桶能应对瞬间小突刺
     *  蓝色算法能应对持续高压
     *  两者结合，任何流量都能稳稳接住
     *
     * 组合优势：
     *  平滑限流，不抖动
     *  自适应负载，智能拒绝
     *  硬限流兜底，绝对安全
     *  防雪崩、防重连风暴、防流量突刺
     *  ZK 能支撑 10w+ 客户端的核心保障
     *
     */
    public synchronized boolean checkLimit(int need) {
        // A maxTokens setting of zero disables throttling
        if (maxTokens == 0) { // 0 代表不限流
            return true;
        }

        long now = Time.currentElapsedTime();
        long diff = now - lastTime;

        // 令牌桶算法
        if (diff > fillTime) { // 默认每1毫秒填充一次
            /**
             * 假设：
             *  diff = 1000 ms（允许最大延迟 1 秒）
             *  fillCount = 10 个
             *  fillTime = 1000 ms（1 秒放 10 个连接）
             *
             * fillCount / fillTime = 每毫秒能生成多少个令牌 = 生成速率
             * diff * (fillCount / fillTime) = 在 diff 毫秒内，一共能生成多少个令牌= 当前最大允许的 “正在排队” 连接数= 限流阈值
             */
            int refill = (int) (diff * fillCount / fillTime);
            // 现在的token数
            tokens = Math.min(tokens + refill, maxTokens);
            lastTime = now;
        }

        // A freeze time of -1 disables BLUE randomized throttling
        if (freezeTime != -1) { // 默认-1 关闭
            if (!checkBlue(now)) {
                return false;  // 1. 先过蓝色概率限流（软）
            }
        }

        // 传统令牌桶算法
        if (tokens < need) {
            return false; // 2. 再过令牌桶硬限流（硬）
        }

        tokens -= need;
        return true;
    }

    /**
     * 根据系统负载，计算一个概率，决定【是否拒绝新客户端连接】
     *  负载越高 → 拒绝概率越大
     *  负载下降 → 拒绝概率慢慢降到 0
     *  完全不忙 → 不拒绝
     */
    public synchronized boolean checkBlue(long now) {
        int length = maxTokens - tokens;  // 令牌桶剩余空间
        int limit = maxTokens; // 最大令牌数
        long diff = now - lastFreeze;  // 距离上一次调整概率过了多久
        long threshold = Math.round(maxTokens * decreasePoint); // decreasePoint 默认是0 动态的阈值，decreasePoint控制调整的幅度

        // 在服务忙碌的时候，为保证服务正常，快速提高拒绝频率
        // 等服务差不多时候，缓慢降低拒绝频率 提高的速度默认是降低的10倍
        if (diff > freezeTime) { // 防止抖动，避免频繁加减概率
            if ((length == limit) && (drop < 1)) { // 太忙了 → 提高拒绝概率 length == limit说明token消耗完了，持续增加拒绝频率，默认+0.02
                drop = Math.min(drop + dropIncrease, 1); // 默认0.02 最大是1
            } else if ((length <= threshold) && (drop > 0)) { // 不忙了 → 降低拒绝概率 当小于阈值时threshold，说明服务不忙了，缓慢降低拒绝频率 默认-0.002
                drop = Math.max(drop - dropDecrease, 0); // 默认0.002 最小是0
            }
            lastFreeze = now;
        }

        // drop 概率值会动态调整，默认是0，区间[0-1] rng.nextDouble()生成0-1之间的随机数
        return !(rng.nextDouble() < drop);
    }

}
