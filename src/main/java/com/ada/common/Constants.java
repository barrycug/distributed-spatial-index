package com.ada.common;

import com.ada.geometry.*;
import com.ada.globalTree.GTree;

import java.io.*;
import java.text.DecimalFormat;
import java.util.*;

import static org.apache.flink.runtime.state.KeyGroupRangeAssignment.assignKeyToParallelOperator;

public class Constants implements Serializable {

    /**
     * 定义Double类型的零
     */
    public final static double zero = 0.00001;

    public final static DecimalFormat df = new DecimalFormat("#.00000");

    /**
     * 本系统要求轨迹段断电在坐标轴上的映射的最大离
     */
    public final static double maxSegment = 400.0;

    /**
     * 全局索引的并行度
     */
    public static int globalPartition;

    /**
     * 本地索引的并行度
     */
    public static int dividePartition;

    /**
     * kafka的topic的并行度
     */
    public static int topicPartition;

    /**
     * subTask: globalSubTask
     * value: key
     */
    public static Map<Integer,Integer> globalSubTaskKeyMap = new HashMap<>();

    /**
     * subTask: divideSubTask
     * value: key
     */
    public static Map<Integer,Integer> divideSubTaskKeyMap = new HashMap<>();

    /**
     * 全局索引做动态负载均衡的频度
     */
    public static int balanceFre;

    private final static String confFileName = "conf.properties";

    /**
     * 网格密度
     */
    public final static int gridDensity = 511;

    public static long windowSize;

    public static int logicWindow;

    public static double radius; //查询矩形的大小

    public static int ratio = 2; //查询和更新的比例

    public final static Rectangle globalRegion = new Rectangle(new Point(0.0,0.0), new Point(8626.0,8872.0));

    static {
        try {
            Properties pro = new Properties();
            FileInputStream in = new FileInputStream(confFileName);
            pro.load(in);
            in.close();
            topicPartition = Integer.parseInt(pro.getProperty("topicPartition"));
            globalPartition = Integer.parseInt(pro.getProperty("globalPartition"));
            dividePartition = Integer.parseInt(pro.getProperty("dividePartition"));
            GTree.globalLowBound = Integer.parseInt(pro.getProperty("globalLowBound"));
            windowSize = Integer.parseInt(pro.getProperty("windowSize"));
            logicWindow = Integer.parseInt(pro.getProperty("logicWindow"));
        }catch (Exception e){
            e.printStackTrace();
        }
        balanceFre = logicWindow/5;

        int maxParallelism = 128;
//        int maxParallelism = 256;
        Set<Integer> usedSubtask = new HashSet<>();
        for (int i = 0; i < 1000000; i++) {
            Integer subTask = assignKeyToParallelOperator(i, maxParallelism, globalPartition);
            if (!usedSubtask.contains(subTask)) {
                usedSubtask.add(subTask);
                globalSubTaskKeyMap.put(subTask, i);
                if (usedSubtask.size() == globalPartition)
                    break;
            }
        }
        usedSubtask.clear();

//        maxParallelism = 128;
        maxParallelism = 256;
        for (int i = 0; i < 1000000; i++) {
            Integer subTask = assignKeyToParallelOperator(i, maxParallelism, dividePartition);
            if (!usedSubtask.contains(subTask)) {
                usedSubtask.add(subTask);
                divideSubTaskKeyMap.put(subTask, i);
                if (usedSubtask.size() == dividePartition)
                    break;
            }
        }
    }

    public static String appendSegment(Segment queryElem) {
        DecimalFormat df = new DecimalFormat("#.0000");
        return queryElem.getTID() + " " +
                queryElem.p2.timestamp + " " +
                df.format(queryElem.rect.low.data[0]) + " " +
                df.format(queryElem.rect.low.data[1]) + " " +
                df.format(queryElem.rect.high.data[0]) + " " +
                df.format(queryElem.rect.high.data[1]);
    }

    public static String appendTrackPoint(TrackPointElem elem) {
        DecimalFormat df = new DecimalFormat("#.0000");
        return elem.getTID() + " " +
                elem.timestamp + " " +
                df.format(elem.data[0]) + " " +
                df.format(elem.data[1]);
    }

    public static boolean isEqual(double a, double b){
        return Math.abs(a - b) < zero;
    }



    /**
     * 两个同型矩阵a,b。将b中的每个元素加到（isAdd是true）或者去减（isAdd是false）a中的对应元素上。
     */
    public static void addArrsToArrs (int[][] a, int[][] b, boolean isAdd){
        if (isAdd){
            for (int i = 0; i < a.length; i++) {
                for (int j = 0; j < a[i].length; j++)
                    a[i][j] += b[i][j];
            }
        }else {
            for (int i = 0; i < a.length; i++) {
                for (int j = 0; j < a[i].length; j++)
                    a[i][j] -= b[i][j];
            }
        }

    }

    public static boolean arrsEqual (int[][] a, int[][] b){
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i].length != b[i].length) return false;
            for (int j = 0; j < a[i].length; j++) {
                if (a[i][j] != b[i][j]) return false;
            }
        }
        return true;
    }

    /**
     * 对象转数组
     */
    public static byte[] toByteArray (Object obj) {
        byte[] bytes = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(obj);
            oos.flush();
            bytes = bos.toByteArray ();
            oos.close();
            bos.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return bytes;
    }

    /**
     * 数组转对象
     */
    public static Object toObject (byte[] bytes) {
        Object obj = null;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream (bytes);
            ObjectInputStream ois = new ObjectInputStream (bis);
            obj = ois.readObject();
            ois.close();
            bis.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return obj;
    }


}
