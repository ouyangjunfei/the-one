# The ONE

The Opportunistic Network Environment simulator.

For introduction and releases, see [the ONE homepage at GitHub](http://akeranen.github.io/the-one/).

For instructions on how to get started, see [the README](https://github.com/akeranen/the-one/wiki/README).

The [wiki page](https://github.com/akeranen/the-one/wiki) has the latest information.

## 文档参考

The ONE [官方文档](https://github.com/akeranen/the-one/wiki/README)
，其没有作为项目的README

## 使用IDE进行二次开发

- JDK版本推荐`1.8`及以下

- 在`lib`中新增了`junit-4.3.1.jar`用以解决`src/test`下的报错，不添加jar包直接删去所有的测试文件也可以

- 导入项目至IDEA或者Eclipse，配置好JDK和第三方lib路径(编译器有可能会自动识别)，即可开始二次开发
  - 我使用的是IDEA，后面以此环境做阐述，没有本质区别

- 建议将IDE的编译输出模块路径改为`target`与原项目一致，否则需要每次改动后手动运行`compile.bat`进行编译 ，再通过`one.bat`运行
  - 比如会出现找不到自定义Router类的报错
  - 修改后可以直接在IDE内运行或者Debug

## 路由算法实现

参考资料[The ONE使用笔记：目录](http://sparkandshine.net/the-one-use-notes-directory/)
**路由协议**章节

- 所有的路由协议都是`ActiveRouter.java`的子类，而`ActiveRouter.java`又是`MessageRouter.java`的子类

- 建议先阅读`MessageRouter.java`，其包含路由定义的一些基本信息和处理过程，包括所属主机、缓存大小、TTL、发送队列策略，以及将要接收、承载、已经接收、黑名单信息

- 然后是`ActiveRouter.java`，其描述当前活跃的路由，包含一些便捷方法用于进行Host、Connection和Message的交互

- `DirectDeliveryRouter.java`是一种最基本的路由算法实现，只允许直接交付，不进行转发

- `EpidemicRouter.java`则类似泛洪似的消息转发传递，这两种实现的代码都非常简单，可以阅读并理解其基本含义后在此基础上进行二次开发

- 自己实现的路由算法位于`src/routing/MyEpidemicRouter.java`