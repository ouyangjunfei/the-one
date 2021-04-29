# The ONE

The Opportunistic Network Environment simulator.

For introduction and releases, see [the ONE homepage at GitHub](http://akeranen.github.io/the-one/).

For instructions on how to get started, see [the README](https://github.com/akeranen/the-one/wiki/README).

The [wiki page](https://github.com/akeranen/the-one/wiki) has the latest information.



本文[代码仓库](https://github.com/ouyangjunfei/the-one)

[TOC]

## 1. 综述

### 1.1 文档参考

The ONE [官方文档](https://github.com/akeranen/the-one/wiki/README)

其没有作为项目的README

### 1.2 使用IDE进行二次开发

- JDK版本推荐`1.8`及以下

- 在`lib`中新增了`junit-4.3.1.jar`用以解决`src/test`下的报错，不添加jar包直接删去所有的测试文件也可以

- 导入项目至IDEA或者Eclipse，配置好JDK和第三方lib路径（编译器有可能会自动识别），即可开始二次开发
  - 我使用的是IDEA，后面以此环境做阐述，没有本质区别

- 建议将IDE的编译输出模块路径改为`target`与原项目一致，否则需要每次改动后手动运行`compile.bat`进行编译 ，再通过`one.bat`运行
  - 比如会出现找不到自定义Router类的报错
  - 修改后可以直接在IDE内Run或者Debug

## 2. 路由算法实现

### 2.1 学习已经实现的路由算法

参考资料[The ONE使用笔记：目录](http://sparkandshine.net/the-one-use-notes-directory/)

**路由协议**章节

- 所有的路由协议都是`ActiveRouter.java`的子类，而`ActiveRouter.java`又是`MessageRouter.java`的子类
- 建议先阅读`MessageRouter.java`，其包含路由定义的一些基本信息和处理过程，包括所属主机、缓存大小、TTL、发送队列策略，以及将要接收、承载、已经接收、黑名单信息
- 然后是`ActiveRouter.java`，其描述当前活跃的路由，包含一些便捷方法用于进行Host、Connection和Message的交互
- `DirectDeliveryRouter.java`是一种最基本的路由算法实现，只允许直接交付，不进行转发，可以参考[The ONE使用笔记：DirectDelivery路由](http://sparkandshine.net/en/the-one-use-notes-direct-delivery-router)
- `EpidemicRouter.java`则类似泛洪似的消息转发传递，这两种实现的代码都非常简单，可以阅读并理解其基本含义后在此基础上进行二次开发，可以参考[The ONE使用笔记：Epidemic路由](http://sparkandshine.net/en/the-one-use-notes-epidemic-router)

### 2.2 自定义算法实现

位于`src/routing/MyEpidemicRouter.java`

大致思想是 LFU(Least Frequently Used) + 冷启动

- **引入自定义参数**：定义了3项额外参数，命名空间为`Custom`

  ```yacas
  # 路由内置计数器阈值
  Custom.hostCacheCounter=[10;10;10;10;20;20;20;20]
  
  # LFU缓存条目清理阈值
  Custom.hostUpdateCounter=[1;1;2;2;1;1;2;2]
  
  # 冷启动因子
  Custom.hostCacheFactor=[0.1;0.2;0.1;0.2;0.1;0.2;0.1;0.2]
  ```

  - 还有一项冷启动阈值由内置计数器和冷启动因子计算得到

      ```java
        COLD_START_THRESHOLD = (int) (CACHE_FACTOR * COUNTER_THRESHOLD);
      ```

  - 设置多值并使用分号`;`分隔，就可以一次性全部跑完；`Scenario.name`需要修改为动态的，否则每次运行都会覆盖之前生成的报告，使用`%%字段名%%`添加动态字段
  - 要注意的是需要设置`-b n`，其中的`n`是批次数量

- **数据结构设计**

  - 为路由加入缓存表，其数据结构为`Map`，存储`<主机,访问次数>`键值对，用以记录当前路由对成功转发消息的目的主机频次

    ```java
    /**
    * 缓存表，存储最近发送过信息的主机及其对应次数
    */
    private Map<DTNHost, Integer> hostCacheCounter;
    ```

  - 其次是内置计数器，每次对缓存表更新过后自增，且在大于一定阈值时对缓存表进行一次**LFU**清理，**LFU**清理的参数也由外部配置文件决定

    ```java
    /**
    * 计数器，用于记录当前路由进行过的更新次数，初始为0
    */
    private int counter;
    
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
    ```

- **算法思想**

  1. 核心`update()`函数先进行父类的执行
  2. 判断当前节点能否传输消息
  3. 尝试无转发直接发送消息，任一成功则返回
  4. 否则进入自定义的消息发送机制：判断当前节点缓存表规模小于冷启动阈值时进行泛洪，大于阈值后只将消息发送给**当前连通链路**的且存在于**缓存表中**的目的主机，此时发送的消息为改节点缓存内的所有消息，会做一次随机排序，成功后更新缓存表

- **核心执行过程**：位于`update()`方法，类似于`EpidemicRouter.java`中实现方式，不同之处是在直接传递消息成功后会**更新缓存表**，如果没有直接传递消息则开始自定义的消息发送过程`tryCustomMessages()`

  ```java
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
  ```

- **自定义消息发送**：过程正如**算法思想**的第4点所述，这里不再赘述，代码直接查看`tryCustomMessages()`方法即可

### 2.3 测试结果

配置文件参考`settings.properties`，`The-ONE`的作用原理是默认加载`default_settings.txt`内的所有参数，如果指定了第三方配置文件，则会覆盖默认参数

所以本实验所有测试都是基于**默认配置文件**进行的，以`EpidemicRouter.java`测试结果作为**基准**进行比较。

自定义参数按照如上所设置即可一次跑完8个batch，得到如下8个报告，第一个报告为基准结果

```
default_scenario_MessageStatsReport.txt

my_scenario_10_1_0.1_MessageStatsReport.txt
my_scenario_10_1_0.2_MessageStatsReport.txt
my_scenario_10_2_0.1_MessageStatsReport.txt
my_scenario_10_2_0.2_MessageStatsReport.txt
my_scenario_20_1_0.1_MessageStatsReport.txt
my_scenario_20_1_0.2_MessageStatsReport.txt
my_scenario_20_2_0.1_MessageStatsReport.txt
my_scenario_20_2_0.2_MessageStatsReport.txt
```

**与基准结果纵向相比**：送达率上升八至九个百分点，网络负载数十倍大幅降低，延迟大概增加三分之一，平均跳数为其一半还少，消息的缓存时间增加五至六倍，有其特定使用场景，总体性能不错。显然，消息的缓存时间增加是因为缓存表收敛后的保守发送模式，这种模式同时也保证了网络负载较低。

**同算法水平相比**：其中`(10,2,0.1)`与`(20,2,0,2)`参数的效果比较好，前者的`overhead`最小，可以理解为网络负载小，并且送达率最高，平均跳数最少；后者的消息缓存时间较少，其原因是缓存表清理频次减少，并且泛洪时间较长。

具体文件已经上传至`reports`文件夹

## 3. 消息队列算法

参考[The ONE使用笔记：消息发送队列](http://sparkandshine.net/en/the-one-use-notes-messages-send-queue-random-fifo/)

重写实现我们自己的消息队列排序算法，本质上只改了一行，即可适配上述的缓存表环境

```java
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
```

重写的排序方法只处理队列模式为`Q_MODE_LFU`也即配置文件中`Group.sendQueue=3`的情况，其他的情况排序交给父类的方法。

16行注释以上的代码功能类似，使用lambda表达式创建一个`Comparator<?>`后进行判别类型转换，最终决定顺序的是17行的`m1`与`m2`变量位置。

出于实用主义的思想，两者分别在前的情况我都测试过，对应文件为

```
my_scenario_10_2_0.1_LFU_asc_MessageStatsReport.txt
my_scenario_10_2_0.1_LFU_desc_MessageStatsReport.txt
```

结果差别不大，`asc`的方式送达率略高一些，故最终代码选择`m1`在前，这样的排序结果就是升序。

## 4. 缓存清理算法

阅读原有的`protected boolean makeRoomForMessage(int size)`方法，发现其实现过程也非常简单易懂，就是先排除正在发送的消息，然后选择`getReceiveTime()`最小的，也就是最早的消息，然后将其删除，如此重复直到申请的空间小于空闲的空间。

结合上述缓存表的场景，其实关键代码只用更改一行，也即`protected Message getNextMessageToRemove(boolean excludeMsgBeingSent)`选择最早时间的判断语句。

完整实现如下，关注第**22-23**行，这其实是一行，比较长所以分开了

```java
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
            } else if (hostCacheCounter.getOrDefault(m.getTo(), 0) > 
                       hostCacheCounter.getOrDefault(messageToDelete.getTo(), 0)) {
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
```

从缓存表中选取**目的主机缓存次数最大**的消息，并将其删除

其实这里也是基于实用主义的想法，最大与最小的情况我都运行过，发现删除目的主机缓存次数最大的方式运行结果更好

分析其原因是，缓存**次数越大**，说明其**直接发送**的概率越大，**越不可能**在需要清理缓存时仍然存放在内存中，由此可以解释通。

针对`2.3 测试结果`中水平比较结果较好的两种情况，进行了最终版本的测试，结果文件如下

```
my_scenario_10_2_0.1_LFU_final_MessageStatsReport.txt
my_scenario_20_2_0.2_LFU_final_MessageStatsReport.txt
```

在送达率基本不变的情况下，略微增加了延迟与网络负载，但较大地减少了消息缓存时间 (百分之十以上)。

## 5. 总结

至此，实验要求的3个需求全部完成，且实验结果较好，所有代码和报告都已经上传[本人仓库](https://github.com/ouyangjunfei/the-one)。