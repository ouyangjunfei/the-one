package routing.util;

import core.Settings;

public class Constants {

    /**
     * Events1命名空间
     */
    public static final String EVENTS1_NAMESPACE = "Events1" ;

    /**
     * Sender/receiver address range -setting id ({@value}).
     * The lower bound is inclusive and upper bound exclusive.
     */
    public static final String HOST_RANGE_S = "hosts" ;

    /**
     * 节点总数，目前没有使用到
     */
    public static int nrofTotalHosts;

    static {
        Settings eventSetting = new Settings(EVENTS1_NAMESPACE);
        int[] hostRange = eventSetting.getCsvInts(HOST_RANGE_S, 2);
        nrofTotalHosts = hostRange[1] - hostRange[0];
    }
}
