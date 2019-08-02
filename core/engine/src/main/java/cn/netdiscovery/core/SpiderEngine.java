package cn.netdiscovery.core;

import cn.netdiscovery.core.config.Configuration;
import cn.netdiscovery.core.config.Constant;
import cn.netdiscovery.core.domain.Request;
import cn.netdiscovery.core.domain.bean.SpiderJobBean;
import cn.netdiscovery.core.quartz.ProxyPoolJob;
import cn.netdiscovery.core.quartz.QuartzManager;
import cn.netdiscovery.core.quartz.SpiderJob;
import cn.netdiscovery.core.queue.Queue;
import cn.netdiscovery.core.registry.Registry;
import cn.netdiscovery.core.utils.BooleanUtils;
import cn.netdiscovery.core.utils.NumberUtils;
import cn.netdiscovery.core.utils.UserAgent;
import cn.netdiscovery.core.vertx.VertxUtils;
import com.cv4j.proxy.ProxyManager;
import com.cv4j.proxy.ProxyPool;
import com.cv4j.proxy.domain.Proxy;
import com.safframework.tony.common.utils.IOUtils;
import com.safframework.tony.common.utils.Preconditions;
import io.reactivex.Flowable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static cn.netdiscovery.core.config.Constant.*;

/**
 * 可以管理多个 Spider 的容器
 * Created by tony on 2018/1/2.
 */
@Slf4j
public class SpiderEngine {

    @Getter
    private Queue queue;

    private HttpServer server;

    private boolean useMonitor = false;

    private RegisterConsumer registerConsumer;

    private Registry registry;

    private int defaultHttpdPort = 8715; // SpiderEngine 默认的端口号

    private AtomicInteger count = new AtomicInteger(0);

    private Map<String, Spider> spiders = new ConcurrentHashMap<>();

    private Map<String, SpiderJobBean> jobs = new ConcurrentHashMap<>();

    private SpiderEngine() {

        this(null);
    }

    private SpiderEngine(Queue queue) {

        this.queue = queue;

        initSpiderEngine();
    }

