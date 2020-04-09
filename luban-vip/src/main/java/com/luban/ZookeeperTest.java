package com.luban;

import org.apache.zookeeper.*;

import java.io.IOException;

public class ZookeeperTest {

    public static void main(String[] args) throws IOException, KeeperException, InterruptedException {
        ZooKeeper zooKeeper = new ZooKeeper("127.0.0.1:2181", 10 * 1000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {

            }
        });

        // CreateMode有7种
        zooKeeper.create("/luban", "123".getBytes(), null, CreateMode.PERSISTENT);

    }
}
