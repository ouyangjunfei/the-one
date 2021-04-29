package routing;

import core.*;
import util.Tuple;

import java.util.*;

/**
 * 自定义路由算法
 *
 * @author Fly
 */
public class MyEpidemicRouter extends ActiveRouter {

    /**
     * 自定义命名空间前缀
     */
    public static final String CUSTOM_NAMESPACE = "Custom";

    public static final String HOST_CACHE_COUNTER = "hostCacheCounter";

    public static final String HOST_UPDATE_COUNTER = "hostUpdateCounter";

    public static final String HOST_CACHE_FACTOR = "hostCacheFactor";

    /**
     * 自定义最少使用规则
     */
    public static final int Q_MODE_LFU = 3;

    /**
     * 路由内置计数器阈值
     */
    private final int COUNTER_THRESHOLD;

    /**
     * LFU缓存条目清理阈值，缓存表条目的值与其比较
     */
    private final int UPDATE_THRESHOLD;

    /**
     * 冷启动因子，用于计算冷启动阈值
     */
    private final double CACHE_FACTOR;

    /**
     * 冷启动阈值，缓存表的条目与其比较
     */
    private final int COLD_START_THRESHOLD;

    /**
     * 缓存表，存储最近发送过信息的主机及其对应次数
     */
    private Map<DTNHost, Integer> hostCacheCounter;

    /**
     * 计数器，用于记录当前路由进行过的更新次数，初始为0
     */
    private int counter;

    /**
     * Constructor. Creates a new message router based on the settings in
     * the given Settings object.
     *
     * @param s The settings object
     */
    public MyEpidemicRouter(Settings s) {
        super(s);
        initCounter();

        Settings customSetting = new Settings(CUSTOM_NAMESPACE);
        COUNTER_THRESHOLD = customSetting.getInt(HOST_CACHE_COUNTER, 10);
        UPDATE_THRESHOLD = customSetting.getInt(HOST_UPDATE_COUNTER, 1);
        CACHE_FACTOR = customSetting.getDouble(HOST_CACHE_FACTOR, 0.1);
        COLD_START_THRESHOLD = (int) (CACHE_FACTOR * COUNTER_THRESHOLD);
    }

    /**
     * Copy constructor.
     *
     * @param r The router prototype where setting values are copied from
     */
    protected MyEpidemicRouter(MyEpidemicRouter r) {
        super(r);
        initCounter();
        this.COUNTER_THRESHOLD = r.COUNTER_THRESHOLD;
        this.UPDATE_THRESHOLD = r.UPDATE_THRESHOLD;
        this.CACHE_FACTOR = r.CACHE_FACTOR;
        this.COLD_START_THRESHOLD = r.COLD_START_THRESHOLD;
    }

    private void initCounter() {
        this.hostCacheCounter = new HashMap<>();
        this.counter = 0;
    }

    private void updateCounter(DTNHost host) {
        // 当前路由更新次数大于一定阈值时候，对缓存表进行一次清理，去除少于一定阈值使用次数的缓存项
        if (counter >= COUNTER_THRESHOLD) {
            Set<DTNHost> hostToBeRemovedSet = new HashSet<>();
            for (Map.Entry<DTNHost, Integer> hostEntry : hostCacheCounter.entrySet()) {
                if (hostEntry.getValue() <= UPDATE_THRESHOLD) {
                    hostToBeRemovedSet.add(hostEntry.getKey());
                }
            }
            for (DTNHost dtnHost : hostToBeRemovedSet) {
                hostCacheCounter.remove(dtnHost);
            }
            // 也可以不重置counter，不重置的结果是每次更新缓存之前都会清理
            counter = 0;
        }
        // 将目的主机加入最近缓存
        hostCacheCounter.merge(host, 1, Integer::sum);
        // 计数器自增
        counter++;
    }

