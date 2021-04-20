package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import util.Tuple;

import java.util.*;

/**
 * 自定义路由算法
 */
public class MyEpidemicRouter extends ActiveRouter {

    private static final int COUNTER_THRESHOLD = 10;

    private static final int UPDATE_THRESHOLD = 1;

    private static final float CACHE_FACTOR = 0.1f;

    private static final int SOFT_THRESHOLD = (int) (CACHE_FACTOR * COUNTER_THRESHOLD);

    /**
     * 缓存表，存储最近发送过信息的主机及其对应次数
     */
    private Map<DTNHost, Integer> hostCacheCounter;

    /**
     * 计数器，用于记录当前路由进行过的更新次数
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
    }

    /**
     * Copy constructor.
     *
     * @param r The router prototype where setting values are copied from
     */
    protected MyEpidemicRouter(MyEpidemicRouter r) {
        super(r);
        initCounter();
    }

    private void initCounter() {
        this.hostCacheCounter = new HashMap<>();
        this.counter = 0;
    }

    private void updateCounter(DTNHost host) {
        // 当前路由更新次数大于一定阈值时候，对缓存表进行一次清理，去除最少使用的
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

        Tuple<Message, Connection> message = tryCustomMessages();
    }

    private Tuple<Message, Connection> tryCustomMessages() {
        // 缓存表规模小于阈值时，泛洪
        if (hostCacheCounter.size() <= SOFT_THRESHOLD) {
            tryAllMessagesToAllConnections();
        }

        List<Connection> connectionList = new ArrayList<>();

        for (Connection connection : getConnections()) {
            if (hostCacheCounter.containsKey(connection.getOtherNode(getHost()))) {
                connectionList.add(connection);
            }
        }

        List<Message> messages = new ArrayList<>(this.getMessageCollection());
        Connection connection = tryMessagesToConnections(messages, connectionList);
        if (connection != null) {
            updateCounter(connection.getOtherNode(getHost()));
        }
        return null;
    }


    @Override
    public MyEpidemicRouter replicate() {
        return new MyEpidemicRouter(this);
    }

}
