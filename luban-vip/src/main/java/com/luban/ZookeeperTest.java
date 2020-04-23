package com.luban;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;

public class ZookeeperTest {

    public static void main(String[] args) throws IOException, KeeperException, InterruptedException {
        // 当前zookeeper客户端的defaultWatch
        ZooKeeper zooKeeper = new ZooKeeper("127.0.0.1:2181", 1000 * 1000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                System.out.println("zookeeer");
            }
        });

        zooKeeper.getData("/luban", new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                System.out.println(event.getType());
            }
        }, new Stat());

        zooKeeper.getData("/xx", new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                System.out.println(event.getType());
            }
        }, new Stat());


//        zooKeeper.exists("/luban", new Watcher() {
//            @Override
//            public void process(WatchedEvent event) {
//                System.out.println(event);
//            }
//        });







//        zooKeeper.addWatch("/luban/a/b/c", new Watcher() {
//            @Override
//            public void process(WatchedEvent event) {
//                System.out.println("getData");
//            }
//        }, AddWatchMode.PERSISTENT_RECURSIVE);

        System.in.read();


        // CreateMode有7种
        // acl不能为空，
        zooKeeper.create("/luban123", "123".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        //
        zooKeeper.addWatch("/luban123", AddWatchMode.PERSISTENT);
    }
}