    @Override
    public void update() {

        /*
         * 先执行应用的具体更新，再
         * 1. 处理已完成传输的数据包
         * 2. 中止那些断开链路上的数据包
         * 3. 必要时，删除那些最早接收到且不正在传输的消息
         * 4. 丢弃那些TTL到期的数据包（只在没有消息发送的情况）
         * 5. 更新能量模板
         */
        super.update();

        /*
         * 判断该节点能否进行传输消息，存在以下情况一种以上的，直接返回，不更新：
         * 1. 本节点正在传输，sendingConnections.size() > 0
         * 2. 没有邻居节点，即没有节点与之建立连接，connections.size() == 0
         * 3. 有邻居节点，但有链路正在传输(想想无线信道)，!con.isReadyForTransfer()
         * 4. 缓冲区没有消息，this.getNrofMessages() == 0
         */
        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        /*
         * 用于交换该节点与邻居节点间的消息
         * 这些消息的目的节点是该节点或者其邻居节点，是直接传输
         * 该节点可能会有多个邻居节点，但只有有一个消息能传输到目的节点
         * 遍历会在完成第一次成功传输后返回Tuple<Message, Connection> tuple
         * 之后此函数返回；如果遍历完了都没有传输成功，会返回null，那么进入后面的流程
         */
        Connection conn;
        if ((conn = exchangeDeliverableMessages()) != null) {
            DTNHost otherNode = conn.getOtherNode(getHost());
            updateCounter(otherNode);
            return;
        }

        tryCustomMessages();
    }

    private void tryCustomMessages() {
        // 缓存表规模小于阈值时，泛洪
        if (hostCacheCounter.size() <= COLD_START_THRESHOLD) {
            Connection connection = tryAllMessagesToAllConnections();
            if (connection != null) {
                updateCounter(connection.getOtherNode(getHost()));
            }
        } else {
            List<Connection> connectionList = new ArrayList<>();
            // 通过目前路由的所有连接筛选目的路由在缓存表内的连接
            for (Connection connection : getConnections()) {
                if (hostCacheCounter.containsKey(connection.getOtherNode(getHost()))) {
                    connectionList.add(connection);
                }
            }

            // 得到当前路由携带的消息
            List<Message> messages = new ArrayList<>(this.getMessageCollection());
            // 通过指定连接发送特定信息，成功一次则返回对应连接，发送前对消息进行排序
            Connection connection = tryMessagesToConnections(sortByQueueMode(messages), connectionList);
            if (connection != null) {
                updateCounter(connection.getOtherNode(getHost()));
            }
        }
    }


    @Override
    public MyEpidemicRouter replicate() {
        return new MyEpidemicRouter(this);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> List<T> sortByQueueMode(List<T> list) {
        if (getSendQueueMode() == Q_MODE_LFU) {
            list.sort((o1, o2) -> {
                Message m1, m2;
                if (o1 instanceof Tuple) {
                    m1 = ((Tuple<Message, Connection>) o1).getKey();
                    m2 = ((Tuple<Message, Connection>) o2).getKey();
                } else if (o1 instanceof Message) {
                    m1 = (Message) o1;
                    m2 = (Message) o2;
                } else {
                    throw new SimError("Invalid type of objects in the list");
                }
                // 关键代码，查找消息目的主机在缓存表中的次数并按照升序排序
                return hostCacheCounter.getOrDefault(m1.getTo(), 0) - hostCacheCounter.getOrDefault(m2.getTo(), 0);
            });
            return list;
        }
        return super.sortByQueueMode(list);
    }

    @Override
    protected boolean makeRoomForMessage(int size) {
        // 想获取的空间大于总buffer大小，不可能
        if (size > this.getBufferSize()) {
            return false;
        }

        long freeBuffer = this.getFreeBufferSize();
        // 删除buffer中的消息直到有足够的空间
        while (freeBuffer < size) {
            Message messageToDelete = null;
            Collection<Message> messages = this.getMessageCollection();

            for (Message m : messages) {
                // 跳过正在发送的消息
                if (isSending(m.getId())) {
                    continue;
                }

                if (messageToDelete == null) {
                    messageToDelete = m;
                } else if (hostCacheCounter.getOrDefault(m.getTo(), 0) > hostCacheCounter.getOrDefault(messageToDelete.getTo(), 0)) {
                    messageToDelete = m;
                }
            }

            // 没有消息
            if (messageToDelete == null) {
                return false;
            }

            // 从buffer中删除消息
            deleteMessage(messageToDelete.getId(), true);
            freeBuffer += messageToDelete.getSize();
        }

        return true;
    }
}
