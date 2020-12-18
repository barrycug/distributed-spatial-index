package com.ada.flinkFunction;

import com.ada.common.Arrays;
import com.ada.common.Constants;
import com.ada.common.collections.Collections;
import com.ada.geometry.Rectangle;
import com.ada.geometry.Segment;
import com.ada.globalTree.GDataNode;
import com.ada.globalTree.GNode;
import com.ada.globalTree.GTree;
import com.ada.model.Density;
import com.ada.model.DensityToGlobalElem;
import com.ada.model.GlobalToLocalElem;
import com.ada.model.LocalRegionAdjustInfo;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import redis.clients.jedis.Jedis;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class GlobalTreePF extends ProcessWindowFunction<DensityToGlobalElem, GlobalToLocalElem, Integer, TimeWindow> {
    private int subTask;
    private long winStart;
    private GTree globalTree;
    private Queue<int[][]> densityQueue;
    private Jedis jedis;

    public  GlobalTreePF(){ }

    @Override
    public void process(Integer key,
                        Context context,
                        Iterable<DensityToGlobalElem> elements,
                        Collector<GlobalToLocalElem> out) throws Exception {
        winStart = context.window().getStart();
        globalTree.subTask = subTask;
        //根据Global Index给输入项分区
        int[][] density = processElemAndDensity(elements, out);

        // 调整Global Index, 然后将调整结果同步到相关的Local Index中。
        if (density != null){
            if (subTask == 0 && winStart == 1477933920000L)
                System.out.println();
            densityQueue.add(density);
            Arrays.addArrsToArrs(globalTree.density, density, true);
            if (densityQueue.size() > Constants.logicWindow / Constants.balanceFre) {
                int[][] removed = densityQueue.remove();
                Arrays.addArrsToArrs(globalTree.density, removed, false);
            }

            if (subTask == 1)
                System.out.println(context.window().getStart() + " " + globalTree.getAllLeafs().size());

            //调整Global Index
            Map<GNode, GNode> nodeMap = globalTree.updateTree();
            globalTree.check();
            byte[] redisKey = (context.window().getStart() + "|" + "globalTree").getBytes(StandardCharsets.UTF_8);
            testEqualByRedis(redisKey, globalTree);

            //Global Index发生了调整，通知Local Index迁移数据，重建索引。
            if (!nodeMap.isEmpty() && subTask == 0)
                adjustLocalTasksRegion(nodeMap, out);
        }
    }

    private <T> void testEqualByRedis(byte[] redisKey, T t) {
        long size = jedis.llen(redisKey);
        if (size < 3) {
            jedis.rpush(redisKey, Arrays.toByteArray(t));
        }else if (size == 3){
            if (winStart == 1477963200000L)
                System.out.println();
            List<byte[]> list = jedis.lrange(redisKey,0, -1);
            jedis.ltrim(redisKey, 1,0);
            T t0 = (T) Arrays.toObject(list.get(0));
            T t1 = (T) Arrays.toObject(list.get(1));
            T t2 = (T) Arrays.toObject(list.get(2));
            if (!t.equals(t0) ||
                    !t.equals(t1) ||
                    !t.equals(t2))
                System.out.println();
        }else {
            throw new IllegalArgumentException("redisKey too large");
        }
    }

    private <T> void testCollectionEqualByRedis(byte[] redisKey, Collection<T> t) {
        long size = jedis.llen(redisKey);
        if (size < 3) {
            jedis.rpush(redisKey, Arrays.toByteArray(t));
        }else if (size == 3){
            List<byte[]> list = jedis.lrange(redisKey,0, -1);
            jedis.ltrim(redisKey, 1,0);
            Collection<T> t0 = (Collection<T>) Arrays.toObject(list.get(0));
            Collection<T> t1 = (Collection<T>) Arrays.toObject(list.get(1));
            Collection<T> t2 = (Collection<T>) Arrays.toObject(list.get(2));
            if (!Collections.collectionsEqual(t, t0) ||
                    !Collections.collectionsEqual(t, t1) ||
                    !Collections.collectionsEqual(t, t2))
                System.out.println();
        }else {
            throw new IllegalArgumentException("redisKey too large");
        }
    }

    private int[][] processElemAndDensity(Iterable<DensityToGlobalElem> elements,
                                          Collector<GlobalToLocalElem> out) throws Exception {
        int queryCount = 0;
        List<Density> densities = new ArrayList<>(Constants.globalPartition);
        for (DensityToGlobalElem element : elements) {
            if (element instanceof Segment){
                Segment segment = (Segment) element;
//                List<Integer> leafs = globalTree.searchLeafNodes(segment.rect);
//                for (Integer leaf : leafs){
//                    out.collect(new GlobalToLocalElem(leaf, 1, segment));
//                }
//                queryCount++;
//                if (queryCount%Constants.ratio == 0){
//                    Point point = segment.p1;
//                    Rectangle queryRect = new Rectangle(point.clone(), point.clone()).extendLength(Constants.radius);
//                    leafs = globalTree.searchLeafNodes(queryRect);
//                    for (Integer leafID : leafs) {
//                        out.collect(new GlobalToLocalElem(leafID, 2, queryRect));
//                    }
//                }
            } else {
                densities.add((Density) element);
            }
        }
        if (densities.size() != 0){
            byte[] redisKey = (winStart + "|" + "density").getBytes(StandardCharsets.UTF_8);
            testCollectionEqualByRedis(redisKey, densities);
        }
        int[][] result;
        if (densities.isEmpty()){
            result = null;
        }else {
            result = new int[Constants.gridDensity+1][Constants.gridDensity+1];
            for (Density density : densities) Arrays.addArrsToArrs(result, density.grids, true);
        }
        return result;
    }

    private void adjustLocalTasksRegion(Map<GNode, GNode> nodeMap,
                                        Collector<GlobalToLocalElem> out){
        Map<Integer, LocalRegionAdjustInfo> migrateOutMap = new HashMap<>();
        Map<Integer, LocalRegionAdjustInfo> migrateFromMap = new HashMap<>();
        for (Map.Entry<GNode, GNode> entry : nodeMap.entrySet()) {
            List<GDataNode> leafs = new ArrayList<>();
            entry.getKey().getLeafs(leafs);
            for (GDataNode oldLeaf : leafs) {
                List<GDataNode> migrateOutLeafs = new ArrayList<>();
                entry.getValue().getIntersectLeafNodes(oldLeaf.region, migrateOutLeafs);
                migrateOutLeafs.removeIf(leaf -> leaf.leafID == oldLeaf.leafID);
                List<Tuple2<Integer, Rectangle>> migrateOutLeafIDs = (List<Tuple2<Integer, Rectangle>>) Collections.changeCollectionElem(migrateOutLeafs, node -> new Tuple2<>(node.leafID,node.region));
                migrateOutMap.put(oldLeaf.leafID, new LocalRegionAdjustInfo(migrateOutLeafIDs, null, null));
            }
            leafs.clear();
            entry.getValue().getLeafs(leafs);
            for (GDataNode newLeaf : leafs) {
                List<GDataNode> migrateFromLeafs = new ArrayList<>();
                entry.getKey().getIntersectLeafNodes(newLeaf.region, migrateFromLeafs);
                List<Integer> migrateFromLeafIDs = (List<Integer>) Collections.changeCollectionElem(migrateFromLeafs, node -> node.leafID);
                migrateFromLeafIDs.remove(new Integer(newLeaf.leafID));
                migrateFromMap.put(newLeaf.leafID, new LocalRegionAdjustInfo(null, migrateFromLeafIDs, newLeaf.region));
            }
        }
        Iterator<Map.Entry<Integer, LocalRegionAdjustInfo>> ite = migrateOutMap.entrySet().iterator();
        while (ite.hasNext()){
            Map.Entry<Integer, LocalRegionAdjustInfo> entry = ite.next();
            LocalRegionAdjustInfo elem = migrateFromMap.get(entry.getKey());
            if (elem != null){
                elem.setMigrateOutST(entry.getValue().getMigrateOutST());
                ite.remove();
            }
        }
        migrateFromMap.putAll(migrateOutMap);
        for (Map.Entry<Integer, LocalRegionAdjustInfo> entry : migrateFromMap.entrySet())
            out.collect(new GlobalToLocalElem(entry.getKey(), 3, entry.getValue()));
    }

    private void openWindow() {
        try {
            subTask = getRuntimeContext().getIndexOfThisSubtask();
            densityQueue = new ArrayDeque<>();
            globalTree = new GTree();
        }catch (Exception e){
            e.printStackTrace();
        }
    }


    @Override
    public void open(Configuration parameters) {
        jedis = new Jedis("localhost");
        openWindow();
    }

}