    /**
     * 初始化爬虫引擎，加载ua列表
     */
    private void initSpiderEngine() {

        String[] uaList = Constant.uaList;

        if (Preconditions.isNotBlank(uaList)) {

            Arrays.asList(uaList)
                    .parallelStream()
                    .forEach(name -> {

                        InputStream input = null;

                        try {
                            input = this.getClass().getResourceAsStream(name);
                            String inputString = IOUtils.inputStream2String(input); // input 流无须关闭，inputStream2String()方法里已经做了关闭流的操作
                            if (Preconditions.isNotBlank(inputString)) {
                                String[] ss = inputString.split("\r\n");
                                if (ss.length > 0) {

                                    Arrays.asList(ss).forEach(ua -> UserAgent.uas.add(ua));
                                }
                            }
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });

            com.cv4j.proxy.config.Constant.setUas(UserAgent.uas); // 让代理池也能够共享ua
        }

        try {
            defaultHttpdPort = NumberUtils.toInt(Configuration.getConfig("spiderEngine.config.port"));
            useMonitor = BooleanUtils.toBoolean(Configuration.getConfig("spiderEngine.config.useMonitor"));
        } catch (ClassCastException e) {
            defaultHttpdPort = 8715;
            useMonitor = false;
        }
    }

    public static SpiderEngine create() {

        return new SpiderEngine();
    }

    public static SpiderEngine create(Queue queue) {

        return new SpiderEngine(queue);
    }

    public SpiderEngine proxyList(List<Proxy> proxies) {

        ProxyPool.addProxyList(proxies);
        return this;
    }

    public SpiderEngine setUseMonitor(boolean useMonitor) {

        this.useMonitor = useMonitor;
        return this;
    }

    public SpiderEngine setRegistry(Registry registry) {

        this.registry = registry;
        return this;
    }

    /**
     * 添加爬虫到SpiderEngine，由SpiderEngine来管理
     *
     * @param spider
     * @return
     */
    public SpiderEngine addSpider(Spider spider) {

        if (spider != null && !spiders.containsKey(spider.getName())) {

            spiders.put(spider.getName(), spider);
        }
        return this;
    }

    /**
     * 在SpiderEngine中创建一个爬虫，使用SpiderEngine的Queue
     *
     * @param name
     * @return Spider
     */
    public Spider createSpider(String name) {

        if (!spiders.containsKey(name)) {

            Spider spider = Spider.create(this.getQueue()).name(name);
            spiders.put(name, spider);
            return spider;
        }

        return null;
    }

    /**
     * 对各个爬虫的状态进行监测，并返回json格式。
     * 如果要使用此方法，须放在run()之前
     * 采用默认的端口号
     * @return
     */
    public SpiderEngine httpd() {

        return httpd(defaultHttpdPort);
    }

    /**
     * 对各个爬虫的状态进行监测，并返回json格式。
     * 如果要使用此方法，须放在run()之前
     *
     * @param port
     */
    public SpiderEngine httpd(int port) {

        defaultHttpdPort = port;
        server = VertxUtils.getVertx().createHttpServer();

        Router router = Router.router(VertxUtils.getVertx());
        router.route().handler(BodyHandler.create());

        RouterHandler routerHandler = new RouterHandler(spiders,jobs,router,useMonitor);
        routerHandler.route();
        server.requestHandler(router::accept).listen(port);

        return this;
    }

    /**
     * 注册 Vert.x eventBus 的消费者
     * @param registerConsumer
     * @return
     */
    public SpiderEngine registerConsumers(RegisterConsumer registerConsumer) {

        this.registerConsumer = registerConsumer;
        return this;
    }

    /**
     * 关闭HttpServer
     */
    public void closeHttpServer() {

        if (server != null) {

            server.close();
        }
    }

    /**
     * 启动SpiderEngine中所有的spider，让每个爬虫并行运行起来。
     *
     */
    public void run() {

        log.info("\r\n" +
                "   _   _      _   ____  _\n" +
                "  | \\ | | ___| |_|  _ \\(_)___  ___ _____   _____ _ __ _   _\n" +
                "  |  \\| |/ _ \\ __| | | | / __|/ __/ _ \\ \\ / / _ \\ '__| | | |\n" +
                "  | |\\  |  __/ |_| |_| | \\__ \\ (_| (_) \\ V /  __/ |  | |_| |\n" +
                "  |_| \\_|\\___|\\__|____/|_|___/\\___\\___/ \\_/ \\___|_|   \\__, |\n" +
                "                                                      |___/");

        if (Preconditions.isNotBlank(spiders)) {

            if (registry!=null && registry.getProvider()!=null) {

                registry.register(registry.getProvider(), defaultHttpdPort);
            }

            if (registerConsumer!=null) {

                registerConsumer.process();
            }

            Flowable.fromIterable(spiders.values())
                    .parallel(spiders.values().size())
                    .runOn(Schedulers.io())
                    .map(new Function<Spider, Spider>() {

                        @Override
                        public Spider apply(Spider spider) throws Exception {

                            spider.run();

                            return spider;
                        }
                    })
                    .sequential()
                    .subscribe();

            Runtime.getRuntime().addShutdownHook(new Thread(()-> {
                log.info("stop all spiders");
                stopSpiders();
                QuartzManager.shutdownJobs();
            }));
        }
    }

    /**
     * 基于爬虫的名字，从SpiderEngine中获取爬虫
     *
     * @param name
     */
    public Spider getSpider(String name) {

        return spiders.get(name);
    }

    /**
     * 停止某个爬虫程序
     *
     * @param name
     */
    public void stopSpider(String name) {

        Spider spider = spiders.get(name);

        if (spider != null) {

            spider.stop();
        }
    }

    /**
     * 停止所有的爬虫程序
     */
    public void stopSpiders() {

        if (Preconditions.isNotBlank(spiders)) {

            spiders.forEach((s, spider) -> spider.stop());
        }
    }

    /**
     * 给 Spider 发起定时任务
     * @param spiderName
     * @param cron cron表达式
     * @param urls
     */
    public SpiderJobBean addSpiderJob(String spiderName, String cron, String... urls) {

        if (Preconditions.isNotBlank(urls)
                && spiders.get(spiderName)!=null
                && Preconditions.isNotBlank(cron)) {

            Request[] requests = new Request[urls.length];

            for (int i=0;i<urls.length;i++) {

                requests[i] = new Request(urls[i],spiderName).checkDuplicate(false);
            }

            return  addSpiderJob(spiderName,cron,requests);
        }

        return null;
    }

    /**
     * 给 Spider 发起定时任务
     * @param spiderName
     * @param cron cron表达式
     * @param requests
     */
    public SpiderJobBean addSpiderJob(String spiderName, String cron, Request... requests) {

        Spider spider = spiders.get(spiderName);

        if (spider!=null){
            String jobName = SPIDER_JOB_NAME + count.incrementAndGet();

            SpiderJobBean jobBean = new SpiderJobBean();
            jobBean.setJobName(jobName);
            jobBean.setJobGroupName(JOB_GROUP_NAME);
            jobBean.setTriggerName(TRIGGER_NAME);
            jobBean.setTriggerGroupName(TRIGGER_GROUP_NAME);
            jobBean.setCron(cron);
            jobBean.setRequests(requests);

            Stream.of(requests)
                    .filter(request -> request.isCheckDuplicate())
                    .forEach(request -> request.checkDuplicate(false));

            jobs.put(jobName, jobBean);
            QuartzManager.addJob(jobBean, SpiderJob.class, cron, spider, requests);

            return jobBean;
        }

        return null;
    }

    /**
     * 给 ProxyPool 发起定时任务
     * @param proxyMap
     * @param cron cron表达式
     * @return
     */
    public void addProxyPoolJob(Map<String, Class> proxyMap, String cron) {

        String jobName = PROXY_POOL_JOB_NAME + count.incrementAndGet();

        QuartzManager.addJob(jobName, JOB_GROUP_NAME, TRIGGER_NAME, TRIGGER_GROUP_NAME, ProxyPoolJob.class, cron, proxyMap);
    }

    /**
     * 需要在启动 SpiderEngine 之前，启动 ProxyPool
     */
    public void startProxyPool(Map<String, Class> proxyMap) {

        if (proxyMap == null) return;

        ProxyPool.proxyMap = proxyMap;
        ProxyManager proxyManager = ProxyManager.get();
        proxyManager.start();
    }

    /**
     * 注册 Vert.x eventBus 的消费者
     */
    @FunctionalInterface
    public interface RegisterConsumer {

        void process();
    }
}